package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class CompileMainDryRunTest {

  @Test
  fun `compile command resolves workspace before target`() {
    val tpDir = Files.createTempDirectory("tp")
    val workspace = Files.createTempDirectory("ws")
    createFramework(tpDir)
    createWorkspaceBundle(workspace)

    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val savedOut = System.out
    val savedErr = System.err
    System.setOut(PrintStream(out))
    System.setErr(PrintStream(err))
    try {
      main(
        arrayOf(
          "compile",
          "--target-root", tpDir.toString(),
          "--workspace", workspace.toString(),
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

  private fun createFramework(tpDir: Path) {
    val plugins = tpDir.resolve("plugins").createDirectories()
    val mf = Manifest().apply {
      mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
      mainAttributes.putValue("Bundle-ManifestVersion", "2")
      mainAttributes.putValue("Bundle-SymbolicName", "org.eclipse.osgi")
      mainAttributes.putValue("Bundle-Version", "1.0.0")
    }
    JarOutputStream(Files.newOutputStream(plugins.resolve("org.eclipse.osgi_1.0.0.jar")), mf).use { /* empty */ }
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
