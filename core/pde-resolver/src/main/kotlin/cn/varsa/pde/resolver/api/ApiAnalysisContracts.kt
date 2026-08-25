package cn.varsa.pde.resolver.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

data class BatchApiAnalyzerInput(
  val currentBundles: List<CurrentBundleInfo> = emptyList(),
  val dependencyArtifacts: List<AnalyzerBundleArtifact> = emptyList(),
  val baselineArtifacts: List<AnalyzerBundleArtifact> = emptyList(),
  val preferences: Map<String, String> = emptyMap(),
  val workspaceDataDir: String? = null,
  val applyApiFilters: Boolean = true,
  /** Run the result-shape heuristic ([ApiAnalysisSanity.degradedResultMessage]) after each bundle. */
  val sanityCheck: Boolean = true,
  /** When set, the harness writes an [ApiAnalysisFailureSummary] here for every failed bundle. */
  val failureSummaryPath: String? = null
)

/** One failed bundle analysis; written by the analyzer JVM so the CLI can print the reason. */
data class ApiAnalysisFailure(
  val bundleSymbolicName: String,
  val message: String
)

data class ApiAnalysisFailureSummary(
  val failures: List<ApiAnalysisFailure> = emptyList()
)

object ApiAnalysisFailureSummaryJson {
  private val mapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

  fun write(summary: ApiAnalysisFailureSummary): String =
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary)

  fun read(path: Path): ApiAnalysisFailureSummary =
    mapper.readValue(path.toFile(), ApiAnalysisFailureSummary::class.java)
}

data class ApiFilterRule(
  val typeName: String,
  val path: String?,
  val problemId: Int,
  val messageArguments: List<String>
) {
  fun matches(
    problemId: Int,
    typeName: String?,
    resourcePath: String?,
    messageArguments: List<String>
  ): Boolean {
    if (this.path != null && resourcePath != null && this.path != resourcePath) return false
    if (this.typeName != typeName || this.problemId != problemId) return false
    if (this.messageArguments.size != messageArguments.size) return false
    return this.messageArguments.zip(messageArguments).all { (filterArg, problemArg) ->
      argumentsEqual(filterArg, problemArg)
    }
  }

  companion object {
    fun argumentsEqual(filterArg: String, problemArg: String): Boolean {
      if (filterArg == problemArg) return true
      if (!filterArg.contains('.')) {
        return filterArg == problemArg.substringAfterLast('.')
      }
      return false
    }

    fun load(path: Path): List<ApiFilterRule> {
      if (!Files.exists(path)) return emptyList()
      val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      val doc = Files.newInputStream(path).use { input -> builder.parse(input) }
      val root = doc.documentElement ?: return emptyList()
      val rules = mutableListOf<ApiFilterRule>()
      val resources = root.childNodes
      for (i in 0 until resources.length) {
        val resourceNode = resources.item(i)
        if (resourceNode !is Element || resourceNode.nodeName != "resource") continue
        val type = resourceNode.getAttribute("type").trim().takeIf { it.isNotEmpty() } ?: continue
        val resPath = resourceNode.getAttribute("path").trim().takeIf { it.isNotEmpty() }
        val filters = resourceNode.childNodes
        for (j in 0 until filters.length) {
          val filterNode = filters.item(j)
          if (filterNode !is Element || filterNode.nodeName != "filter") continue
          val id = filterNode.getAttribute("id").trim().toIntOrNull() ?: continue
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
          rules += ApiFilterRule(typeName = type, path = resPath, problemId = id, messageArguments = args)
        }
      }
      return rules
    }
  }
}

data class CurrentBundleInfo(
  val currentBundle: AnalyzerBundleArtifact,
  val outputReportPath: Path,
  val apiFilterPath: Path? = null
)

data class AnalyzerBundleArtifact(
  val bundleSymbolicName: String,
  val version: String? = null,
  val path: Path,
  val sourcePath: Path? = null,
  val synthetic: Boolean = false,
  val workspaceProjectName: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiAnalysisReport(
  val schemaVersion: Int = 2,
  val generatedAt: String? = null,
  val tool: String? = null,
  val problems: List<ApiAnalysisProblem> = emptyList()
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiAnalysisProblem(
  val problemRef: String? = null,
  val problemId: Int,
  val messageArguments: List<String> = emptyList(),
  val problemTypeName: String? = null,
  val resourcePath: String? = null,
  val severity: String? = null,
  val line: Int? = null,
  val charStart: Int? = null,
  val charEnd: Int? = null,
  val sourceFile: String? = null,
  val bundleSymbolicName: String? = null,
  val baselineComponentId: String? = null,
  val currentComponentId: String? = null,
  val message: String? = null,
  val category: String? = null,
  val apiFilterFile: String? = null
)

object ApiAnalysisReportJson {
  private val mapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

  fun write(report: ApiAnalysisReport): String =
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)

  fun read(path: Path): ApiAnalysisReport =
    mapper.readValue(path.toFile(), ApiAnalysisReport::class.java)
}

object BatchApiAnalyzerInputJson {
  private val mapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

  fun write(input: BatchApiAnalyzerInput): String =
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(input)

  fun read(path: Path): BatchApiAnalyzerInput =
    mapper.readValue(path.toFile(), BatchApiAnalyzerInput::class.java)
}
