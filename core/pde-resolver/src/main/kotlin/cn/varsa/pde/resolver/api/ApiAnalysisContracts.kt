package cn.varsa.pde.resolver.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Path

data class DirectApiAnalyzerInput(
  val currentBundle: AnalyzerBundleArtifact,
  val dependencyArtifacts: List<AnalyzerBundleArtifact> = emptyList(),
  val baselineArtifacts: List<AnalyzerBundleArtifact> = emptyList(),
  val apiFilterFile: Path? = null,
  val preferences: Map<String, String> = emptyMap(),
  val outputReportPath: Path
)

data class BatchApiAnalyzerInput(
  val currentBundles: List<CurrentBundleInfo> = emptyList(),
  val dependencyArtifacts: List<AnalyzerBundleArtifact> = emptyList(),
  val baselineArtifacts: List<AnalyzerBundleArtifact> = emptyList(),
  val preferences: Map<String, String> = emptyMap()
)

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
  val synthetic: Boolean = false
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

object DirectApiAnalyzerInputJson {
  private val mapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

  fun write(input: DirectApiAnalyzerInput): String =
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(input)

  fun read(path: Path): DirectApiAnalyzerInput =
    mapper.readValue(path.toFile(), DirectApiAnalyzerInput::class.java)
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
