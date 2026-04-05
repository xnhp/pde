package pde.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CliParserTest {
    @Test
    fun `positional directory uses repo mode`() {
        val tempDir = Files.createTempDirectory("pde-format-test")
        val repoDir = Files.createTempDirectory(tempDir, "repo")

        val result = CliParser(baseArgs("check", repoDir.toString())).parse()

        assertEquals(repoDir, result.options.repoDir)
        assertNull(result.options.inputFile)
    }

    @Test
    fun `positional file uses input mode`() {
        val tempDir = Files.createTempDirectory("pde-format-test")
        val file = Files.createTempFile(tempDir, "Input", ".java")

        val result = CliParser(baseArgs("fix", file.toString())).parse()

        assertEquals(file, result.options.inputFile)
        assertNull(result.options.repoDir)
        assertEquals(true, result.options.inPlace)
    }

    @Test
    fun `range with positional directory fails`() {
        val tempDir = Files.createTempDirectory("pde-format-test")
        val repoDir = Files.createTempDirectory(tempDir, "repo")

        val ex = assertThrows(IllegalStateException::class.java) {
            CliParser(baseArgs("fix", "--range", "0:10", repoDir.toString())).parse()
        }

        assertEquals("--range is only supported for a single file", ex.message)
    }

    @Test
    fun `positional with explicit in fails`() {
        val tempDir = Files.createTempDirectory("pde-format-test")
        val file = Files.createTempFile(tempDir, "Input", ".java")

        val ex = assertThrows(IllegalStateException::class.java) {
            CliParser(baseArgs("check", "--in", file.toString(), file.toString())).parse()
        }

        assertEquals("Positional path is not allowed with --in or --repo", ex.message)
    }

    @Test
    fun `ignore option is collected`() {
        val tempDir = Files.createTempDirectory("pde-format-test")
        val repoDir = Files.createTempDirectory(tempDir, "repo")

        val result = CliParser(
            baseArgs("check", "--ignore", "build/", "--ignore", "out", repoDir.toString())
        ).parse()

        assertEquals(listOf("build/", "out"), result.options.ignore)
    }

    @Test
    fun `stdin paths are accepted`() {
        val tempDir = Files.createTempDirectory("pde-format-test")
        val file = Files.createTempFile(tempDir, "Input", ".java")

        val result = CliParser(
            baseArgs("check"),
            stdinLinesProvider = { listOf(file.toString()) }
        ).parse()

        assertEquals(listOf(file), result.options.stdinPaths)
        assertNull(result.options.inputFile)
        assertNull(result.options.repoDir)
    }

    @Test
    fun `eclipse home defaults to bootstrap runtime when omitted`() {
        val tempDir = Files.createTempDirectory("pde-format-test")
        val file = Files.createTempFile(tempDir, "Input", ".java")
        val profile = Files.createTempFile(tempDir, "profile", ".prefs")
        val runtimeZip = tempDir.resolve("runtime.zip")
        ZipOutputStream(Files.newOutputStream(runtimeZip)).use { zip ->
            zip.putNextEntry(ZipEntry("plugins/"))
            zip.closeEntry()
        }

        val prevZip = System.getProperty("pde.eclipse.runtime.zip")
        val prevSha = System.getProperty("pde.eclipse.runtime.zip.sha256")
        try {
            System.setProperty("pde.eclipse.runtime.zip", runtimeZip.toUri().toString())
            System.clearProperty("pde.eclipse.runtime.zip.sha256")
            val result = CliParser(
                arrayOf("check", "--profile", profile.toString(), "--in", file.toString())
            ).parse()
            assertEquals(true, Files.isDirectory(result.options.eclipseHome.resolve("plugins")))
        } finally {
            if (prevZip == null) System.clearProperty("pde.eclipse.runtime.zip") else System.setProperty("pde.eclipse.runtime.zip", prevZip)
            if (prevSha == null) System.clearProperty("pde.eclipse.runtime.zip.sha256") else System.setProperty("pde.eclipse.runtime.zip.sha256", prevSha)
        }
    }

    private fun baseArgs(vararg args: String): Array<String> {
        return arrayOf(
            args[0],
            "--eclipse-home",
            "/tmp/eclipse",
            "--profile",
            "/tmp/profile",
            *args.drop(1).toTypedArray()
        )
    }
}
