package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class CompileMainExitStatusTest {

  @Test
  fun `compile command returns error exit code on failure`() {
    val baseDir = Files.createTempDirectory("cfg")
    val workspace = Files.createTempDirectory("ws")
    createProfileWithFramework(baseDir)
    val srcDir = createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(baseDir, workspace)
    srcDir.resolve("Foo.java").writeText("public class Foo { void broken() { int x = ; } }")

    val exitCode = compileMain(
      arrayOf(
        "--execute",
        "--config", configFile.toString()
      )
    )

    assertEquals(1, exitCode)
  }

  private fun createWorkspaceBundle(dir: Path): Path {
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
