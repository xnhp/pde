package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchCliTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun launchCommandGeneratesOutputs() {
    val target = tmp.newFolder("target").also { createBundle(it, "org.eclipse.osgi", "1.0.0") }
    val workspace = tmp.newFolder("workspace").also {
      createBundle(it, "org.example.app", "1.0.0", require = "org.eclipse.osgi")
    }
    val output = tmp.newFolder("out")

    launchMain(
      arrayOf(
        "--target-root", target.absolutePath,
        "--workspace", workspace.absolutePath,
        "--dev-prop", "org.example.app=${workspace.absolutePath}/classes",
        "--product", "org.example.product",
        "--application", "org.example.app",
        "--output", output.absolutePath
      )
    )

    assertTrue(File(output, "config.ini").exists())
    assertTrue(File(output, "org.eclipse.equinox.simpleconfigurator/bundles.info").exists())
    assertTrue(File(output, "dev.properties").exists())
  }

  private fun createBundle(root: File, bsn: String, version: String, require: String? = null) {
    val metaInf = File(root, "META-INF")
    metaInf.mkdirs()
    val mf = File(metaInf, "MANIFEST.MF")
    val headers = buildString {
      appendLine("Manifest-Version: 1.0")
      appendLine("Bundle-SymbolicName: $bsn")
      appendLine("Bundle-Version: $version")
      if (require != null) appendLine("Require-Bundle: $require")
    }
    mf.writeText(headers)
  }
}
