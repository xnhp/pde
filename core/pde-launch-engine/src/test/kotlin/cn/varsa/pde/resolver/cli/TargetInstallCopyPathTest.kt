package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetInstallCopyPathTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `target-install copies profile path when requested`() {
    val baseDir = tmp.newFolder("cfg").toPath()
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(
      configFile,
      """
        target:
          installer: target-installer.jar
          definition: example.target
        bundles: []
      """.trimIndent()
    )
    val installerJar = baseDir.resolve("target-installer.jar")
    Files.writeString(installerJar, "stub")
    val targetFile = baseDir.resolve("example.target")
    Files.writeString(targetFile, "<target></target>")

    val registryDir = baseDir
      .resolve("target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.profile")
    Files.createDirectories(registryDir)

    val logger = Logger.getLogger("pde-launch-engine")
    val previousLevel = logger.level
    val records = mutableListOf<LogRecord>()
    val handler = object : Handler() {
      override fun publish(record: LogRecord) {
        records += record
      }

      override fun flush() {}

      override fun close() {}
    }
    handler.level = Level.ALL
    logger.addHandler(handler)

    var copiedPath: String? = null
    try {
      val exit = targetMain(
        arrayOf("--config", configFile.toString(), "--copy-path", "--verbose"),
        runInstallerLauncher = { _, _, _, _ -> 0 },
        clipboardCopier = {
          copiedPath = it
          true
        }
      )
      assertEquals(0, exit)
    } finally {
      logger.removeHandler(handler)
      logger.level = previousLevel
    }

    val expected = registryDir.toAbsolutePath().normalize().toString()
    assertEquals(expected, copiedPath)
    assertTrue(
      records.any { it.level == Level.INFO && it.message.contains(expected) },
      "Expected info message with copied path, got: ${records.joinToString { "${it.level}:${it.message}" }}"
    )
  }
}
