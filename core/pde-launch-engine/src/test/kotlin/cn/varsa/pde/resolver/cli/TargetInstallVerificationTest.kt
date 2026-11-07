package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetInstallVerificationTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  private fun writeInstallConfig(): Path {
    val baseDir = tmp.newFolder("cfg").toPath()
    Files.writeString(baseDir.resolve("target-installer.jar"), "stub")
    Files.writeString(baseDir.resolve("example.target"), "<target></target>")
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
    return configFile
  }

  private fun install(configFile: Path, installer: (List<String>) -> Int): Int =
    targetMain(
      arrayOf("--config", configFile.toString()),
      runInstallerLauncher = { _, args, _, _, _ -> installer(args) }
    )

  @Test
  fun `target-install succeeds when the installer leaves installed artifacts`() {
    val exit = install(writeInstallConfig()) { args ->
      writeInstalledProfile(args)
      0
    }

    assertEquals(0, exit)
  }

  @Test
  fun `target-install fails when the installer exits 0 without writing a profile`() {
    val failure = assertFailsWith<IllegalStateException> {
      install(writeInstallConfig()) { 0 }
    }

    assertContains(failure.message.orEmpty(), "installed nothing")
  }

  @Test
  fun `target-install fails when the installer exits 0 with an empty profile`() {
    val failure = assertFailsWith<IllegalStateException> {
      install(writeInstallConfig()) { args ->
        val p2Path = Path.of(args[args.indexOf("-p2Path") + 1])
        p2Path.resolve("org.eclipse.equinox.p2.engine/profileRegistry/profile.Profile")
          .createDirectories()
          .resolve("1.profile")
          .writeText("""<?xml version="1.0" encoding="UTF-8"?><profile id="profile" timestamp="1" version="1.0.0"/>""")
        0
      }
    }

    assertContains(failure.message.orEmpty(), "installed nothing")
  }

  @Test
  fun `target-install reports a failing installer exit code`() {
    val failure = assertFailsWith<IllegalStateException> {
      install(writeInstallConfig()) { 3 }
    }

    assertEquals("Target installer exited with code 3", failure.message)
  }
}
