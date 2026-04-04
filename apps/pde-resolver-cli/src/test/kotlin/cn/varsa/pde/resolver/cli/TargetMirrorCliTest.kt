package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class TargetMirrorCliTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun failsWhenTargetConfigMissing() {
    val config = tmp.newFile("pde.yaml")
    config.writeText("{}")

    val exit = targetMirrorMain(arrayOf("--config", config.absolutePath))

    assertEquals(2, exit)
  }

  @Test
  fun failsWhenTargetDefinitionMissing() {
    val config = tmp.newFile("pde.yaml")
    config.writeText(
      """
        target:
          mirror:
            destination: /tmp/mirror
      """.trimIndent()
    )

    val exit = targetMirrorMain(arrayOf("--config", config.absolutePath))

    assertEquals(2, exit)
  }

  @Test
  fun failsWhenDestinationMissing() {
    val targetFile = tmp.newFile("sample.target")
    targetFile.writeText(
      """
        <target name="example" sequenceNumber="1">
          <locations>
            <location type="InstallableUnit">
              <repository location="https://example.com/updates"/>
            </location>
          </locations>
        </target>
      """.trimIndent()
    )
    val config = tmp.newFile("pde.yaml")
    config.writeText(
      """
        target:
          definition: ${targetFile.absolutePath}
      """.trimIndent()
    )

    val exit = targetMirrorMain(arrayOf("--config", config.absolutePath))

    assertEquals(2, exit)
  }

  @Test
  fun failsWhenMetadataAndArtifactsOnlyBothSet() {
    val config = tmp.newFile("pde.yaml")
    config.writeText(
      """
        target:
          mirror:
            destination: /tmp/mirror
      """.trimIndent()
    )

    val exit = targetMirrorMain(
      arrayOf(
        "--config", config.absolutePath,
        "--metadata-only",
        "--artifacts-only"
      )
    )

    assertEquals(2, exit)
  }

  @Test
  fun mirrorsSyntheticLocalRepository() {
    val sourceRepo = tmp.newFolder("source-repo").toPath()
    Files.createDirectories(sourceRepo)
    Files.writeString(sourceRepo.resolve("content.xml"), "<repository/>")
    Files.writeString(sourceRepo.resolve("artifacts.xml"), "<repository/>")
    Files.createDirectories(sourceRepo.resolve("plugins"))
    Files.writeString(sourceRepo.resolve("plugins").resolve("example.jar"), "fake")

    val targetFile = tmp.newFile("sample.target")
    targetFile.writeText(
      """
        <target name="example" sequenceNumber="1">
          <locations>
            <location type="InstallableUnit">
              <repository location="${sourceRepo.toUri()}"/>
            </location>
          </locations>
        </target>
      """.trimIndent()
    )

    val destination = tmp.newFolder("mirror-out")
    val config = tmp.newFile("pde.yaml")
    config.writeText(
      """
        target:
          definition: ${targetFile.absolutePath}
          mirror:
            destination: ${destination.absolutePath}
      """.trimIndent()
    )

    val invocations = mutableListOf<String>()
    val exit = targetMirrorMain(
      args = arrayOf("--config", config.absolutePath),
      launcherResolver = { _, _, _ -> Path.of("/fake/launcher") },
      mirrorRunner = { _, applicationId, source, dest, _, _ ->
        invocations += applicationId
        copyDirectory(Path.of(source), Path.of(dest))
        0
      }
    )

    assertEquals(0, exit)
    assertTrue(invocations.contains(P2_METADATA_MIRROR_APPLICATION))
    assertTrue(invocations.contains(P2_ARTIFACT_MIRROR_APPLICATION))
    assertTrue(Files.exists(destination.toPath().resolve("content.xml")))
    assertTrue(Files.exists(destination.toPath().resolve("artifacts.xml")))
    assertTrue(Files.exists(destination.toPath().resolve("plugins").resolve("example.jar")))
  }

  private fun copyDirectory(source: Path, destination: Path) {
    Files.walk(source).forEach { current ->
      val relative = source.relativize(current)
      val target = destination.resolve(relative)
      if (Files.isDirectory(current)) {
        Files.createDirectories(target)
      } else {
        target.parent?.let { Files.createDirectories(it) }
        Files.copy(current, target, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }
}
