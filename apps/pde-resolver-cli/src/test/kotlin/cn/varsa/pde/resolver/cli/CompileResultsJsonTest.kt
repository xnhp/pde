package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class CompileResultsJsonTest {

  @Test
  fun `writes results json when executing`() {
    val tpDir = Files.createTempDirectory("tp")
    val workspace = Files.createTempDirectory("ws")
    createFramework(tpDir)
    val srcDir = createWorkspaceBundle(workspace)
    // create minimal java source
    srcDir.resolve("Foo.java").writeText("public class Foo {}")

    val resultsFile = Files.createTempFile("results", ".json").toFile()

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(
        arrayOf(
          "--execute",
          "--target-root", tpDir.toString(),
          "--workspace", workspace.toString(),
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

  private fun createFramework(tpDir: java.nio.file.Path) {
    val plugins = tpDir.resolve("plugins").createDirectories()
    val mf = Manifest().apply {
      mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
      mainAttributes.putValue("Bundle-ManifestVersion", "2")
      mainAttributes.putValue("Bundle-SymbolicName", "org.eclipse.osgi")
      mainAttributes.putValue("Bundle-Version", "1.0.0")
    }
    JarOutputStream(Files.newOutputStream(plugins.resolve("org.eclipse.osgi_1.0.0.jar")), mf).use { /* empty */ }
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
