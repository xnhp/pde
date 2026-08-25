package cn.varsa.pde.resolver.workspace

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path

/** One problem marker from the JDT workspace build, decoupled from Eclipse types. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JdtBuildDiagnostic(
    val severity: String,
    val project: String,
    /** Absolute file path when the resource is on disk, otherwise the workspace-relative path. */
    val path: String,
    val line: Int? = null,
    val message: String
) {
    companion object {
        const val SEVERITY_ERROR = "error"
        const val SEVERITY_WARNING = "warning"
        const val SEVERITY_INFO = "info"
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JdtBuildResult(
    val success: Boolean,
    val projects: Int,
    val errorCount: Int,
    val warningCount: Int,
    val diagnostics: List<JdtBuildDiagnostic> = emptyList(),
    /** Set when the build itself threw instead of finishing with markers. */
    val failure: String? = null
) {
    companion object {
        fun from(projects: Int, diagnostics: List<JdtBuildDiagnostic>, failure: String? = null): JdtBuildResult {
            val errors = diagnostics.count { it.severity == JdtBuildDiagnostic.SEVERITY_ERROR }
            val warnings = diagnostics.count { it.severity == JdtBuildDiagnostic.SEVERITY_WARNING }
            return JdtBuildResult(
                success = errors == 0 && failure == null,
                projects = projects,
                errorCount = errors,
                warningCount = warnings,
                diagnostics = diagnostics,
                failure = failure
            )
        }
    }
}

object JdtBuildReport {
    /** `path:line: severity: message`, the format compilers and editors parse. */
    fun formatDiagnostic(d: JdtBuildDiagnostic): String {
        val location = if (d.line != null) "${d.path}:${d.line}" else d.path
        return "$location: ${d.severity}: ${d.message}"
    }

    fun summaryLine(result: JdtBuildResult): String {
        val status = if (result.success) "JDT build succeeded" else "JDT build FAILED"
        val base = "$status: ${result.errorCount} ${plural(result.errorCount, "error")}, " +
            "${result.warningCount} ${plural(result.warningCount, "warning")}, " +
            "${result.projects} ${plural(result.projects, "project")}"
        return if (result.failure != null) "$base (${result.failure})" else base
    }

    /** Errors only (sorted by path, then line), followed by the summary line. */
    fun renderStdout(result: JdtBuildResult): List<String> {
        val errors = result.diagnostics
            .filter { it.severity == JdtBuildDiagnostic.SEVERITY_ERROR }
            .sortedWith(compareBy({ it.path }, { it.line ?: 0 }))
        return errors.map(::formatDiagnostic) + summaryLine(result)
    }

    private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
}

object JdtBuildResultJson {
    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    fun write(result: JdtBuildResult): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)

    fun write(result: JdtBuildResult, path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, write(result))
    }

    fun read(path: Path): JdtBuildResult =
        mapper.readValue(path.toFile(), JdtBuildResult::class.java)
}
