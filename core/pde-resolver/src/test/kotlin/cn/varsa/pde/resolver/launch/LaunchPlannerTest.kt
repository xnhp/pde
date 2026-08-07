package cn.varsa.pde.resolver.launch

import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.ResolveProblemType
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osgi.framework.Constants.ACTIVATION_LAZY
import org.osgi.framework.Constants.BUNDLE_ACTIVATIONPOLICY
import org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME
import org.osgi.framework.Constants.BUNDLE_VERSION
import org.osgi.framework.Constants.REQUIRE_BUNDLE
import org.osgi.framework.Version
import java.nio.file.Path
import java.nio.file.Paths
import java.util.NavigableMap
import java.util.TreeMap

class LaunchPlannerTest {

  private fun manifest(vararg headers: Pair<String, String>): BundleManifest =
    BundleManifest.parse(mapOf(*headers))

  private fun workspace(bsn: String, version: String, vararg headers: Pair<String, String>): WorkspaceBundleDescriptor =
    WorkspaceBundleDescriptor(
      path = Paths.get("/workspace/$bsn-$version"),
      manifest = manifest(BUNDLE_SYMBOLICNAME to bsn, BUNDLE_VERSION to version, *headers)
    )

  private fun resolved(bsn: String, version: String, vararg headers: Pair<String, String>): ResolvedBundle =
    ResolvedBundle(
      location = Paths.get("/target/$bsn-$version"),
      manifest = manifest(BUNDLE_SYMBOLICNAME to bsn, BUNDLE_VERSION to version, *headers),
      isDirectory = false
    )

  private fun tpIndex(vararg bundles: ResolvedBundle): TargetPlatformIndex {
    val byBsn: MutableMap<String, NavigableMap<Version, ResolvedBundle>> = hashMapOf()
    bundles.forEach { rb ->
      val bsn = rb.manifest.bundleSymbolicName?.key ?: return@forEach
      val version = rb.manifest.bundleVersion
      byBsn.computeIfAbsent(bsn) { TreeMap() }[version] = rb
    }
    return TargetPlatformIndex(byBsn)
  }

  @Test
  fun buildIncludesWorkspaceLibrariesAndDevProperties() {
    val dependency = resolved("dep.bundle", "1.0.0")
    val targetIndex = tpIndex(dependency)
    val workspace = workspace(
      bsn = "app.bundle",
      version = "1.0.0",
      REQUIRE_BUNDLE to "dep.bundle"
    )

    val supplemental = LaunchEnvironment.SupplementalBundle(
      bsn = "extra.bundle",
      version = Version.parseVersion("2.0.0"),
      location = Paths.get("/libs/extra.jar")
    )

    val environment = LaunchEnvironment(
      targetIndex = targetIndex,
      workspaceEntries = listOf(workspace),
      resolverOptions = ResolveOptions(preferWorkspace = true),
      libraryBundles = listOf(supplemental),
      startupLevels = mapOf("app.bundle" to 3, "extra.bundle" to 2),
      autoStartBundles = mapOf("app.bundle" to true, "extra.bundle" to false),
      devProperties = mapOf("app.bundle" to listOf("out/production"))
    )

    val options = LauncherOptions(frameworkBSN = "dep.bundle", defaultStartLevel = 4, autoStartDefault = true)
    val result = LaunchPlanner.build(environment, options)

    val bundles = result.plan.bundles.associateBy { it.bsn }
    assertTrue("workspace bundle present", bundles.containsKey("app.bundle"))
    assertTrue("dependency bundle present", bundles.containsKey("dep.bundle"))
    assertTrue("supplemental bundle present", bundles.containsKey("extra.bundle"))

    assertEquals(3, bundles.getValue("app.bundle").startLevel)
    assertTrue(bundles.getValue("app.bundle").autoStart)
    assertEquals(2, bundles.getValue("extra.bundle").startLevel)
    assertFalse(bundles.getValue("extra.bundle").autoStart)

    assertEquals(listOf("out/production"), result.context.devProperties["app.bundle"])
    assertTrue("no problems expected", result.problemsByScope.isEmpty())
  }

  @Test
  fun requiredStartupBundlesAreAddedAndMissingOnesReported() {
    val targetIndex = tpIndex(resolved("framework", "1.0.0"))
    val environment = LaunchEnvironment(
      targetIndex = targetIndex,
      workspaceEntries = emptyList(),
      requiredStartupBundles = setOf("framework", "missing.bundle"),
      devProperties = emptyMap()
    )

    val result = LaunchPlanner.build(environment, LauncherOptions(frameworkBSN = "framework"))
    val bundleNames = result.plan.bundles.map { it.bsn }
    assertTrue(bundleNames.contains("framework"))

    val launchProblems = result.problemsByScope["Launch Plan"] ?: error("expected launch plan problems")
    assertTrue(launchProblems.any { it.symbol == "missing.bundle" && it.type == ResolveProblemType.MISSING_BUNDLE })
  }

  @Test
  fun lazyActivationBundlesAreArmedEvenWhenAutoStartDefaultIsFalse() {
    // A Bundle-ActivationPolicy: lazy bundle (e.g. org.knime.core) must be started/armed or Equinox
    // never runs its activator + DS components — which is why `pde test` found 0 tests for the
    // org.knime.core.workflow.tests fragment. autoStartDefault=false mirrors pde's real launch.
    val lazyDep = resolved("lazy.dep", "1.0.0", BUNDLE_ACTIVATIONPOLICY to ACTIVATION_LAZY)
    val eagerDep = resolved("eager.dep", "1.0.0")
    val targetIndex = tpIndex(lazyDep, eagerDep)
    val workspace = workspace(
      bsn = "app.bundle",
      version = "1.0.0",
      REQUIRE_BUNDLE to "lazy.dep,eager.dep"
    )
    val environment = LaunchEnvironment(
      targetIndex = targetIndex,
      workspaceEntries = listOf(workspace),
      resolverOptions = ResolveOptions(preferWorkspace = true)
    )
    val options = LauncherOptions(frameworkBSN = "eager.dep", defaultStartLevel = 4, autoStartDefault = false)
    val result = LaunchPlanner.build(environment, options)
    val bundles = result.plan.bundles.associateBy { it.bsn }

    assertTrue("lazy bundle is armed so its activator/DS components run", bundles.getValue("lazy.dep").autoStart)
    assertFalse("non-lazy bundle stays un-started under autoStartDefault=false", bundles.getValue("eager.dep").autoStart)
  }
}
