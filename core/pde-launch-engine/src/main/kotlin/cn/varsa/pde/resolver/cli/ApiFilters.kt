package cn.varsa.pde.resolver.cli

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.optional
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.jar.Manifest
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

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
)

private enum class UpsertResult {
  CREATED,
  UPDATED,
  SKIPPED
}

private class ApiFiltersFile(
  val file: Path,
  val componentId: String,
  val entries: MutableList<ApiFilterEntry>
) {
  fun upsert(type: String, path: String?, id: Int, args: List<String>, comment: String?): UpsertResult {
    val normalizedType = type.trim()
    val normalizedPath = path?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedArgs = args.map { it.trim() }
    val normalizedComment = comment?.trim()?.takeIf { it.isNotEmpty() }
    val index = entries.indexOfFirst {
      it.type == normalizedType && it.path == normalizedPath && it.id == id && it.args == normalizedArgs
    }
    if (index < 0) {
      entries += ApiFilterEntry(normalizedType, normalizedPath, id, normalizedArgs, normalizedComment)
      return UpsertResult.CREATED
    }
    val existing = entries[index]
    return if (existing.comment == normalizedComment) {
      UpsertResult.SKIPPED
    } else {
      entries[index] = existing.copy(comment = normalizedComment)
      UpsertResult.UPDATED
    }
  }

  fun write() {
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
    val root = doc.createElement("component")
    root.setAttribute("id", componentId)
    root.setAttribute("version", "2")
    doc.appendChild(root)

    val sortedEntries = entries.sortedWith(
      compareBy<ApiFilterEntry>({ it.path == null }, { it.path ?: "" }, { it.type }, { it.id }, { it.args.joinToString("\u0000") }, { it.comment ?: "" })
    )
    val byResource = linkedMapOf<Pair<String, String?>, MutableList<ApiFilterEntry>>()
    sortedEntries.forEach { entry ->
      byResource.computeIfAbsent(entry.type to entry.path) { mutableListOf() }.add(entry)
    }
    byResource.forEach { (key, resourceEntries) ->
      val resource = doc.createElement("resource")
      resource.setAttribute("type", key.first)
      key.second?.let { resource.setAttribute("path", it) }
      resourceEntries.forEach { entry ->
        val filter = doc.createElement("filter")
        filter.setAttribute("id", entry.id.toString())
        entry.comment?.let { filter.setAttribute("comment", it) }
        val arguments = doc.createElement("message_arguments")
        entry.args.forEach { value ->
          val argument = doc.createElement("message_argument")
          argument.setAttribute("value", value)
          arguments.appendChild(argument)
        }
        filter.appendChild(arguments)
        resource.appendChild(filter)
      }
      root.appendChild(resource)
    }

    file.parent?.let { Files.createDirectories(it) }
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
      setOutputProperty(OutputKeys.ENCODING, "UTF-8")
      setOutputProperty(OutputKeys.INDENT, "yes")
      setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    }
    Files.newBufferedWriter(file).use { writer ->
      transformer.transform(DOMSource(doc), StreamResult(writer))
    }
  }

  companion object {
    fun load(bundleDir: Path, expectedBsn: String): ApiFiltersFile {
      val file = bundleDir.resolve(".settings").resolve(".api_filters")
      if (!Files.exists(file)) {
        return ApiFiltersFile(file = file, componentId = expectedBsn, entries = mutableListOf())
      }
      val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      val doc = Files.newInputStream(file).use { input -> builder.parse(input) }
      val root = doc.documentElement
      if (root == null || root.nodeName != "component") {
        throw IllegalArgumentException("Invalid .api_filters root in ${file.toAbsolutePath().normalize()}")
      }
      val version = root.getAttribute("version")
      if (version != "2") {
        throw IllegalArgumentException("Unsupported .api_filters version '$version' in ${file.toAbsolutePath().normalize()}")
      }
      val componentId = root.getAttribute("id").trim()
      if (componentId.isEmpty()) {
        throw IllegalArgumentException("Missing component id in ${file.toAbsolutePath().normalize()}")
      }
      if (componentId != expectedBsn) {
        throw IllegalArgumentException(
          "Component id '$componentId' does not match bundle BSN '$expectedBsn' in ${file.toAbsolutePath().normalize()}"
        )
      }
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
          val idText = filterNode.getAttribute("id").trim()
          val id = idText.toIntOrNull() ?: continue
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
      return ApiFiltersFile(file = file, componentId = componentId, entries = entries)
    }
  }
}

internal fun extractApiAnalyzeProblemsFromLog(logFile: Path, defaultBundleBsn: String, defaultBundleDir: Path): List<ApiAnalyzeProblem> {
  if (!Files.exists(logFile)) return emptyList()
  val problems = mutableListOf<ApiAnalyzeProblem>()
  Files.newBufferedReader(logFile).useLines { lines ->
    lines.forEach { line ->
      val node = parseProblemNode(line) ?: return@forEach
      val problem = parseProblemNode(node, defaultBundleBsn, defaultBundleDir)
      if (problem != null) {
        problems += problem
      }
    }
  }
  return problems
}

internal fun writeApiAnalyzeProblemReport(reportPath: Path, problems: List<ApiAnalyzeProblem>, generatedAt: Instant = Instant.now()) {
  val normalized = problems.mapIndexed { index, problem ->
    val generatedRef = String.format("P%06d", index + 1)
    val assignedRef = problem.problemRef?.trim().takeIf { !it.isNullOrEmpty() } ?: generatedRef
    problem.copy(problemRef = assignedRef)
  }
  val report = ApiAnalyzeProblemReport(
    schemaVersion = 1,
    generatedAt = generatedAt.toString(),
    tool = "pde api-analyze",
    problems = normalized
  )
  reportPath.parent?.let { Files.createDirectories(it) }
  apiFiltersMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report)
}

private fun parseProblemNode(line: String): JsonNode? {
  val trimmed = line.trim()
  val candidate = when {
    trimmed.startsWith("API_PROBLEM_JSON:") -> trimmed.removePrefix("API_PROBLEM_JSON:").trim()
    trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
    else -> trimmed.substringAfter('{', missingDelimiterValue = "").let { rest ->
      if (rest.isEmpty()) "" else "{$rest"
    }
  }
  if (candidate.isEmpty() || !candidate.contains("problemId") || !candidate.contains("messageArgs")) return null
  return runCatching { apiFiltersMapper.readTree(candidate) }.getOrNull()
}

private fun parseProblemNode(node: JsonNode, defaultBundleBsn: String, defaultBundleDir: Path): ApiAnalyzeProblem? {
  val idNode = node.get("problemId") ?: node.get("id") ?: return null
  val problemId = idNode.asInt(Int.MIN_VALUE)
  if (problemId == Int.MIN_VALUE) return null
  val argsNode = node.get("messageArgs") ?: node.get("arguments") ?: node.get("args")
  val messageArgs = argsNode
    ?.takeIf { it.isArray }
    ?.map { it.asText() }
    ?: return null
  val bundleBsn = node.get("bundleBsn")?.asText()?.trim().takeIf { !it.isNullOrEmpty() } ?: defaultBundleBsn
  val bundleDir = node.get("bundleDir")?.asText()?.trim().takeIf { !it.isNullOrEmpty() } ?: defaultBundleDir.toString()
  val resourceType = listOf("resourceType", "type", "typeName")
    .asSequence()
    .mapNotNull { key -> node.get(key)?.asText()?.trim()?.takeIf { it.isNotEmpty() } }
    .firstOrNull()
  val resourcePath = listOf("resourcePath", "path")
    .asSequence()
    .mapNotNull { key -> node.get(key)?.asText()?.trim()?.takeIf { it.isNotEmpty() } }
    .firstOrNull()
  val severity = node.get("severity")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
  val category = node.get("category")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
  val message = node.get("message")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
  return ApiAnalyzeProblem(
    problemRef = null,
    bundleBsn = bundleBsn,
    bundleDir = bundleDir,
    resourceType = resourceType,
    resourcePath = resourcePath,
    problemId = problemId,
    messageArgs = messageArgs,
    severity = severity,
    category = category,
    message = message
  )
}

internal fun apiFiltersMain(args: Array<String>): Int {
  if (args.isEmpty() || args[0] == "--help" || args[0] == "-h" || args[0] == "help") {
    printApiFiltersHelp()
    return 0
  }
  return when (args[0]) {
    "add-from-report" -> apiFiltersAddFromReportMain(args.drop(1).toTypedArray())
    else -> {
      apiFiltersLogger.severe("Unknown api-filters subcommand '${args[0]}'. Use 'pde api-filters --help'.")
      2
    }
  }
}

private fun printApiFiltersHelp() {
  println("pde api-filters - API filter commands")
  println()
  println("Usage:")
  println("  pde api-filters <subcommand> [options]")
  println()
  println("Subcommands:")
  println("  add-from-report  Add .api_filters entries from api-analyze report JSON")
  println()
  println("Example:")
  println("  pde api-filters add-from-report --report build/api-report.json --problem P000001 --apply")
  println()
  println("See also:")
  println("  pde api-filters add-from-report --help")
  println("  pde api-analyze --report build/api-report.json")
  println("  docs/cli-reference.md")
}

private fun apiFiltersAddFromReportMain(args: Array<String>): Int {
  val parser = ArgParser("pde api-filters add-from-report")
  val reportOpt by parser.option(
    ArgType.String,
    fullName = "report",
    description = "Path to api-analyze report JSON"
  )
  val problemRefs by parser.option(
    ArgType.String,
    fullName = "problem",
    description = "Problem reference from report (repeatable)"
  ).multiple()
  val allOpt by parser.option(
    ArgType.Boolean,
    fullName = "all",
    description = "Select all problems in report"
  ).default(false)
  val bundles by parser.option(
    ArgType.String,
    fullName = "bundle",
    description = "Bundle BSN selector (repeatable)"
  ).multiple()
  val categories by parser.option(
    ArgType.String,
    fullName = "category",
    description = "Category selector (repeatable)"
  ).multiple()
  val severities by parser.option(
    ArgType.String,
    fullName = "severity",
    description = "Severity selector (repeatable)"
  ).multiple()
  val commentTemplateOpt by parser.option(
    ArgType.String,
    fullName = "comment-template",
    description = "Comment template with {problemRef},{bundleBsn},{timestamp}"
  )
  val applyOpt by parser.option(
    ArgType.Boolean,
    fullName = "apply",
    description = "Persist changes"
  ).default(false)
  val dryRunOpt by parser.option(
    ArgType.Boolean,
    fullName = "dry-run",
    description = "Preview changes without writing files"
  ).default(false)
  val allowEmptySelectionOpt by parser.option(
    ArgType.Boolean,
    fullName = "allow-empty-selection",
    description = "Treat empty selection as success"
  ).default(false)
  val reportPos by parser.argument(
    ArgType.String,
    description = "Path to api-analyze report JSON"
  ).optional()

  parser.parse(args)

  val reportPath = reportOpt ?: reportPos
  if (reportPath.isNullOrBlank()) {
    apiFiltersLogger.severe("Missing --report")
    return 2
  }
  if (!allOpt && problemRefs.isEmpty()) {
    apiFiltersLogger.severe("Specify --problem <ref> (repeatable) or --all")
    return 2
  }
  if (applyOpt && dryRunOpt) {
    apiFiltersLogger.severe("Use either --apply or --dry-run, not both")
    return 2
  }
  val dryRun = dryRunOpt || !applyOpt

  val report = runCatching {
    apiFiltersMapper.readValue(Paths.get(reportPath).toFile(), ApiAnalyzeProblemReport::class.java)
  }.getOrElse { error ->
    apiFiltersLogger.severe("Failed to parse report: ${error.message}")
    return 4
  }
  if (report.schemaVersion != 1) {
    apiFiltersLogger.severe("Unsupported report schemaVersion=${report.schemaVersion}; expected 1")
    return 4
  }

  val selectedRefs = problemRefs.toSet()
  var selected = if (allOpt) {
    report.problems
  } else {
    report.problems.filter { selectedRefs.contains(it.problemRef) }
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
    val refs = invalid.map { it.problemRef ?: "<missing>" }
    apiFiltersLogger.severe("Selected problems missing required fields: ${refs.joinToString(", ")}")
    return 5
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
    when (
      store.upsert(
        type = problem.resourceType!!,
        path = problem.resourcePath,
        id = problem.problemId!!,
        args = problem.messageArgs!!,
        comment = comment
      )
    ) {
      UpsertResult.CREATED -> created++
      UpsertResult.UPDATED -> updated++
      UpsertResult.SKIPPED -> skipped++
    }
  }

  if (!dryRun) {
    stores.values.forEach { it.write() }
  }
  val mode = if (dryRun) "dry-run" else "apply"
  apiFiltersLogger.info("api-filters add-from-report ($mode): created=$created updated=$updated skipped=$skipped")
  return 0
}

private fun resolveBundleDir(problem: ApiAnalyzeProblem): Path? {
  val declared = problem.bundleDir?.let { Paths.get(it) }
  if (declared != null && Files.isDirectory(declared)) {
    return declared
  }
  return null
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
