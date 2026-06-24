package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TargetInstallInputsTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `target-install discovers target file and passes defaulted installer args`() {
    val baseDir = tmp.newFolder("defaults").toPath()
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(
      configFile,
      """
        target:
          installer: tools/target-installer-launcher.jar
        bundles: []
      """.trimIndent()
    )
    val installerJar = baseDir.resolve("tools/target-installer-launcher.jar")
    Files.createDirectories(installerJar.parent)
    Files.writeString(installerJar, "stub")
    val targetDefinition = baseDir.resolve("example.target")
    Files.writeString(targetDefinition, "<target></target>")

    var invokedInstaller: Path? = null
    var invokedArgs: List<String>? = null
    var invokedWorkingDir: Path? = null

    val exit = targetMain(
      arrayOf("--config", configFile.toString()),
      runInstallerLauncher = { installer, args, workingDir, _ ->
        invokedInstaller = installer
        invokedArgs = args
        invokedWorkingDir = workingDir
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(installerJar.toAbsolutePath().normalize(), invokedInstaller)
    assertEquals(baseDir.toAbsolutePath().normalize(), invokedWorkingDir)
    assertEquals(
      listOf(
        "-profileId", "profile",
        "-p2Path", baseDir.resolve("target/p2").normalize().toString(),
        "-targetDefinition", targetDefinition.toAbsolutePath().normalize().toString(),
        "-install-folder", baseDir.resolve("target/install").normalize().toString(),
        "-bundlePool", baseDir.resolve("target/bundle-pool").normalize().toString()
      ),
      invokedArgs
    )
  }

  @Test
  fun `target-install uses explicit target file and configured path args`() {
    val baseDir = tmp.newFolder("configured").toPath()
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(
      configFile,
      """
        target:
          installer: installer.jar
          definition: targets/selected.target
          profileId: custom-profile
          p2Path: .p2-state
          install: eclipse-install
          bundlePool: shared-pool
        bundles: []
      """.trimIndent()
    )
    val installerJar = baseDir.resolve("installer.jar")
    Files.writeString(installerJar, "stub")
    val selectedTarget = baseDir.resolve("targets/selected.target")
    Files.createDirectories(selectedTarget.parent)
    Files.writeString(selectedTarget, "<target></target>")
    Files.writeString(baseDir.resolve("ignored.target"), "<target></target>")

    var invokedArgs: List<String>? = null

    val exit = targetMain(
      arrayOf("--config", configFile.toString()),
      runInstallerLauncher = { _, args, _, _ ->
        invokedArgs = args
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(
      listOf(
        "-profileId", "custom-profile",
        "-p2Path", baseDir.resolve(".p2-state").normalize().toString(),
        "-targetDefinition", selectedTarget.normalize().toString(),
        "-install-folder", baseDir.resolve("eclipse-install").normalize().toString(),
        "-bundlePool", baseDir.resolve("shared-pool").normalize().toString()
      ),
      invokedArgs
    )
  }

  @Test
  fun `target-install rejects missing explicit target file before launching installer`() {
    val baseDir = tmp.newFolder("missing-target").toPath()
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(
      configFile,
      """
        target:
          installer: installer.jar
          definition: missing.target
        bundles: []
      """.trimIndent()
    )
    Files.writeString(baseDir.resolve("installer.jar"), "stub")

    var launched = false
    val exit = targetMain(
      arrayOf("--config", configFile.toString()),
      runInstallerLauncher = { _, _, _, _ ->
        launched = true
        0
      }
    )

    assertEquals(2, exit)
    assertFalse(launched)
  }
}
