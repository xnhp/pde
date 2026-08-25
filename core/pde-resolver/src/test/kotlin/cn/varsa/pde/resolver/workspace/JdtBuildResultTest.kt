package cn.varsa.pde.resolver.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JdtBuildResultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val error = JdtBuildDiagnostic(
        severity = JdtBuildDiagnostic.SEVERITY_ERROR,
        project = "org.example.foo",
        path = "/ws/org.example.foo/src/Foo.java",
        line = 12,
        message = "Bar cannot be resolved to a type"
    )
    private val warning = JdtBuildDiagnostic(
        severity = JdtBuildDiagnostic.SEVERITY_WARNING,
        project = "org.example.foo",
        path = "/ws/org.example.foo/src/Foo.java",
        line = 3,
        message = "The import java.util.List is never used"
    )
    private val projectLevelError = JdtBuildDiagnostic(
        severity = JdtBuildDiagnostic.SEVERITY_ERROR,
        project = "org.example.bar",
        path = "/ws/org.example.bar",
        line = null,
        message = "Project 'org.example.bar' is missing required library"
    )

    @Test
    fun `formats diagnostics compiler-style`() {
        assertEquals(
            "/ws/org.example.foo/src/Foo.java:12: error: Bar cannot be resolved to a type",
            JdtBuildReport.formatDiagnostic(error)
        )
        assertEquals(
            "/ws/org.example.bar: error: Project 'org.example.bar' is missing required library",
            JdtBuildReport.formatDiagnostic(projectLevelError)
        )
    }

    @Test
    fun `result counts severities and fails on errors`() {
        val result = JdtBuildResult.from(2, listOf(warning, error, projectLevelError))
        assertFalse(result.success)
        assertEquals(2, result.errorCount)
        assertEquals(1, result.warningCount)
        assertEquals(
            "JDT build FAILED: 2 errors, 1 warning, 2 projects",
            JdtBuildReport.summaryLine(result)
        )
    }

    @Test
    fun `warnings alone succeed`() {
        val result = JdtBuildResult.from(1, listOf(warning))
        assertTrue(result.success)
        assertEquals("JDT build succeeded: 0 errors, 1 warning, 1 project", JdtBuildReport.summaryLine(result))
        assertEquals(listOf(JdtBuildReport.summaryLine(result)), JdtBuildReport.renderStdout(result))
    }

    @Test
    fun `build exception fails even without markers`() {
        val result = JdtBuildResult.from(3, emptyList(), failure = "Errors running builder")
        assertFalse(result.success)
        assertEquals("JDT build FAILED: 0 errors, 0 warnings, 3 projects (Errors running builder)", JdtBuildReport.summaryLine(result))
    }

    @Test
    fun `stdout lists only errors sorted by path and line then summary`() {
        val later = error.copy(line = 40)
        val result = JdtBuildResult.from(2, listOf(later, warning, error, projectLevelError))
        assertEquals(
            listOf(
                JdtBuildReport.formatDiagnostic(projectLevelError),
                JdtBuildReport.formatDiagnostic(error),
                JdtBuildReport.formatDiagnostic(later),
                "JDT build FAILED: 3 errors, 1 warning, 2 projects"
            ),
            JdtBuildReport.renderStdout(result)
        )
    }

    @Test
    fun `result JSON round-trips and creates parent directories`() {
        val result = JdtBuildResult.from(2, listOf(warning, error, projectLevelError))
        val path = tmp.root.toPath().resolve("results/jdt-build.json")
        JdtBuildResultJson.write(result, path)
        assertEquals(result, JdtBuildResultJson.read(path))
        assertNull(JdtBuildResultJson.read(path).failure)
    }

    @Test
    fun `input JSON carries resultPath and tolerates its absence`() {
        val withPath = JdtBuildInput(fullRebuild = true, resultPath = "/out/results/jdt-build.json")
        val file = tmp.newFile("in.json").toPath()
        java.nio.file.Files.writeString(file, JdtBuildInputJson.write(withPath))
        assertEquals(withPath, JdtBuildInputJson.read(file))

        java.nio.file.Files.writeString(file, """{"fullRebuild": false}""")
        assertNull(JdtBuildInputJson.read(file).resultPath)
    }
}
