package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetHealthTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `target-health reports cached feature pins with missing physical bundles`() {
    val baseDir = tmp.newFolder("missing-pin").toPath()
    val bundlePool = baseDir.resolve("bundle-pool")
    Files.createDirectories(bundlePool.resolve("plugins"))
    Files.createDirectories(bundlePool.resolve("features/example.feature_1.0.0"))
    Files.writeString(
      baseDir.resolve("pde.yaml"),
      """
        target:
          bundlePool: bundle-pool
        bundles: []
      """.trimIndent()
    )
    Files.writeString(
      bundlePool.resolve("artifacts.xml"),
      """
        <?xml version='1.0' encoding='UTF-8'?>
        <repository>
          <artifacts size='0'/>
        </repository>
      """.trimIndent()
    )
    Files.writeString(
      bundlePool.resolve("features/example.feature_1.0.0/feature.xml"),
      """
        <feature id="example.feature" version="1.0.0">
          <plugin id="org.missing" version="1.2.3"/>
        </feature>
      """.trimIndent()
    )

    val output = captureStdout {
      assertEquals(1, targetHealthMain(arrayOf("--config", baseDir.resolve("pde.yaml").toString())))
    }

    assertTrue(output.contains("Missing cached feature plugin pins: 1"))
    assertTrue(output.contains("org.missing@1.2.3"))
    assertTrue(output.contains("Healthy: false"))
  }

  @Test
  fun `target-repair rebuild-index writes physical artifact index`() {
    val baseDir = tmp.newFolder("rebuild-index").toPath()
    val bundlePool = baseDir.resolve("bundle-pool")
    Files.createDirectories(bundlePool.resolve("plugins/org.example_1.2.3/META-INF"))
    Files.createDirectories(bundlePool.resolve("features/example.feature_1.0.0"))
    Files.writeString(
      bundlePool.resolve("plugins/org.example_1.2.3/META-INF/MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-SymbolicName: org.example
        Bundle-Version: 1.2.3
      """.trimIndent().replace("\n", "\r\n") + "\r\n\r\n"
    )
    Files.writeString(
      bundlePool.resolve("features/example.feature_1.0.0/feature.xml"),
      """
        <feature id="example.feature" version="1.0.0"/>
      """.trimIndent()
    )
    Files.writeString(bundlePool.resolve("artifacts.xml"), "<repository><artifacts size='0'/></repository>")
    Files.writeString(
      baseDir.resolve("pde.yaml"),
      """
        target:
          bundlePool: bundle-pool
        bundles: []
      """.trimIndent()
    )

    assertEquals(0, targetRepairRebuildIndexMain(arrayOf("--config", baseDir.resolve("pde.yaml").toString())))

    val artifactsXml = Files.readString(bundlePool.resolve("artifacts.xml"))
    assertTrue(artifactsXml.contains("id='org.example'"))
    assertTrue(artifactsXml.contains("version='1.2.3'"))
    assertTrue(artifactsXml.contains("artifact.folder"))
  }

  @Test
  fun `target-repair rebuild-index reads bundle identity from manifest when filename contains underscores`() {
    val baseDir = tmp.newFolder("rebuild-underscore-index").toPath()
    val bundlePool = baseDir.resolve("bundle-pool")
    Files.createDirectories(bundlePool.resolve("plugins/org.bytedeco.lz4.linux-x86_64_1.9.4.1_5_8v20221026-knime/META-INF"))
    Files.writeString(bundlePool.resolve("artifacts.xml"), "<repository><artifacts size='0'/></repository>")
    Files.writeString(
      bundlePool.resolve("plugins/org.bytedeco.lz4.linux-x86_64_1.9.4.1_5_8v20221026-knime/META-INF/MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-SymbolicName: org.bytedeco.lz4.linux-x86_64
        Bundle-Version: 1.9.4.1_5_8v20221026-knime
      """.trimIndent().replace("\n", "\r\n") + "\r\n\r\n"
    )
    Files.writeString(
      baseDir.resolve("pde.yaml"),
      """
        target:
          bundlePool: bundle-pool
        bundles: []
      """.trimIndent()
    )

    assertEquals(0, targetRepairRebuildIndexMain(arrayOf("--config", baseDir.resolve("pde.yaml").toString())))

    val artifactsXml = Files.readString(bundlePool.resolve("artifacts.xml"))
    assertTrue(artifactsXml.contains("id='org.bytedeco.lz4.linux-x86_64'"))
    assertTrue(artifactsXml.contains("version='1.9.4.1_5_8v20221026-knime'"))
    assertTrue(!artifactsXml.contains("version='0.0.0'"))
  }

  private fun captureStdout(block: () -> Unit): String {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      block()
    } finally {
      System.setOut(savedOut)
    }
    return out.toString()
  }
}
