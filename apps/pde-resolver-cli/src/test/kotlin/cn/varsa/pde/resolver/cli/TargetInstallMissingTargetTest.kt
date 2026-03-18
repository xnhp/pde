package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetInstallMissingTargetTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `target-install reports missing target config`() {
    val baseDir = tmp.newFolder("cfg").toPath()
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(configFile, "bundlesPerRepo: []\n")

    val err = ByteArrayOutputStream()
    val savedErr = System.err
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    val logger = Logger.getLogger("pde-resolver-cli")
    val buffer = StringBuilder()
    val handler = object : Handler() {
      override fun publish(record: LogRecord) {
        if (record.level.intValue() >= Level.SEVERE.intValue()) {
          buffer.append(record.message).append('\n')
        }
      }

      override fun flush() {}

      override fun close() {}
    }
    logger.addHandler(handler)
    System.setErr(PrintStream(err))
    System.setOut(PrintStream(out))
    try {
      val exit = targetMain(arrayOf("--config", configFile.toString()))
      assertEquals(2, exit)
    } finally {
      logger.removeHandler(handler)
      System.setErr(savedErr)
      System.setOut(savedOut)
    }

    val output = buffer.toString() + err.toString() + out.toString()
    assertTrue(output.contains("Missing target config"), "Expected missing target config message, got: $output")
    assertTrue(output.contains("docs/config-yaml.md#target"), "Expected docs reference, got: $output")
  }
}
