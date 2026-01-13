package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.algo.ResolvedBundle
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.LaunchContext
import cn.varsa.pde.resolver.launch.LaunchPlanner
import cn.varsa.pde.resolver.launch.LauncherPlan
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompileServiceTest {

  @Test
  fun `maps workspace bundle metadata into compile spec`() {
    val wsPath = Paths.get("/workspace/org.example.test")
    val wsDesc = WorkspaceBundleDescriptor(
      path = wsPath,
      manifest = dummyManifest("org.example.test", "1.0.0"),
      classPathEntries = listOf(wsPath),
      sourceRoots = listOf(wsPath.resolve("src")),
      resourceIncludes = listOf("META-INF/", ".", "icons/"),
      resourceExcludes = listOf("**/*.bak"),
      compilerPrefs = mapOf("org.eclipse.jdt.core.compiler.source" to "17"),
      executionEnvironment = "JavaSE-17",
      outputDirectory = wsPath.resolve("bin")
    )
    val selected = listOf(
      ResolvedBundle(
        bsn = "org.example.test",
        version = Version.parseVersion("1.0.0"),
        path = wsPath,
        origin = cn.varsa.pde.resolver.algo.BundleOrigin.WORKSPACE,
        classPathEntries = listOf(wsPath),
        sourceEntries = emptyList()
      )
    )
    val plan = LaunchPlanner.PlanResult(
      plan = LauncherPlan(emptyList(), null, emptyMap()),
      context = LaunchContext(emptyMap(), emptyMap()),
      selectedBundles = selected
    )

    val output = CompileService.buildSpecs(plan, listOf(wsDesc))
    val spec = output.specs.single()

    assertEquals("org.example.test", spec.bsn)
    assertEquals("workspace", spec.origin)
    assertEquals(wsPath.toString(), spec.bundlePath)
    assertTrue(spec.classpath.contains(wsPath.toString()))
    assertTrue(spec.sourceRoots.contains(wsPath.resolve("src").toString()))
    assertEquals(listOf("META-INF/", ".", "icons/"), spec.resourceIncludes)
    assertEquals(listOf("**/*.bak"), spec.resourceExcludes)
    assertEquals("17", spec.compilerPrefs["org.eclipse.jdt.core.compiler.source"])
    assertEquals("JavaSE-17", spec.executionEnvironment)
    assertEquals(wsPath.resolve("bin").toString(), spec.outputDirectory)
  }

  @Test
  fun `target bundle gets empty workspace-only metadata`() {
    val tpPath = Paths.get("/target/org.foo")
    val selected = listOf(
      ResolvedBundle(
        bsn = "org.foo",
        version = Version.parseVersion("2.0.0"),
        path = tpPath,
        origin = cn.varsa.pde.resolver.algo.BundleOrigin.TARGET,
        classPathEntries = listOf(tpPath),
        sourceEntries = emptyList()
      )
    )
    val plan = LaunchPlanner.PlanResult(
      plan = LauncherPlan(emptyList(), null, emptyMap()),
      context = LaunchContext(emptyMap(), emptyMap()),
      selectedBundles = selected
    )
    val spec = CompileService.buildSpecs(plan, emptyList()).specs.single()

    assertEquals("target", spec.origin)
    assertEquals(tpPath.toString(), spec.bundlePath)
    assertTrue(spec.sourceRoots.isEmpty())
    assertTrue(spec.resourceIncludes.isEmpty())
    assertTrue(spec.resourceExcludes.isEmpty())
    assertTrue(spec.compilerPrefs.isEmpty())
    assertEquals(null, spec.executionEnvironment)
    assertEquals(null, spec.outputDirectory)
  }

  private fun dummyManifest(bsn: String, ver: String) =
    cn.varsa.pde.resolver.manifest.BundleManifest.parse(
      mapOf(
        org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME to bsn,
        org.osgi.framework.Constants.BUNDLE_VERSION to ver
      )
    )

  @Test
  fun `classpath includes resolved dependencies`() {
    val wsPath = Paths.get("/workspace/org.example.a")
    val tpPath = Paths.get("/target/org.foo")
    val wsDesc = WorkspaceBundleDescriptor(
      path = wsPath,
      manifest = dummyManifest("org.example.a", "1.0.0"),
      classPathEntries = listOf(wsPath)
    )
    val selected = listOf(
      ResolvedBundle(
        bsn = "org.example.a",
        version = Version.parseVersion("1.0.0"),
        path = wsPath,
        origin = cn.varsa.pde.resolver.algo.BundleOrigin.WORKSPACE,
        classPathEntries = listOf(wsPath),
        sourceEntries = emptyList()
      ),
      ResolvedBundle(
        bsn = "org.foo",
        version = Version.parseVersion("2.0.0"),
        path = tpPath,
        origin = cn.varsa.pde.resolver.algo.BundleOrigin.TARGET,
        classPathEntries = listOf(tpPath),
        sourceEntries = emptyList()
      )
    )
    val plan = LaunchPlanner.PlanResult(
      plan = LauncherPlan(emptyList(), null, emptyMap()),
      context = LaunchContext(emptyMap(), emptyMap()),
      selectedBundles = selected
    )

    val spec = CompileService.buildSpecs(plan, listOf(wsDesc)).specs.first { it.bsn == "org.example.a" }

    assertTrue(spec.classpath.any { it == tpPath.toString() }, "classpath should include target bundle dependency")
  }
}
