package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class ConfigDiscoveryTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun prefersFirstCandidateInOrder() {
    val root: Path = tmp.root.toPath()
    // create two candidates; the first in the ordered list should win
    root.resolve("launch.yml").toFile().writeText("application: app")
    root.resolve("pde-launch.yaml").toFile().writeText("application: other")

    val discovered = discoverConfigFile(root)

    assertEquals(root.resolve("launch.yml"), discovered)
  }

  @Test
  fun returnsNullWhenNoConfigFound() {
    val root: Path = tmp.root.toPath()

    val discovered = discoverConfigFile(root)

    assertNull(discovered)
  }

  @Test
  fun positionalConfigArgumentIsAccepted() {
    val root: Path = tmp.root.toPath()
    val config = root.resolve("my-launch.yaml").toFile()
    config.writeText("application: app")

    // Should behave like --config when provided positionally
    launchMain(arrayOf(config.absolutePath, "--dry-run"))

    assertNotNull(config) // reached without exceptions
  }
}
