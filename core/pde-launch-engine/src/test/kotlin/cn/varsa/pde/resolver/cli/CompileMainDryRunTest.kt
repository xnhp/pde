package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class CompileMainDryRunTest {

  @Test
  fun `compile command resolves workspace before target`() {
    val baseDir = Files.createTempDirectory("cfg")
    val workspace = Files.createTempDirectory("ws")
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(baseDir, workspace)

    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val savedOut = System.out
    val savedErr = System.err
    System.setOut(PrintStream(out))
    System.setErr(PrintStream(err))
    try {
      compileMain(
        arrayOf(
          "--config", configFile.toString(),
          "--json"
        )
      )
    } finally {
      System.setOut(savedOut)
      System.setErr(savedErr)
    }

    val output = out.toString()
    assertTrue(output.contains("\"bsn\" : \"org.example.test\""))
    assertTrue(output.contains("\"origin\" : \"workspace\""))
    assertTrue(output.contains("\"sourceRoots\""))
  }

  private fun createWorkspaceBundle(dir: Path) {
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
    dir.resolve("src").createDirectories()
  }
}
