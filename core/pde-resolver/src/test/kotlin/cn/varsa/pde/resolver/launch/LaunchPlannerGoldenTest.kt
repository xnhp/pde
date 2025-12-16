package cn.varsa.pde.resolver.launch

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

class LaunchPlannerGoldenTest {
  private val fixtureRoot: Path = run {
    val devProps = requireNotNull(javaClass.classLoader.getResource("fixtures/launch-minimal/dev.properties")) {
      "Missing test fixture resource: fixtures/launch-minimal/dev.properties"
    }
    Paths.get(devProps.toURI()).parent.toAbsolutePath().normalize()
  }

  @Test
  fun matchesGoldenArtifacts() {
    val targetRoot = fixtureRoot.resolve("target/plugins")
    val workspaceRoot = fixtureRoot.resolve("workspace")

    val targetIndex = TargetPlatformIndex.build(listOf(targetRoot))
    val workspaceDescriptors = Files.list(workspaceRoot).use { stream ->
      stream.filter { Files.isDirectory(it) }
        .map { dir -> WorkspaceBundleDescriptor(dir, readManifest(dir)) }
        .toList()
    }
    val devProps = loadDevProperties(fixtureRoot.resolve("dev.properties"))

    val environment = LaunchEnvironment(
      targetIndex = targetIndex,
      workspaceEntries = workspaceDescriptors,
      devProperties = devProps
    )
    val options = LauncherOptions(product = "org.example.product", application = "org.example.app")
    val planResult = LaunchPlanner.build(environment, options)

    val normalizedBundles = planResult.plan.bundles
      .sortedBy { it.bsn }
      .joinToString("\n", prefix = "#version=1\n") { spec ->
        val rel = relativize(spec.location)
        listOf(spec.bsn, spec.version.toString(), rel, spec.startLevel.toString(), spec.autoStart.toString()).joinToString(",")
      }
    val expectedBundles = Files.readString(fixtureRoot.resolve("expected/bundles.info")).trim()
    assertEquals(expectedBundles, normalizedBundles)

    val configProps = ConfigIniRenderer.toProperties(planResult.plan, options)
    val normalizedConfig = Properties().apply {
      configProps.forEach { (key, value) ->
        val k = key as String
        val normalized = when (k) {
          "osgi.framework", "osgi.splashPath" -> relativize(Path.of(java.net.URI(value as String)))
          "osgi.framework.extensions" -> (value as String).split(',').joinToString(",") { uri ->
            relativize(Path.of(java.net.URI(uri)))
          }
          else -> value
        }
        put(k, normalized)
      }
    }
    val expectedConfig = loadProperties(fixtureRoot.resolve("expected/config.ini"))
    assertEquals(expectedConfig, normalizedConfig)

    val devPropsOut = DevPropertiesRenderer.toProperties(planResult.context)
    val expectedDevProps = loadProperties(fixtureRoot.resolve("expected/dev.properties"))
    assertEquals(expectedDevProps, devPropsOut)
  }

  private fun readManifest(dir: Path): BundleManifest = Files.newInputStream(dir.resolve("META-INF/MANIFEST.MF")).use {
    BundleManifest.parse(java.util.jar.Manifest(it))
  }

  private fun loadDevProperties(path: Path): Map<String, List<String>> = Files.readAllLines(path)
    .mapNotNull {
      val parts = it.split('=')
      if (parts.size != 2) null else parts[0] to parts[1].split(',').filter { p -> p.isNotBlank() }
    }.toMap()

  private fun relativize(path: Path): String = fixtureRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')

  private fun loadProperties(path: Path): Properties = Properties().apply {
    path.toFile().inputStream().use { load(it) }
  }
}
