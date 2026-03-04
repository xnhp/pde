package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TargetMirrorCliTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun failsWhenTargetConfigMissing() {
    val config = tmp.newFile("config.yaml")
    config.writeText("product: example")

    val exit = targetMirrorMain(arrayOf("--config", config.absolutePath))

    assertEquals(2, exit)
  }

  @Test
  fun failsWhenTargetDefinitionMissing() {
    val config = tmp.newFile("config.yaml")
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
    val config = tmp.newFile("config.yaml")
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
    val config = tmp.newFile("config.yaml")
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
}
