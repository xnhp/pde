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

  private fun typeMatches(filterType: String, searchType: String): Boolean =
    filterType == searchType || (filterType + "$") in searchType

  private fun argsMatch(entryArgs: List<String>, searchArgs: List<String>): Boolean =
    entryArgs == searchArgs || entryArgs.all { ea -> searchArgs.any { sa -> ea in sa || sa in ea } }

  fun removeMatching(type: String, path: String?, args: List<String>): Boolean {
    val normalizedType = type.trim()
    val normalizedPath = path?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedArgs = args.map { it.trim() }
    val candidates = entries.filter {
      typeMatches(it.type, normalizedType) &&
        (normalizedPath == null || it.path == normalizedPath)
    }
    val matched = candidates.filter { argsMatch(it.args, normalizedArgs) }
    if (matched.isNotEmpty()) {
      return entries.removeAll { it in matched }
    }
    if (candidates.size == 1) {
      return entries.removeIf { it in candidates }
    }
    return false
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
  val applyOpt by parser.option(
    ArgType.Boolean,
    fullName = "apply",
    description = "Write .settings/.api_filters changes to disk (default: dry-run preview only)"
  ).default(false)
  val dryRunOpt by parser.option(
    ArgType.Boolean,
    fullName = "dry-run",
    description = "Preview .api_filters changes without writing files (default)"
  ).default(false)
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
  if (applyOpt && dryRunOpt) {
    apiFiltersLogger.severe("Use either --apply or --dry-run, not both")
    return 2
  }
  val dryRun = dryRunOpt || !applyOpt

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
  apiFiltersLogger.info("api-baseline filters add-all-from-report ($mode): created=$created updated=$updated skipped=$skipped")
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
    description = "problemRef from the report (e.g. P000001); always writes the filter to disk"
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
  val applyOpt by parser.option(
    ArgType.Boolean,
    fullName = "apply",
    description = "Write .settings/.api_filters changes to disk (default: dry-run preview only)"
  ).default(false)
  val dryRunOpt by parser.option(
    ArgType.Boolean,
    fullName = "dry-run",
    description = "Preview .api_filters changes without writing files (default)"
  ).default(false)
  val allowEmptySelectionOpt by parser.option(
    ArgType.Boolean,
    fullName = "allow-empty-selection",
    description = "Exit 0 when no unused filters are found (default: exit 3)"
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
  if (applyOpt && dryRunOpt) {
    apiFiltersLogger.severe("Use either --apply or --dry-run, not both")
    return 2
  }
  val dryRun = dryRunOpt || !applyOpt

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
    if (removedHere) {
      removed++
    } else {
      // The filter is reported as unused by the analyzer but is already absent from
      // .api_filters (e.g. removed by a prior prune --apply or manually). This is
      // benign — there is simply nothing to delete — so log at INFO rather than
      // WARNING to avoid noise in the dry-run summary.
      apiFiltersLogger.info(
        "Could not locate the stale filter for ${problem.problemRef ?: "<no-ref>"} in $bsn " +
          "(type=${problem.resourceType}, message=\"$underlyingMessage\")"
      )
      notFound++
    }
  }

  if (!dryRun) {
    stores.values.forEach { it.write() }
  }
  val mode = if (dryRun) "dry-run" else "apply"
  apiFiltersLogger.info("api-baseline filters prune ($mode): removed=$removed notFound=$notFound")
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
