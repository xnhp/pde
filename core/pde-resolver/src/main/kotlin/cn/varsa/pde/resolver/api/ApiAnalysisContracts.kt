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
  val applyApiFilters: Boolean = true
)

data class ApiFilterRule(
  val type: String,
  val path: String?,
  val id: Int,
  val args: List<String>
) {
  fun matches(typeName: String?, resourcePath: String?, problemId: Int, messageArguments: List<String>): Boolean =
    typeName != null && type == typeName && id == problemId && args == messageArguments &&
      (path == null || path == resourcePath)
}

fun loadApiFilterRules(apiFilterPath: Path): List<ApiFilterRule> {
  if (!Files.isRegularFile(apiFilterPath)) return emptyList()
  val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
  val doc = Files.newInputStream(apiFilterPath).use { input -> builder.parse(input) }
  val root = doc.documentElement ?: return emptyList()
  if (root.nodeName != "component") return emptyList()

  val rules = mutableListOf<ApiFilterRule>()
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
      rules += ApiFilterRule(type = type, path = path, id = id, args = args)
    }
  }
  return rules
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
