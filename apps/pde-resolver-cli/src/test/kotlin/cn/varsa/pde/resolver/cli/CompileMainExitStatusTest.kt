package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class CompileMainExitStatusTest {

  @Test
  fun `compile command returns error exit code on failure`() {
    val tpDir = Files.createTempDirectory("tp")
    val workspace = Files.createTempDirectory("ws")
    createFramework(tpDir)
    val srcDir = createWorkspaceBundle(workspace)
    srcDir.resolve("Foo.java").writeText("public class Foo { void broken() { int x = ; } }")

    val exitCode = compileMain(
      arrayOf(
        "--execute",
        "--target-root", tpDir.toString(),
        "--workspace", workspace.toString()
      )
    )

    assertEquals(1, exitCode)
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
