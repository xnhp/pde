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
      selectedBundles = selected,
      workspaceDependencies = emptyMap()
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
      selectedBundles = selected,
      workspaceDependencies = emptyMap()
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
      selectedBundles = selected,
      workspaceDependencies = emptyMap()
    )

    val spec = CompileService.buildSpecs(plan, listOf(wsDesc)).specs.first { it.bsn == "org.example.a" }

    assertTrue(spec.classpath.any { it == tpPath.toString() }, "classpath should include target bundle dependency")
  }

  @Test
  fun `workspace specs are ordered by dependencies`() {
    val aPath = Paths.get("/workspace/a")
    val bPath = Paths.get("/workspace/b")
    val aDesc = WorkspaceBundleDescriptor(path = aPath, manifest = dummyManifest("org.example.a", "1.0.0"))
    val bDesc = WorkspaceBundleDescriptor(path = bPath, manifest = dummyManifest("org.example.b", "1.0.0"))
    val selected = listOf(
      ResolvedBundle(
        bsn = "org.example.b",
        version = Version.parseVersion("1.0.0"),
        path = bPath,
        origin = cn.varsa.pde.resolver.algo.BundleOrigin.WORKSPACE
      ),
      ResolvedBundle(
        bsn = "org.example.a",
        version = Version.parseVersion("1.0.0"),
        path = aPath,
        origin = cn.varsa.pde.resolver.algo.BundleOrigin.WORKSPACE
      )
    )
    val plan = LaunchPlanner.PlanResult(
      plan = LauncherPlan(emptyList(), null, emptyMap()),
      context = LaunchContext(emptyMap(), emptyMap()),
      selectedBundles = selected,
      workspaceDependencies = mapOf("org.example.a" to setOf("org.example.b"))
    )

    val specs = CompileService.buildSpecs(plan, listOf(aDesc, bDesc)).specs

    assertEquals(listOf("org.example.b", "org.example.a"), specs.filter { it.isWorkspace }.map { it.bsn })
  }

  @Test
  fun `workspace bundle uses its own per-entry classpath, not the multi-version union`() {
    // Aggregate selection carries BOTH library versions (as the accumulator does when one bundle
    // requires lib 1.0 and another 2.0). org.example.a's own closure only has 1.0, so its compile
    // spec must not see 2.0 -- otherwise ecj could bind a 2.0-only overload it won't have at runtime.
    val aPath = Paths.get("/workspace/org.example.a")
    val lib1 = Paths.get("/tp/lib-1.0.0.jar")
    val lib2 = Paths.get("/tp/lib-2.0.0.jar")
    val aDesc = WorkspaceBundleDescriptor(
      path = aPath,
      manifest = dummyManifest("org.example.a", "1.0.0"),
      classPathEntries = listOf(aPath)
    )
    val selected = listOf(
      ResolvedBundle(
        bsn = "org.example.a", version = Version.parseVersion("1.0.0"), path = aPath,
        origin = cn.varsa.pde.resolver.algo.BundleOrigin.WORKSPACE, classPathEntries = listOf(aPath)
      ),
      ResolvedBundle(
        bsn = "com.lib", version = Version.parseVersion("1.0.0"), path = lib1,
        origin = cn.varsa.pde.resolver.algo.BundleOrigin.TARGET, classPathEntries = listOf(lib1)
      ),
      ResolvedBundle(
        bsn = "com.lib", version = Version.parseVersion("2.0.0"), path = lib2,
        origin = cn.varsa.pde.resolver.algo.BundleOrigin.TARGET, classPathEntries = listOf(lib2)
      )
    )
    val plan = LaunchPlanner.PlanResult(
      plan = LauncherPlan(emptyList(), null, emptyMap()),
      context = LaunchContext(emptyMap(), emptyMap()),
      selectedBundles = selected,
      workspaceDependencies = emptyMap()
    )
    val lib1Cp = lib1.toAbsolutePath().normalize().toString()
    val lib2Cp = lib2.toAbsolutePath().normalize().toString()
    val perEntry = mapOf(
      "org.example.a" to listOf(aPath.toAbsolutePath().normalize().toString(), lib1Cp)
    )

    val spec = CompileService.buildSpecs(plan, listOf(aDesc), perEntry).specs.single { it.bsn == "org.example.a" }
    assertTrue(spec.classpath.contains(lib1Cp), "own version (lib 1.0) on classpath")
    assertTrue(!spec.classpath.contains(lib2Cp), "foreign version (lib 2.0) NOT on classpath")

    // Without a per-entry classpath the union (both versions) leaks in -- the prior behavior.
    val unionSpec = CompileService.buildSpecs(plan, listOf(aDesc)).specs.single { it.bsn == "org.example.a" }
    assertTrue(unionSpec.classpath.contains(lib2Cp), "fallback union contains both versions")
  }
}
