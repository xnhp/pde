package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.api.ApiAnalysisProblem
import cn.varsa.pde.resolver.api.ApiAnalysisReport
import cn.varsa.pde.resolver.api.ApiAnalysisReportJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.optional
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.jar.Manifest
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory

private val apiFiltersLogger: Logger = Logger.getLogger("pde-launch-engine")
private val apiFiltersMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiAnalyzeProblemReport(
  val schemaVersion: Int = 1,
  val generatedAt: String? = null,
  val tool: String? = null,
  val problems: List<ApiAnalyzeProblem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiAnalyzeProblem(
  val problemRef: String? = null,
  val bundleBsn: String? = null,
  val bundleDir: String? = null,
  val resourceType: String? = null,
  val resourcePath: String? = null,
  val problemId: Int? = null,
  val messageArgs: List<String>? = null,
  val severity: String? = null,
  val category: String? = null,
  val message: String? = null
)

private data class ApiFilterEntry(
  val type: String,
  val path: String?,
  val id: Int,
  val args: List<String>,
  val comment: String?
) {
  fun describe(): String = buildString {
    append("type=").append(type)
    path?.let { append(" path=").append(it) }
    append(" id=").append(id)
    append(" args=").append(args.joinToString(" | ", prefix = "[", postfix = "]"))
    comment?.let { append(" comment=\"").append(it).append('"') }
  }
}

private enum class UpsertResult {
  CREATED,
  UPDATED,
  SKIPPED
}

// ---------------------------------------------------------------------------------------------
// Surgical .api_filters editing.
//
// Eclipse PDE writes .api_filters with 4-space indentation, alphabetically ordered attributes and
// resources in insertion order. Round-tripping the file through a DOM serializer rewrites every
// line (indentation, resource order, header), which makes the diff of a small prune or add
// unreadable. Instead we locate elements with a small offset-tracking scanner over the original
// text and apply removals/insertions as byte-range edits, so untouched parts of the file stay
// identical. If the file cannot be scanned, or the edited result does not parse back to the
// expected model, we fall back to a full rewrite and say so on stderr.
// ---------------------------------------------------------------------------------------------

private class XmlSourceElement(
  val name: String,
  val attributes: Map<String, String>,
  val start: Int,
  val children: MutableList<XmlSourceElement> = mutableListOf()
) {
  /** Offset just past the closing tag (or past the self-closing tag). */
  var end: Int = -1
  /** Offset of the '<' of the closing tag; equals [start] for self-closing elements. */
  var closeTagStart: Int = -1
}

private object XmlSourceScanner {
  private val attributePattern = Regex("""([^\s=/>]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
  private val namePattern = Regex("""[^\s/>]+""")

  fun unescape(value: String): String {
    if (!value.contains('&')) return value
    return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos);").replace(value) { m ->
      when (val ref = m.groupValues[1]) {
        "lt" -> "<"
        "gt" -> ">"
        "amp" -> "&"
        "quot" -> "\""
        "apos" -> "'"
        else -> {
          val code = if (ref.startsWith("#x")) ref.substring(2).toInt(16) else ref.substring(1).toInt()
          String(Character.toChars(code))
        }
      }
    }
  }

  /** Returns the root element. Throws IllegalArgumentException on anything it cannot follow. */
  fun scan(text: String): XmlSourceElement {
    val stack = ArrayDeque<XmlSourceElement>()
    var root: XmlSourceElement? = null
    var i = 0
    fun skipTo(marker: String, from: Int): Int {
      val idx = text.indexOf(marker, from)
      if (idx < 0) throw IllegalArgumentException("Unterminated construct at offset $from")
      return idx + marker.length
    }
    while (i < text.length) {
      if (text[i] != '<') {
        i++
        continue
      }
      when {
        text.startsWith("<!--", i) -> i = skipTo("-->", i + 4)
        text.startsWith("<![CDATA[", i) -> i = skipTo("]]>", i + 9)
        text.startsWith("<?", i) -> i = skipTo("?>", i + 2)
        text.startsWith("<!", i) -> i = skipTo(">", i + 2)
        text.startsWith("</", i) -> {
          val close = text.indexOf('>', i)
          if (close < 0) throw IllegalArgumentException("Unterminated end tag at offset $i")
          val name = text.substring(i + 2, close).trim()
          val element = stack.removeLastOrNull()
            ?: throw IllegalArgumentException("Unexpected end tag </$name> at offset $i")
          if (element.name != name) {
            throw IllegalArgumentException("Mismatched end tag </$name> for <${element.name}> at offset $i")
          }
          element.closeTagStart = i
          element.end = close + 1
          i = element.end
        }
        else -> {
          val tagEnd = findTagEnd(text, i)
          val tagBody = text.substring(i + 1, tagEnd)
          val selfClosing = tagBody.trimEnd().endsWith("/")
          val body = if (selfClosing) tagBody.trimEnd().dropLast(1) else tagBody
          val nameMatch = namePattern.find(body)
            ?: throw IllegalArgumentException("Missing element name at offset $i")
          val attributes = linkedMapOf<String, String>()
          attributePattern.findAll(body.substring(nameMatch.range.last + 1)).forEach { m ->
            attributes[m.groupValues[1]] = unescape(m.groups[2]?.value ?: m.groups[3]?.value ?: "")
          }
          val element = XmlSourceElement(nameMatch.value, attributes, i)
          if (stack.isEmpty()) {
            if (root != null) throw IllegalArgumentException("Multiple root elements")
            root = element
          } else {
            stack.last().children += element
          }
          if (selfClosing) {
            element.closeTagStart = i
            element.end = tagEnd + 1
          } else {
            stack.addLast(element)
          }
          i = tagEnd + 1
        }
      }
    }
    if (stack.isNotEmpty()) throw IllegalArgumentException("Unclosed element <${stack.last().name}>")
    return root ?: throw IllegalArgumentException("No root element")
  }

  private fun findTagEnd(text: String, start: Int): Int {
    var i = start + 1
    var quote: Char? = null
    while (i < text.length) {
      val c = text[i]
      if (quote != null) {
        if (c == quote) quote = null
      } else if (c == '"' || c == '\'') {
        quote = c
      } else if (c == '>') {
        return i
      }
      i++
    }
    throw IllegalArgumentException("Unterminated start tag at offset $start")
  }
}

private fun xmlAttribute(value: String): String = buildString(value.length + 8) {
  value.forEach { c ->
    when (c) {
      '&' -> append("&amp;")
      '<' -> append("&lt;")
      '>' -> append("&gt;")
      '"' -> append("&quot;")
      '\n' -> append("&#10;")
      '\r' -> append("&#13;")
      '\t' -> append("&#9;")
      else -> append(c)
    }
  }
}

private class ApiFiltersSource(val text: String, val root: XmlSourceElement) {
  val eol: String = if (text.contains("\r\n")) "\r\n" else "\n"
  val resources: List<XmlSourceElement> = root.children.filter { it.name == "resource" }
  val unit: String = run {
    val resourceIndent = resources.firstOrNull()?.let { indentOf(it.start) }
    val filterIndent = resources.firstOrNull()?.children?.firstOrNull { it.name == "filter" }?.let { indentOf(it.start) }
    when {
      resourceIndent != null && filterIndent != null && filterIndent.length > resourceIndent.length ->
        filterIndent.substring(resourceIndent.length)
      !resourceIndent.isNullOrEmpty() -> resourceIndent
      else -> "    "
    }
  }

  fun lineStart(offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

  /** Leading whitespace of the line containing [offset], or null if that line has other content before it. */
  fun indentOf(offset: Int): String? {
    val ls = lineStart(offset)
    val prefix = text.substring(ls, offset)
    return prefix.takeIf { it.isBlank() }
  }

  fun indentOrEmpty(offset: Int): String = indentOf(offset) ?: ""

  /** Range covering the element including its own line's indentation and trailing newline, when it owns the line. */
  fun lineRange(element: XmlSourceElement): IntRange {
    var from = element.start
    val indent = indentOf(element.start)
    if (indent != null) from = lineStart(element.start)
    var to = element.end
    while (to < text.length && (text[to] == ' ' || text[to] == '\t')) to++
    if (to < text.length && text[to] == '\r') to++
    if (to < text.length && text[to] == '\n') {
      to++
    } else if (indent != null) {
      // Element ends the file without a newline: keep the deletion to the element itself.
      to = element.end
    }
    return from until to
  }
}

private class ApiFilterItem(
  var entry: ApiFilterEntry,
  val sourceFilter: XmlSourceElement?,
  val sourceResource: XmlSourceElement?,
  val isNew: Boolean = false
) {
  var dirty = false
}

private class ApiFiltersFile(
  val file: Path,
  val componentId: String,
  private val items: MutableList<ApiFilterItem>,
  private val source: ApiFiltersSource?,
  private val existed: Boolean
) {
  private val removedItems = mutableListOf<ApiFilterItem>()

  val entries: List<ApiFilterEntry> get() = items.map { it.entry }

  fun upsert(type: String, path: String?, id: Int, args: List<String>, comment: String?): UpsertResult {
    val normalizedType = type.trim()
    val normalizedPath = path?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedArgs = args.map { it.trim() }
    val normalizedComment = comment?.trim()?.takeIf { it.isNotEmpty() }
    val item = items.firstOrNull {
      it.entry.type == normalizedType && it.entry.path == normalizedPath && it.entry.id == id && it.entry.args == normalizedArgs
    }
    if (item == null) {
      items += ApiFilterItem(ApiFilterEntry(normalizedType, normalizedPath, id, normalizedArgs, normalizedComment), null, null, isNew = true)
      return UpsertResult.CREATED
    }
    return if (item.entry.comment == normalizedComment) {
      UpsertResult.SKIPPED
    } else {
      item.entry = item.entry.copy(comment = normalizedComment)
      item.dirty = true
      UpsertResult.UPDATED
    }
  }

  private fun typeMatches(filterType: String, searchType: String): Boolean =
    filterType == searchType || (filterType + "$") in searchType

  private fun argsMatch(entryArgs: List<String>, searchArgs: List<String>): Boolean =
    entryArgs == searchArgs || entryArgs.all { ea -> searchArgs.any { sa -> ea in sa || sa in ea } }

  /** Removes matching filters and returns them (empty when nothing matched). */
  fun removeMatching(type: String, path: String?, args: List<String>): List<ApiFilterEntry> {
    val normalizedType = type.trim()
    val normalizedPath = path?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedArgs = args.map { it.trim() }
    val candidates = items.filter {
      typeMatches(it.entry.type, normalizedType) &&
        (normalizedPath == null || it.entry.path == normalizedPath)
    }
    val matched = candidates.filter { argsMatch(it.entry.args, normalizedArgs) }
    val toRemove = when {
      matched.isNotEmpty() -> matched
      candidates.size == 1 -> candidates
      else -> emptyList()
    }
    if (toRemove.isEmpty()) return emptyList()
    items.removeAll(toRemove)
    removedItems += toRemove
    return toRemove.map { it.entry }
  }

  fun hasChanges(): Boolean = removedItems.isNotEmpty() || items.any { it.isNew || it.dirty }

  fun write() {
    if (existed && !hasChanges()) return
    file.parent?.let { Files.createDirectories(it) }
    val text = renderEdited() ?: run {
      if (existed) {
        System.err.println(
          "warning: ${file.toAbsolutePath().normalize()} could not be edited in place; rewriting the whole file"
        )
      }
      renderWhole()
    }
    Files.writeString(file, text)
  }

  private fun renderFilter(entry: ApiFilterEntry, indent: String, unit: String, eol: String): String = buildString {
    append(indent).append("<filter")
    entry.comment?.let { append(" comment=\"").append(xmlAttribute(it)).append('"') }
    append(" id=\"").append(entry.id).append("\">").append(eol)
    append(indent).append(unit).append("<message_arguments>").append(eol)
    entry.args.forEach { value ->
      append(indent).append(unit).append(unit)
        .append("<message_argument value=\"").append(xmlAttribute(value)).append("\"/>").append(eol)
    }
    append(indent).append(unit).append("</message_arguments>").append(eol)
    append(indent).append("</filter>").append(eol)
  }

  private fun renderResource(
    type: String,
    path: String?,
    entries: List<ApiFilterEntry>,
    indent: String,
    unit: String,
    eol: String
  ): String = buildString {
    append(indent).append("<resource")
    path?.let { append(" path=\"").append(xmlAttribute(it)).append('"') }
    append(" type=\"").append(xmlAttribute(type)).append("\">").append(eol)
    entries.forEach { append(renderFilter(it, indent + unit, unit, eol)) }
    append(indent).append("</resource>").append(eol)
  }

  private fun sortedEntries(): List<ApiFilterEntry> = items.map { it.entry }.sortedWith(
    compareBy<ApiFilterEntry>(
      { it.path == null }, { it.path ?: "" }, { it.type }, { it.id }, { it.args.joinToString(" ") }, { it.comment ?: "" }
    )
  )

  private fun groupByResource(entries: List<ApiFilterEntry>): Map<Pair<String, String?>, List<ApiFilterEntry>> {
    val byResource = linkedMapOf<Pair<String, String?>, MutableList<ApiFilterEntry>>()
    entries.forEach { byResource.computeIfAbsent(it.type to it.path) { mutableListOf() }.add(it) }
    return byResource
  }

  /** Full rewrite in Eclipse PDE style (4-space indentation, alphabetical attributes). */
  private fun renderWhole(): String {
    val unit = "    "
    val eol = "\n"
    return buildString {
      append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>").append(eol)
      append("<component id=\"").append(xmlAttribute(componentId)).append("\" version=\"2\">").append(eol)
      groupByResource(sortedEntries()).forEach { (key, resourceEntries) ->
        append(renderResource(key.first, key.second, resourceEntries, unit, unit, eol))
      }
      append("</component>").append(eol)
    }
  }

  private class Edit(val start: Int, val end: Int, val replacement: String, val seq: Int)

  /** Applies the pending changes as text edits on the original file; null when that is not possible. */
  private fun renderEdited(): String? {
    val src = source ?: return null
    if (!hasChanges()) return src.text
    val edits = mutableListOf<Edit>()
    var seq = 0
    fun edit(range: IntRange, replacement: String) {
      edits += Edit(range.first, range.last + 1, replacement, seq++)
    }
    fun insert(offset: Int, text: String) {
      edits += Edit(offset, offset, text, seq++)
    }

    // Group additions by target resource; resources losing their last filter are deleted whole.
    val remainingPerResource = src.resources.associateWith { resource ->
      items.count { it.sourceResource === resource }
    }.toMutableMap()
    val additionsByResource = linkedMapOf<XmlSourceElement, MutableList<ApiFilterEntry>>()
    val additionsNewResources = linkedMapOf<Pair<String, String?>, MutableList<ApiFilterEntry>>()
    items.filter { it.isNew }.forEach { item ->
      val target = src.resources.firstOrNull { resource ->
        resource.attributes["type"]?.trim() == item.entry.type &&
          resource.attributes["path"]?.trim()?.takeIf { it.isNotEmpty() } == item.entry.path
      }
      if (target != null) {
        additionsByResource.computeIfAbsent(target) { mutableListOf() } += item.entry
        remainingPerResource[target] = (remainingPerResource[target] ?: 0) + 1
      } else {
        additionsNewResources.computeIfAbsent(item.entry.type to item.entry.path) { mutableListOf() } += item.entry
      }
    }
    val deletedResources = src.resources.filter { resource ->
      (remainingPerResource[resource] ?: 0) == 0 && removedItems.any { it.sourceResource === resource }
    }.toSet()
    deletedResources.forEach { edit(src.lineRange(it), "") }

    removedItems.forEach { item ->
      val filter = item.sourceFilter ?: return@forEach
      if (item.sourceResource in deletedResources) return@forEach
      edit(src.lineRange(filter), "")
    }

    items.filter { it.dirty && it.sourceFilter != null }.forEach { item ->
      val filter = item.sourceFilter!!
      val range = src.lineRange(filter)
      val ownsLine = src.indentOf(filter.start) != null && range.last + 1 > filter.end
      val rendered = renderFilter(item.entry, src.indentOrEmpty(filter.start), src.unit, src.eol)
      edit(range, if (ownsLine) rendered else rendered.trim())
    }

    additionsByResource.forEach { (resource, entries) ->
      val existingFilter = resource.children.firstOrNull { it.name == "filter" }
      val indent = existingFilter?.let { src.indentOf(it.start) } ?: (src.indentOrEmpty(resource.start) + src.unit)
      val rendered = entries.joinToString("") { renderFilter(it, indent, src.unit, src.eol) }
      if (src.indentOf(resource.closeTagStart) != null) {
        insert(src.lineStart(resource.closeTagStart), rendered)
      } else {
        // `</resource>` shares a line with other content: break the line before it.
        insert(resource.closeTagStart, src.eol + rendered + src.indentOrEmpty(resource.start))
      }
    }

    if (additionsNewResources.isNotEmpty()) {
      val component = src.root
      val indent = src.resources.firstOrNull()?.let { src.indentOf(it.start) } ?: src.unit
      val rendered = additionsNewResources.entries.joinToString("") { (key, entries) ->
        renderResource(key.first, key.second, entries, indent, src.unit, src.eol)
      }
      if (src.indentOf(component.closeTagStart) != null) {
        insert(src.lineStart(component.closeTagStart), rendered)
      } else {
        insert(component.closeTagStart, src.eol + rendered)
      }
    }

    // Apply back-to-front; equal offsets keep insertion order.
    val sorted = edits.sortedWith(compareByDescending<Edit> { it.start }.thenByDescending { it.seq })
    val out = StringBuilder(src.text)
    var lastStart = Int.MAX_VALUE
    for (e in sorted) {
      if (e.end > lastStart) return null // overlapping edits: give up on in-place editing
      out.replace(e.start, e.end, e.replacement)
      lastStart = e.start
    }
    val result = out.toString()
    return if (verify(result)) result else null
  }

  /** The edited text must parse and describe exactly the entries we hold. */
  private fun verify(text: String): Boolean = runCatching {
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(text.byteInputStream(Charsets.UTF_8)).documentElement
    parseEntries(root).sortedBy { it.toString() } == items.map { it.entry }.sortedBy { it.toString() }
  }.getOrDefault(false)

  companion object {
    private fun parseEntries(root: Element): List<ApiFilterEntry> {
      val entries = mutableListOf<ApiFilterEntry>()
      val resources = root.childNodes
      for (i in 0 until resources.length) {
        val resourceNode = resources.item(i)
        if (resourceNode !is Element || resourceNode.nodeName != "resource") continue
        val type = resourceNode.getAttribute("type").trim().takeIf { it.isNotEmpty() } ?: continue
        val path = resourceNode.getAttribute("path").trim().takeIf { it.isNotEmpty() }
        val filters = resourceNode.childNodes
        for (j in 0 until filters.length) {
          val filterNode = filters.item(j)
          if (filterNode !is Element || filterNode.nodeName != "filter") continue
          val id = filterNode.getAttribute("id").trim().toIntOrNull() ?: continue
          val comment = filterNode.getAttribute("comment").trim().takeIf { it.isNotEmpty() }
          val args = mutableListOf<String>()
          val filterChildren = filterNode.childNodes
          for (k in 0 until filterChildren.length) {
            val argumentsNode = filterChildren.item(k)
            if (argumentsNode !is Element || argumentsNode.nodeName != "message_arguments") continue
            val argNodes = argumentsNode.childNodes
            for (m in 0 until argNodes.length) {
              val arg = argNodes.item(m)
              if (arg is Element && arg.nodeName == "message_argument") {
                args += arg.getAttribute("value").trim()
              }
            }
          }
          entries += ApiFilterEntry(type = type, path = path, id = id, args = args, comment = comment)
        }
      }
      return entries
    }

    private fun validateComponent(id: String?, version: String?, expectedBsn: String, file: Path): String {
      val where = file.toAbsolutePath().normalize()
      if (version != "2") {
        throw IllegalArgumentException("Unsupported .api_filters version '$version' in $where")
      }
      val componentId = id?.trim().orEmpty()
      if (componentId.isEmpty()) {
        throw IllegalArgumentException("Missing component id in $where")
      }
      if (componentId != expectedBsn) {
        throw IllegalArgumentException("Component id '$componentId' does not match bundle BSN '$expectedBsn' in $where")
      }
      return componentId
    }

    fun load(bundleDir: Path, expectedBsn: String): ApiFiltersFile {
      val file = bundleDir.resolve(".settings").resolve(".api_filters")
      if (!Files.exists(file)) {
        return ApiFiltersFile(file, expectedBsn, mutableListOf(), source = null, existed = false)
      }
      val text = Files.readString(file)
      val domRoot = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(text.byteInputStream(Charsets.UTF_8)).documentElement
      if (domRoot == null || domRoot.nodeName != "component") {
        throw IllegalArgumentException("Invalid .api_filters root in ${file.toAbsolutePath().normalize()}")
      }
      val componentId = validateComponent(domRoot.getAttribute("id"), domRoot.getAttribute("version"), expectedBsn, file)
      val domEntries = parseEntries(domRoot)

      // Preferred path: offset-tracking scan so edits can be applied in place. The scanner must
      // agree with the real XML parser, otherwise we do not trust its offsets.
      val scanned = runCatching { XmlSourceScanner.scan(text) }.getOrNull()
      if (scanned != null && scanned.name == "component") {
        val src = ApiFiltersSource(text, scanned)
        val items = mutableListOf<ApiFilterItem>()
        src.resources.forEach { resource ->
          val type = resource.attributes["type"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
          val path = resource.attributes["path"]?.trim()?.takeIf { it.isNotEmpty() }
          resource.children.filter { it.name == "filter" }.forEach { filter ->
            val id = filter.attributes["id"]?.trim()?.toIntOrNull() ?: return@forEach
            val comment = filter.attributes["comment"]?.trim()?.takeIf { it.isNotEmpty() }
            val args = filter.children.filter { it.name == "message_arguments" }
              .flatMap { it.children }.filter { it.name == "message_argument" }
              .map { it.attributes["value"]?.trim().orEmpty() }
            items += ApiFilterItem(ApiFilterEntry(type, path, id, args, comment), filter, resource)
          }
        }
        if (domEntries == items.map { it.entry }) {
          return ApiFiltersFile(file, componentId, items, src, existed = true)
        }
      }

      // Fallback: DOM only; write() rewrites the whole file and warns on stderr.
      val items = domEntries.map { ApiFilterItem(it, null, null) }.toMutableList()
      return ApiFiltersFile(file, componentId, items, source = null, existed = true)
    }
  }
}

internal fun inferReportPaths(reportOpt: String?): List<Path> {
  if (!reportOpt.isNullOrBlank()) return listOf(Paths.get(reportOpt))
  val reportsDir = discoverConfigFile()
    ?.parent
    ?.resolve(".api-baseline")
    ?.resolve("reports")
    ?: Paths.get(".api-baseline/reports")
  if (!Files.isDirectory(reportsDir)) return emptyList()
  return Files.list(reportsDir)
    .filter { it.toString().endsWith(".json") }
    .sorted()
    .toList()
}

internal fun apiBaselineFiltersAddAllFromReportMain(args: Array<String>): Int {
  val parser = ArgParser("pde api-baseline filters add-all-from-report")
  val reportOpt by parser.option(
    ArgType.String,
    fullName = "report",
    description = "Path to a report JSON from 'pde api-baseline check'; auto-inferred from .api-baseline/reports/ when absent"
  )
  val problemRefs by parser.option(
    ArgType.String,
    fullName = "problem",
    description = "Select a specific problemRef (repeatable; use --all to select everything)"
  ).multiple()
  val allOpt by parser.option(
    ArgType.Boolean,
    fullName = "all",
    description = "Select all problems in the report(s); can still be narrowed with --bundle, --category, --severity"
  ).default(false)
  val bundles by parser.option(
    ArgType.String,
    fullName = "bundle",
    description = "Narrow selection to specific bundle BSNs (repeatable)"
  ).multiple()
  val categories by parser.option(
    ArgType.String,
    fullName = "category",
    description = "Narrow selection to specific problem categories (repeatable, case-insensitive)"
  ).multiple()
  val severities by parser.option(
    ArgType.String,
    fullName = "severity",
    description = "Narrow selection to specific severities, e.g. 'error', 'warning' (repeatable, case-insensitive)"
  ).multiple()
  val commentTemplateOpt by parser.option(
    ArgType.String,
    fullName = "comment-template",
    description = "Filter comment with {problemRef}, {bundleBsn}, {timestamp} placeholders"
  )
  val allowEmptySelectionOpt by parser.option(
    ArgType.Boolean,
    fullName = "allow-empty-selection",
    description = "Exit 0 when the selection yields no problems (default: exit 3)"
  ).default(false)
  val allowMissingFieldsOpt by parser.option(
    ArgType.Boolean,
    fullName = "allow-missing-fields",
    description = "Skip problems with missing required fields instead of failing"
  ).default(false)
  val dryRunOpt by parser.option(
    ArgType.Boolean,
    fullName = "dry-run",
    description = "Print the filters that would be added or updated without writing .api_filters"
  ).default(false)
  val reportPos by parser.argument(
    ArgType.String,
    description = "Path to a report JSON; auto-inferred from .api-baseline/reports/ when absent"
  ).optional()

  parser.parse(args)

  val reportPaths = inferReportPaths(reportOpt ?: reportPos)
  if (reportPaths.isEmpty()) {
    apiFiltersLogger.severe("No report found. Pass --report or run 'pde api-baseline check' first.")
    return 2
  }
  if (!allOpt && problemRefs.isEmpty()) {
    apiFiltersLogger.severe("Specify --problem <ref> (repeatable) or --all")
    return 2
  }
  val allProblems = run {
    val acc = mutableListOf<ApiAnalyzeProblem>()
    for (path in reportPaths) {
      val problems = runCatching { readApiAnalyzeProblemReport(path).problems }.getOrElse { error ->
        apiFiltersLogger.severe("Failed to parse report ${path}: ${error.message}")
        return 4
      }
      acc += problems
    }
    acc
  }

  val selectedRefs = problemRefs.toSet()
  var selected = if (allOpt) {
    allProblems
  } else {
    allProblems.filter { selectedRefs.contains(it.problemRef) }
  }
  if (bundles.isNotEmpty()) {
    val bsnSet = bundles.toSet()
    selected = selected.filter { bsnSet.contains(it.bundleBsn) }
  }
  if (categories.isNotEmpty()) {
    val categorySet = categories.map { it.lowercase() }.toSet()
    selected = selected.filter { it.category?.lowercase()?.let(categorySet::contains) == true }
  }
  if (severities.isNotEmpty()) {
    val severitySet = severities.map { it.lowercase() }.toSet()
    selected = selected.filter { it.severity?.lowercase()?.let(severitySet::contains) == true }
  }

  // These categories are never suppressible via .api_filters: Eclipse PDE API Tools'
  // ApiProblemFactory always constructs them with a null type name --
  // newApiVersionNumberProblem/newApiBaselineProblem/newApiComponentResolutionProblem/
  // newFatalProblem all pass `null` for typeName -- so they can never carry the resourceType an
  // .api_filters entry requires. "version" needs a manifest version bump, "api-baseline" needs the
  // bundle added to the reference baseline, "component-resolution"/"fatal" need the underlying
  // resolution problem fixed; none of them are fixed by adding a filter.
  val unsuppressibleCategories = setOf("version", "api-baseline", "component-resolution", "fatal")
  val unsuppressibleProblems = selected.filter { it.category?.lowercase() in unsuppressibleCategories }
  if (unsuppressibleProblems.isNotEmpty()) {
    apiFiltersLogger.info(
      "Skipping ${unsuppressibleProblems.size} problem(s) not suppressible via .api_filters " +
        "(categories: ${unsuppressibleProblems.mapNotNull { it.category }.toSet().joinToString(", ")}): " +
        unsuppressibleProblems.mapNotNull { it.problemRef }.joinToString(", ")
    )
    selected = selected - unsuppressibleProblems.toSet()
  }

  if (selected.isEmpty()) {
    if (allowEmptySelectionOpt) {
      apiFiltersLogger.info("No problems selected; nothing to do.")
      return 0
    }
    apiFiltersLogger.severe("Selection resolved to no problems")
    return 3
  }

  val invalid = selected.filter {
    it.bundleBsn.isNullOrBlank() ||
      it.resourceType.isNullOrBlank() ||
      it.problemId == null ||
      it.messageArgs == null
  }
  if (invalid.isNotEmpty()) {
    if (allowMissingFieldsOpt) {
      invalid.forEach { problem ->
        val missing = buildList {
          if (problem.bundleBsn.isNullOrBlank()) add("bundleBsn")
          if (problem.resourceType.isNullOrBlank()) add("resourceType")
          if (problem.problemId == null) add("problemId")
          if (problem.messageArgs == null) add("messageArgs")
        }
        apiFiltersLogger.warning("Skipping ${problem.problemRef ?: "<no-ref>"}: missing fields $missing")
      }
      selected = selected - invalid.toSet()
    } else {
      val refs = invalid.map { it.problemRef ?: "<missing>" }
      apiFiltersLogger.severe("Selected problems missing required fields: ${refs.joinToString(", ")}")
      return 5
    }
  }

  val stores = mutableMapOf<Path, ApiFiltersFile>()
  var created = 0
  var updated = 0
  var skipped = 0
  val now = Instant.now().toString()
  selected.forEach { problem ->
    val bsn = problem.bundleBsn!!
    val bundleDir = resolveBundleDir(problem)
    if (bundleDir == null) {
      apiFiltersLogger.severe("Cannot resolve bundle directory for ${problem.problemRef ?: "<no-ref>"} ($bsn)")
      return 5
    }
    val store = stores.getOrPut(bundleDir) { ApiFiltersFile.load(bundleDir, bsn) }
    val comment = commentTemplateOpt?.let { template ->
      template
        .replace("{problemRef}", problem.problemRef ?: "")
        .replace("{bundleBsn}", bsn)
        .replace("{timestamp}", now)
    }
    val entry = ApiFilterEntry(
      type = problem.resourceType!!.trim(),
      path = problem.resourcePath?.trim()?.takeIf { it.isNotEmpty() },
      id = problem.problemId!!,
      args = problem.messageArgs!!.map { it.trim() },
      comment = comment?.trim()?.takeIf { it.isNotEmpty() }
    )
    val result = store.upsert(entry.type, entry.path, entry.id, entry.args, entry.comment)
    when (result) {
      UpsertResult.CREATED -> created++
      UpsertResult.UPDATED -> updated++
      UpsertResult.SKIPPED -> skipped++
    }
    if (dryRunOpt && result != UpsertResult.SKIPPED) {
      println("would ${result.name.lowercase()} ${store.file}: ${entry.describe()}")
    }
  }

  if (dryRunOpt) {
    apiFiltersLogger.info(
      "api-baseline filters add-all-from-report (dry-run, nothing written): created=$created updated=$updated skipped=$skipped"
    )
    return 0
  }
  stores.values.forEach { it.write() }
  apiFiltersLogger.info("api-baseline filters add-all-from-report: created=$created updated=$updated skipped=$skipped")
  return 0
}

internal fun apiBaselineFiltersAddFilterMain(args: Array<String>): Int {
  val parser = ArgParser("pde api-baseline filters add-filter")
  val reportOpt by parser.option(
    ArgType.String,
    fullName = "report",
    description = "Path to a report JSON from 'pde api-baseline check'; auto-inferred from .api-baseline/reports/ when absent"
  )
  val commentTemplateOpt by parser.option(
    ArgType.String,
    fullName = "comment-template",
    description = "Filter comment with {problemRef}, {bundleBsn}, {timestamp} placeholders"
  )
  val idArg by parser.argument(
    ArgType.String,
    description = "problemRef from the report (e.g. P000001)"
  )

  parser.parse(args)

  val reportPaths = inferReportPaths(reportOpt)
  if (reportPaths.isEmpty()) {
    apiFiltersLogger.severe("No report found. Pass --report or run 'pde api-baseline check' first.")
    return 2
  }

  val allProblems = run {
    val acc = mutableListOf<ApiAnalyzeProblem>()
    for (path in reportPaths) {
      val problems = runCatching { readApiAnalyzeProblemReport(path).problems }.getOrElse { error ->
        apiFiltersLogger.severe("Failed to parse report ${path}: ${error.message}")
        return 4
      }
      acc += problems
    }
    acc
  }

  val problem = allProblems.firstOrNull { it.problemRef == idArg }
  if (problem == null) {
    apiFiltersLogger.severe("Problem '$idArg' not found in report(s). Check 'pde api-baseline check' output.")
    return 3
  }

  if (problem.bundleBsn.isNullOrBlank() ||
    problem.resourceType.isNullOrBlank() ||
    problem.problemId == null ||
    problem.messageArgs == null
  ) {
    apiFiltersLogger.severe("Problem '$idArg' is missing required fields (bundleBsn, resourceType, problemId, messageArgs)")
    return 5
  }

  val bsn = problem.bundleBsn
  val bundleDir = resolveBundleDir(problem)
  if (bundleDir == null) {
    apiFiltersLogger.severe("Cannot resolve bundle directory for '$idArg' ($bsn)")
    return 5
  }

  val now = Instant.now().toString()
  val store = ApiFiltersFile.load(bundleDir, bsn)
  val comment = commentTemplateOpt?.let { template ->
    template
      .replace("{problemRef}", problem.problemRef ?: "")
      .replace("{bundleBsn}", bsn)
      .replace("{timestamp}", now)
  }
  val result = store.upsert(
    type = problem.resourceType,
    path = problem.resourcePath,
    id = problem.problemId,
    args = problem.messageArgs,
    comment = comment
  )
  store.write()
  apiFiltersLogger.info("api-baseline filters add-filter '$idArg': $result")
  return 0
}

// Eclipse PDE API Tools' own message for an IApiProblem.UNUSED_PROBLEM_FILTERS problem
// (see problemmessages.properties key 30): "The API problem filter for: ''{0}'' is no longer
// used", where {0} is literally the ORIGINAL suppressed problem's fully rendered message (see
// BaseApiAnalyzer.createUnusedApiFilterProblems, which passes
// `filter.getUnderlyingProblem().getMessage()` as the sole message argument). That means the
// stale .api_filters <filter> entry can be found again by looking, within the same resource
// (type + path) the warning was reported against, for a filter whose message_arguments equal
// exactly that captured text -- which is how Eclipse itself stores single-argument filters
// (missing-@since-tag, method-removed, etc.) in this schema.
private val unusedApiFilterMessagePattern =
  Regex("^The API problem filter for: '(.*)' is no longer used$", RegexOption.DOT_MATCHES_ALL)

internal fun apiBaselineFiltersPruneMain(args: Array<String>): Int {
  val parser = ArgParser("pde api-baseline filters prune")
  val reportOpt by parser.option(
    ArgType.String,
    fullName = "report",
    description = "Path to a report JSON from 'pde api-baseline check'; auto-inferred from .api-baseline/reports/ when absent"
  )
  val bundles by parser.option(
    ArgType.String,
    fullName = "bundle",
    description = "Narrow to specific bundle BSNs (repeatable)"
  ).multiple()
  val allowEmptySelectionOpt by parser.option(
    ArgType.Boolean,
    fullName = "allow-empty-selection",
    description = "Exit 0 when no unused filters are found (default: exit 3)"
  ).default(false)
  val dryRunOpt by parser.option(
    ArgType.Boolean,
    fullName = "dry-run",
    description = "Print the filters that would be removed without writing .api_filters"
  ).default(false)
  val reportPos by parser.argument(
    ArgType.String,
    description = "Path to a report JSON; auto-inferred from .api-baseline/reports/ when absent"
  ).optional()

  parser.parse(args)

  val reportPaths = inferReportPaths(reportOpt ?: reportPos)
  if (reportPaths.isEmpty()) {
    apiFiltersLogger.severe("No report found. Pass --report or run 'pde api-baseline check' first.")
    return 2
  }
  val allProblems = run {
    val acc = mutableListOf<ApiAnalyzeProblem>()
    for (path in reportPaths) {
      val problems = runCatching { readApiAnalyzeProblemReport(path).problems }.getOrElse { error ->
        apiFiltersLogger.severe("Failed to parse report ${path}: ${error.message}")
        return 4
      }
      acc += problems
    }
    acc
  }

  var unusedFilterProblems = allProblems.mapNotNull { problem ->
    val message = problem.message ?: return@mapNotNull null
    if (problem.category?.lowercase() != "usage") return@mapNotNull null
    val match = unusedApiFilterMessagePattern.find(message) ?: return@mapNotNull null
    problem to match.groupValues[1]
  }
  if (bundles.isNotEmpty()) {
    val bsnSet = bundles.toSet()
    unusedFilterProblems = unusedFilterProblems.filter { (problem, _) -> bsnSet.contains(problem.bundleBsn) }
  }

  if (unusedFilterProblems.isEmpty()) {
    if (allowEmptySelectionOpt) {
      apiFiltersLogger.info("No unused API problem filters found; nothing to do.")
      return 0
    }
    apiFiltersLogger.severe("No unused API problem filters found in report(s)")
    return 3
  }

  val stores = mutableMapOf<Path, ApiFiltersFile>()
  var removed = 0
  var notFound = 0
  unusedFilterProblems.forEach { (problem, underlyingMessage) ->
    val bsn = problem.bundleBsn
    if (bsn.isNullOrBlank() || problem.resourceType.isNullOrBlank()) {
      apiFiltersLogger.warning("Skipping ${problem.problemRef ?: "<no-ref>"}: missing bundleBsn/resourceType")
      notFound++
      return@forEach
    }
    val bundleDir = resolveBundleDir(problem)
    if (bundleDir == null) {
      apiFiltersLogger.warning("Cannot resolve bundle directory for ${problem.problemRef ?: "<no-ref>"} ($bsn)")
      notFound++
      return@forEach
    }
    val store = stores.getOrPut(bundleDir) { ApiFiltersFile.load(bundleDir, bsn) }
    val removedHere = store.removeMatching(
      type = problem.resourceType,
      path = problem.resourcePath,
      args = listOf(underlyingMessage)
    )
    if (removedHere.isNotEmpty()) {
      removed += removedHere.size
      if (dryRunOpt) {
        removedHere.forEach { println("would remove ${store.file}: ${it.describe()}") }
      }
    } else {
      // The filter is reported as unused by the analyzer but is already absent from
      // .api_filters (e.g. removed by a prior prune or manually). This is benign —
      // there is simply nothing to delete — so log at INFO rather than WARNING.
      apiFiltersLogger.info(
        "Could not locate the stale filter for ${problem.problemRef ?: "<no-ref>"} in $bsn " +
          "(type=${problem.resourceType}, message=\"$underlyingMessage\")"
      )
      notFound++
    }
  }

  if (dryRunOpt) {
    apiFiltersLogger.info("api-baseline filters prune (dry-run, nothing written): removed=$removed notFound=$notFound")
    return 0
  }
  stores.values.forEach { it.write() }
  apiFiltersLogger.info("api-baseline filters prune: removed=$removed notFound=$notFound")
  return 0
}

private fun resolveBundleDir(problem: ApiAnalyzeProblem): Path? {
  val declared = problem.bundleDir?.let { Paths.get(it) }
  if (declared != null && Files.isDirectory(declared)) {
    return declared
  }
  return null
}

private fun readApiAnalyzeProblemReport(reportPath: Path): ApiAnalyzeProblemReport {
  val root = apiFiltersMapper.readTree(reportPath.toFile())
  val schemaVersion = root.get("schemaVersion")?.asInt(1) ?: 1
  return when (schemaVersion) {
    1 -> apiFiltersMapper.readValue(reportPath.toFile(), ApiAnalyzeProblemReport::class.java)
    2 -> ApiAnalysisReportJson.read(reportPath).toCliReport()
    else -> throw IllegalArgumentException("Unsupported report schemaVersion=$schemaVersion; expected 1 or 2")
  }
}

private fun ApiAnalysisReport.toCliReport(): ApiAnalyzeProblemReport = ApiAnalyzeProblemReport(
  schemaVersion = 2,
  generatedAt = generatedAt,
  tool = tool,
  problems = problems.map { problem ->
    ApiAnalyzeProblem(
      problemRef = problem.problemRef,
      bundleBsn = problem.bundleSymbolicName,
      bundleDir = problem.apiFilterFile?.let(::bundleDirFromApiFilterFile),
      resourceType = problem.problemTypeName,
      resourcePath = problem.resourcePath,
      problemId = problem.problemId,
      messageArgs = problem.messageArguments,
      severity = problem.severity,
      category = problem.category,
      message = problem.message
    )
  }
)

private fun bundleDirFromApiFilterFile(apiFilterFile: String): String? {
  val path = Paths.get(apiFilterFile)
  val settingsDir = path.parent ?: return null
  if (path.fileName?.toString() != ".api_filters" || settingsDir.fileName?.toString() != ".settings") return null
  return settingsDir.parent?.toString()
}

internal fun detectBundleBsn(bundleDir: Path): String? {
  val manifestPath = bundleDir.resolve("META-INF").resolve("MANIFEST.MF")
  if (!Files.exists(manifestPath)) return null
  return runCatching {
    Files.newInputStream(manifestPath).use { stream ->
      Manifest(stream).mainAttributes.getValue("Bundle-SymbolicName")
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    }
  }.getOrNull()
}
