package cn.varsa.pde.resolver.cli.config

import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.launch.LaunchEnvironment
import cn.varsa.pde.resolver.launch.LaunchPlanner
import cn.varsa.pde.resolver.launch.LauncherOptions
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Paths
import java.util.TreeMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupDefaultsTest {
  @Test
  fun absentTargetSpecificBundlesAreNotMissingRequirements() {
    val levels = defaultStartupLevelsForTarget(emptySet())
    val result = plan(levels, emptyList())
    val missing = result.problemsByScope.values.flatten().map { it.symbol }

    assertFalse("org.eclipse.equinox.ds" in missing)
    assertFalse("org.eclipse.m2e.logback.configuration" in missing)
    assertTrue("org.apache.felix.scr" in missing, "modern DS remains a required startup bundle")
  }

  @Test
  fun targetSpecificBundlesRetainTheirStartupLevelsWhenPresent() {
    val bundles = listOf(bundle("org.eclipse.equinox.ds"), bundle("org.eclipse.m2e.logback.configuration"))
    val levels = defaultStartupLevelsForTarget(bundles.map { it.manifest.bundleSymbolicName!!.key }.toSet())
    val selected = plan(levels, bundles).plan.bundles.associateBy { it.bsn }

    assertEquals(1, selected.getValue("org.eclipse.equinox.ds").startLevel)
    assertTrue(selected.getValue("org.eclipse.equinox.ds").autoStart)
    assertEquals(4, selected.getValue("org.eclipse.m2e.logback.configuration").startLevel)
    assertTrue(selected.getValue("org.eclipse.m2e.logback.configuration").autoStart)
  }

  @Test
  fun explicitlyRequestedLegacyBundleStillReportsAMissingRequirement() {
    val levels = defaultStartupLevelsForTarget(emptySet()) + mapOf("org.eclipse.equinox.ds" to 1)
    val result = plan(levels, emptyList())

    assertTrue(result.problemsByScope.values.flatten().any { it.symbol == "org.eclipse.equinox.ds" })
  }

  private fun bundle(bsn: String) = ResolvedBundle(
    location = Paths.get("/target/$bsn.jar"),
    manifest = BundleManifest.parse(mapOf("Bundle-SymbolicName" to bsn, "Bundle-Version" to "1.0.0")),
    isDirectory = false
  )

  private fun plan(levels: Map<String, Int>, bundles: List<ResolvedBundle>): LaunchPlanner.PlanResult {
    val target = TargetPlatformIndex(bundles.associate { bundle ->
      bundle.manifest.bundleSymbolicName!!.key to TreeMap<Version, ResolvedBundle>().apply {
        put(bundle.manifest.bundleVersion, bundle)
      }
    })
    return LaunchPlanner.build(
      LaunchEnvironment(
        targetIndex = target,
        workspaceEntries = emptyList(),
        requiredStartupBundles = levels.keys,
        startupLevels = levels,
        autoStartBundles = levels.keys.associateWith { true }
      ),
      LauncherOptions(autoStartDefault = false)
    )
  }
}
