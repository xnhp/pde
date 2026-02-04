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
        - path: extra-module-2
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)
    val paths = loaded.config.workspaceModules.map { it.path }

    assertEquals(
      listOf(
        root.resolve("repo-a").resolve("bundle-one").normalize().toString(),
        root.resolve("extra-module").normalize().toString(),
        root.resolve("extra-module-2").normalize().toString()
      ),
      paths
    )
  }

  @Test
  fun launchEntryOverridesProductAndApplication() {
    val root: Path = tmp.root.toPath()
    val configFile = root.resolve("config.yaml").toFile()
    configFile.writeText(
      """
      product: base.product
      application: base.app
      launches:
        - name: run
          product: launch.product
          application: launch.app
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)
    val launch = loaded.config.launches.single()
    val patched = loaded.config.copy(
      product = launch.product ?: loaded.config.product,
      application = launch.application ?: loaded.config.application
    )

    assertEquals("launch.product", patched.product)
    assertEquals("launch.app", patched.application)
  }

  @Test
  fun mergesIncludesAndLaunchesByName() {
    val root: Path = tmp.root.toPath()
    val baseFile = root.resolve("base.yaml").toFile()
    val configFile = root.resolve("config.yaml").toFile()

    baseFile.writeText(
      """
      env:
        BASE: "1"
      launches:
        - name: AP
          vmArgs:
            - -Xmx1024m
        - name: GatewayDevServer
          programArgs:
            - -port=7000
      """.trimIndent()
    )

    configFile.writeText(
      """
      includes:
        - base.yaml
      env:
        OVERRIDE: "2"
      launches:
        - name: AP
          vmArgs:
            - -Xmx2048m
        - name: Extra
          vmArgs:
            - -Xmx512m
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)
    val env = loaded.config.env
    val launchNames = loaded.config.launches.map { it.name }
    val apLaunch = loaded.config.launches.first { it.name == "AP" }

    assertEquals(mapOf("BASE" to "1", "OVERRIDE" to "2"), env)
    assertEquals(listOf("AP", "GatewayDevServer", "Extra"), launchNames)
    assertEquals(listOf("-Xmx2048m"), apLaunch.vmArgs)
  }

  @Test
  fun loadsLaunchesFromIncludesOnly() {
    val root: Path = tmp.root.toPath()
    val baseFile = root.resolve("launches.yaml").toFile()
    val configFile = root.resolve("config.yaml").toFile()

    baseFile.writeText(
      """
      launches:
        - name: GatewayDevServer
          programArgs:
            - -port=7000
        - name: AP
          vmArgs:
            - -Xmx2048m
      """.trimIndent()
    )

    configFile.writeText(
      """
      includes:
        - launches.yaml
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)
    val launchNames = loaded.config.launches.map { it.name }

    assertEquals(listOf("GatewayDevServer", "AP"), launchNames)
  }
}
