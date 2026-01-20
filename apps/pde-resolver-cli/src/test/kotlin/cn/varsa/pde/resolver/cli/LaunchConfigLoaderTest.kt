package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.test.assertEquals

class LaunchConfigLoaderTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun mergesBundlesPerRepoWithExtraWorkspaceModules() {
    val root: Path = tmp.root.toPath()
    val configFile = root.resolve("config.yaml").toFile()
    configFile.writeText(
      """
      bundlesPerRepo:
        - repo: repo-a
          bundles:
            - bundle-one
      extraWorkspaceModules:
        - extra-module
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)
    val paths = loaded.config.workspaceModules.map { it.path }

    assertEquals(
      listOf(
        root.resolve("repo-a").resolve("bundle-one").normalize().toString(),
        root.resolve("extra-module").normalize().toString()
      ),
      paths
    )
  }
}
