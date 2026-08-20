package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end shape of the KNIME report: one bundle ships a processor-declaring jar on its own
 * Bundle-ClassPath, another unrelated bundle in the same plan does not. `pde compile` must build
 * both and warn only about the bundle that actually carries the processor.
 */
class CompileMainProcessorWarningTest {

  @Test
  fun `warns only for the bundle whose own classpath declares processors`() {
    val baseDir = Files.createTempDirectory("cfg")
    val workspace = Files.createTempDirectory("ws")
    createProfileWithFramework(baseDir)

    val provider = workspace.resolve("org.example.provider")
    createBundle(provider, "org.example.provider", bundleClassPath = ".,libs/auto-value-1.11.0.jar")
    processorJar(provider.resolve("libs").createDirectories().resolve("auto-value-1.11.0.jar"))

    val consumer = workspace.resolve("org.example.consumer")
    createBundle(consumer, "org.example.consumer", bundleClassPath = ".")

    val configFile = baseDir.resolve("pde.yaml")
    configFile.writeText(
      """
        target:
          profileId: profile
          p2Path: target/p2
        bundles:
          - path: ${provider.toAbsolutePath()}
          - path: ${consumer.toAbsolutePath()}
      """.trimIndent()
    )

    val stderr = ByteArrayOutputStream()
    val originalErr = System.err
    val exitCode = try {
      System.setErr(PrintStream(stderr, true))
      compileMain(arrayOf("--config", configFile.toString()))
    } finally {
      System.setErr(originalErr)
    }
    val logged = stderr.toString()

    assertEquals(0, exitCode, "Both bundles should compile:\n$logged")
    assertTrue(
      provider.resolve("bin/Dummy.class").toFile().exists() &&
        consumer.resolve("bin/Dummy.class").toFile().exists(),
      "Both bundles should have emitted class files:\n$logged"
    )
    assertTrue(logged.contains("org.example.provider:"), "Provider should be warned about:\n$logged")
    assertTrue(logged.contains("-proc:none"), "Warning text should reach stderr:\n$logged")
    assertTrue(logged.contains("auto-value-1.11.0.jar"), "Warning should name the jar:\n$logged")
    assertFalse(logged.contains("org.example.consumer:"), "Consumer must not be warned about:\n$logged")
  }

  private fun createBundle(dir: Path, bsn: String, bundleClassPath: String) {
    dir.resolve("META-INF").createDirectories().resolve("MANIFEST.MF").writeText(
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: $bsn
        Bundle-Version: 1.0.0
        Bundle-ClassPath: $bundleClassPath
      """.trimIndent() + "\n"
    )
    dir.resolve("src").createDirectories().resolve("Dummy.java").writeText("class Dummy {}")
  }

  private fun processorJar(target: Path) {
    JarOutputStream(target.outputStream()).use { jar ->
      jar.putNextEntry(JarEntry("META-INF/services/javax.annotation.processing.Processor"))
      jar.write("com.example.Processor".toByteArray())
      jar.closeEntry()
    }
  }
}
