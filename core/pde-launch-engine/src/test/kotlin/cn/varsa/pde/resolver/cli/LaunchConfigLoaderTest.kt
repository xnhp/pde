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
  fun launchEntryOverridesProductAndApplication() {
    val root: Path = tmp.root.toPath()
    val configFile = root.resolve("pde.yaml").toFile()
    configFile.writeText(
      """
      launches:
        - name: run
          product: launch.product
          application: launch.app
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)
    val launch = loaded.config.launches.single()

    assertEquals("launch.product", launch.product)
    assertEquals("launch.app", launch.application)
  }

  @Test
  fun mergesIncludesAndLaunchesByName() {
    val root: Path = tmp.root.toPath()
    val baseFile = root.resolve("base.yaml").toFile()
    val configFile = root.resolve("pde.yaml").toFile()

    baseFile.writeText(
      """
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
    val launchNames = loaded.config.launches.map { it.name }
    val apLaunch = loaded.config.launches.first { it.name == "AP" }

    assertEquals(listOf("AP", "GatewayDevServer", "Extra"), launchNames)
    assertEquals(listOf("-Xmx2048m"), apLaunch.vmArgs)
  }

  @Test
  fun loadsLaunchesFromIncludesOnly() {
    val root: Path = tmp.root.toPath()
    val baseFile = root.resolve("launches.yaml").toFile()
    val configFile = root.resolve("pde.yaml").toFile()

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

  @Test
  fun launchEntryLoadsRuntimeDirectoryOverrides() {
    val root: Path = tmp.root.toPath()
    val configFile = root.resolve("pde.yaml").toFile()
    configFile.writeText(
      """
      launches:
        - name: AP
          product: launch.product
          application: launch.app
          dataDir: runtime/data
          configDir: runtime/config
          workDir: runtime/work
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)
    val launch = loaded.config.launches.single()

    assertEquals("runtime/data", launch.dataDir)
    assertEquals("runtime/config", launch.configDir)
    assertEquals("runtime/work", launch.workDir)
  }

  @Test
  fun concatenatesBundlesAcrossIncludes() {
    val root: Path = tmp.root.toPath()
    val baseFile = root.resolve("base.yaml").toFile()
    val extraFile = root.resolve("extra.yaml").toFile()
    val configFile = root.resolve("pde.yaml").toFile()

    baseFile.writeText(
      """
      bundles:
        - path: repo-a/bundle-one
      """.trimIndent()
    )
    extraFile.writeText(
      """
      bundles:
        - path: repo-b/bundle-two
      """.trimIndent()
    )
    configFile.writeText(
      """
      includes:
        - base.yaml
        - extra.yaml
      bundles:
        - path: repo-c/bundle-three
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)
    val bundlePaths = loaded.config.bundles.map { it.path }

    assertEquals(listOf("repo-a/bundle-one", "repo-b/bundle-two", "repo-c/bundle-three"), bundlePaths)
  }

  @Test
  fun targetSupportsRuntimeBootstrapFields() {
    val root: Path = tmp.root.toPath()
    val configFile = root.resolve("pde.yaml").toFile()
    configFile.writeText(
      """
      target:
        definition: sample.target
        apiBaselineRoot: baseline/API-Baseline.target
        eclipseRuntimeCache: .cache/eclipse-runtime
        p2Repositories:
          - https://download.eclipse.org/releases/2024-12
          - https://download.eclipse.org/tools/orbit
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)
    val target = loaded.config.target

    assertEquals("baseline/API-Baseline.target", target?.apiBaselineRoot)
    assertEquals(".cache/eclipse-runtime", target?.eclipseRuntimeCache)
    assertEquals(
      listOf(
        "https://download.eclipse.org/releases/2024-12",
        "https://download.eclipse.org/tools/orbit"
      ),
      target?.p2Repositories
    )
  }

  @Test
  fun targetSupportsExtraBundles() {
    val root: Path = tmp.root.toPath()
    val configFile = root.resolve("pde.yaml").toFile()
    configFile.writeText(
      """
      target:
        extraBundles:
          - org.example.extra
          - org.example.other@1.2.3
      """.trimIndent()
    )

    val loaded = LaunchConfigLoader.load(configFile.toPath(), root)

    assertEquals(listOf("org.example.extra", "org.example.other@1.2.3"), loaded.config.target?.extraBundles)
  }
}
