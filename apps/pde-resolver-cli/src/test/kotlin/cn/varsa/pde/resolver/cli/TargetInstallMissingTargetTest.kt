package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetInstallMissingTargetTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `target-install reports missing target config`() {
    val baseDir = tmp.newFolder("cfg").toPath()
    val configFile = baseDir.resolve("config.yaml")
    Files.writeString(configFile, "bundlesPerRepo: []\n")

    val err = ByteArrayOutputStream()
    val savedErr = System.err
    System.setErr(PrintStream(err))
    try {
      val exit = targetMain(arrayOf("--config", configFile.toString()))
      assertEquals(2, exit)
    } finally {
      System.setErr(savedErr)
    }

    val output = err.toString()
    assertTrue(output.contains("Missing target config"), "Expected missing target config message, got: $output")
    assertTrue(output.contains("docs/config-yaml.md#target"), "Expected docs reference, got: $output")
  }
}
