package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
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

  @Test
  fun `target-install uses packaged installer when config omits installer`() {
    val baseDir = tmp.newFolder("cfg-bundled").toPath()
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(
      configFile,
      """
        target:
          definition: example.target
        bundles: []
      """.trimIndent()
    )
    Files.writeString(baseDir.resolve("example.target"), "<target></target>")
    val packagedInstaller = tmp.newFile("target-installer-launcher.jar").toPath()
    var invokedInstaller: Path? = null

    val previous = System.getProperty("pde.targetInstaller")
    try {
      System.setProperty("pde.targetInstaller", packagedInstaller.toString())
      val exit = targetMain(
        arrayOf("--config", configFile.toString()),
        runInstallerLauncher = { installerJar, _, _, _ ->
          invokedInstaller = installerJar
          0
        }
      )
      assertEquals(0, exit)
    } finally {
      if (previous == null) {
        System.clearProperty("pde.targetInstaller")
      } else {
        System.setProperty("pde.targetInstaller", previous)
      }
    }

    assertEquals(packagedInstaller.toAbsolutePath().normalize(), invokedInstaller)
  }

  @Test
  fun `target-install discovers packaged installer beside classpath entry`() {
    val baseDir = tmp.newFolder("cfg-classpath").toPath()
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(
      configFile,
      """
        target:
          definition: example.target
        bundles: []
      """.trimIndent()
    )
    Files.writeString(baseDir.resolve("example.target"), "<target></target>")

    val appLib = tmp.newFolder("app-lib").toPath()
    val appJar = appLib.resolve("pde-cli.jar")
    val packagedInstaller = appLib.resolve("target-installer-launcher.jar")
    Files.writeString(appJar, "stub")
    Files.writeString(packagedInstaller, "stub")
    var invokedInstaller: Path? = null

    val previousInstaller = System.getProperty("pde.targetInstaller")
    val previousClasspath = System.getProperty("java.class.path")
    try {
      System.clearProperty("pde.targetInstaller")
      System.setProperty("java.class.path", appJar.toString())
      val exit = targetMain(
        arrayOf("--config", configFile.toString()),
        runInstallerLauncher = { installerJar, _, _, _ ->
          invokedInstaller = installerJar
          0
        }
      )
      assertEquals(0, exit)
    } finally {
      if (previousInstaller == null) {
        System.clearProperty("pde.targetInstaller")
      } else {
        System.setProperty("pde.targetInstaller", previousInstaller)
      }
      if (previousClasspath == null) {
        System.clearProperty("java.class.path")
      } else {
        System.setProperty("java.class.path", previousClasspath)
      }
    }

    assertEquals(packagedInstaller.toAbsolutePath().normalize(), invokedInstaller)
  }
}
