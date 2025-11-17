package cn.varsa.pde.resolver.launch

import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.ResolveResult
import cn.varsa.pde.resolver.algo.ResolvedBundle
import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Version
import org.osgi.framework.VersionRange
import java.nio.file.Paths

class LauncherAssemblerTest {
  @Test
  fun assemblesPlanWithStartLevelsAndFramework() {
    val rb1 = ResolvedBundle("org.eclipse.osgi", Version.parseVersion("3.18.0"), Paths.get("/tp/osgi.jar"), cn.varsa.pde.resolver.algo.BundleOrigin.TARGET)
    val rb2 = ResolvedBundle("com.example.a", Version.parseVersion("1.0.0"), Paths.get("/tp/a.jar"), cn.varsa.pde.resolver.algo.BundleOrigin.TARGET)
    val result = ResolveResult(listOf(rb1, rb2), emptyMap<String, VersionRange>(), emptyMap())
    val ctx = LaunchContext(startupLevels = mapOf("com.example.a" to 2))
    val opts = LauncherOptions(defaultStartLevel = 4)
    val plan = LauncherAssembler.from(result, ctx, opts)

    assertEquals(2, plan.bundles.size)
    val a = plan.bundles.first { it.bsn == "com.example.a" }
    assertEquals(2, a.startLevel)
    val osgi = plan.framework
    assertNotNull(osgi)
    assertEquals("org.eclipse.osgi", osgi?.bsn)
  }

  @Test
  fun rendersConfigIniAndBundlesInfo() {
    val rb1 = ResolvedBundle("org.eclipse.osgi", Version.parseVersion("3.18.0"), Paths.get("/tp/osgi.jar"), cn.varsa.pde.resolver.algo.BundleOrigin.TARGET)
    val rb2 = ResolvedBundle("com.example.a", Version.parseVersion("1.0.0"), Paths.get("/tp/a.jar"), cn.varsa.pde.resolver.algo.BundleOrigin.TARGET)
    val plan = LauncherPlan(
      listOf(
        BundleStartSpec(rb1.bsn, rb1.version, rb1.path, 4, true, false),
        BundleStartSpec(rb2.bsn, rb2.version, rb2.path, 2, true, false)
      ),
      framework = BundleStartSpec(rb1.bsn, rb1.version, rb1.path, 4, true, false)
    )
    val props = ConfigIniRenderer.toProperties(plan, LauncherOptions(product = "p", application = "a", splashBSN = rb2.bsn))
    assertEquals("p", props.getProperty("eclipse.product"))
    assertEquals("a", props.getProperty("eclipse.application"))
    assertTrue(props.getProperty("osgi.framework").contains("/tp/osgi.jar"))
    assertTrue(props.getProperty("osgi.splashPath").contains("/tp/a.jar"))

    val txt = BundlesInfoRenderer.toText(plan)
    assertTrue(txt.contains("#version=1"))
    assertTrue(txt.lines().any { it.startsWith("com.example.a,1.0.0,") })
  }

  @Test
  fun prefersWorkspaceBundles() {
    val target = ResolvedBundle("com.example.a", Version.parseVersion("1.0.0"), Paths.get("/tp/a.jar"), cn.varsa.pde.resolver.algo.BundleOrigin.TARGET)
    val workspace = ResolvedBundle("com.example.a", Version.parseVersion("1.0.0"), Paths.get("/ws/a"), cn.varsa.pde.resolver.algo.BundleOrigin.WORKSPACE)
    val result = ResolveResult(listOf(target, workspace), emptyMap(), emptyMap())
    val ctx = LaunchContext(startupLevels = mapOf("com.example.a" to 3))
    val plan = LauncherAssembler.from(result, ctx, LauncherOptions(defaultStartLevel = 4))
    val spec = plan.bundles.first { it.bsn == "com.example.a" }
    assertTrue(spec.isWorkspace)
    assertEquals(3, spec.startLevel)
  }
}
