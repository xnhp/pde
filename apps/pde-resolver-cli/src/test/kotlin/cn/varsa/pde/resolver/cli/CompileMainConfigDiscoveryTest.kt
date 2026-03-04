package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class CompileMainConfigDiscoveryTest {

  @Test
  fun `compile command discovers config yaml in working directory`() {
    val baseDir = java.nio.file.Paths.get("").toAbsolutePath().normalize()
    val workspace = Files.createTempDirectory("ws")
    val p2Root = Files.createTempDirectory("p2")
    createProfileWithFramework(baseDir, p2Root = p2Root)
    createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(
      baseDir,
      workspace,
      "config.yaml",
      p2Path = p2Root.toAbsolutePath().toString()
    )

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    val err = ByteArrayOutputStream()
    val savedErr = System.err
    val savedDir = System.getProperty("user.dir")
    System.setProperty("user.dir", baseDir.toString())
    System.setOut(PrintStream(out))
    System.setErr(PrintStream(err))
    try {
      compileMain(arrayOf("--json"))
    } finally {
      System.setOut(savedOut)
      System.setErr(savedErr)
      System.setProperty("user.dir", savedDir)
      Files.deleteIfExists(configFile)
    }

    val output = out.toString()
    val errorOutput = err.toString()
    val combined = errorOutput + output
    assertTrue(combined.contains("Discovered launch config"))
    assertTrue(output.contains("\"bsn\" : \"org.example.test\""))
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
