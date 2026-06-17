package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class CompileResultsJsonTest {

  @Test
  fun `writes results json when executing`() {
    val baseDir = Files.createTempDirectory("cfg")
    val workspace = Files.createTempDirectory("ws")
    createProfileWithFramework(baseDir)
    val srcDir = createWorkspaceBundle(workspace)
    // create minimal java source
    srcDir.resolve("Foo.java").writeText("public class Foo {}")
    val configFile = writeConfigFile(baseDir, workspace)

    val resultsFile = Files.createTempFile("results", ".json").toFile()

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      compileMain(
        arrayOf(
          "--config", configFile.toString(),
          "--results-json", resultsFile.absolutePath
        )
      )
    } finally {
      System.setOut(savedOut)
    }

    assertTrue(resultsFile.exists())
    val content = resultsFile.readText()
    assertTrue(content.contains("\"bsn\""))
    assertTrue(content.contains("\"success\""))
  }

  private fun createWorkspaceBundle(dir: java.nio.file.Path): java.nio.file.Path {
    val meta = dir.resolve("META-INF").createDirectories()
    meta.resolve("MANIFEST.MF").writeText(
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-Name: Test Bundle
        Bundle-SymbolicName: org.example.test
        Bundle-Version: 1.0.0
        Bundle-ClassPath: .
      """.trimIndent()
    )
    val src = dir.resolve("src").createDirectories()
    return src
  }
}
