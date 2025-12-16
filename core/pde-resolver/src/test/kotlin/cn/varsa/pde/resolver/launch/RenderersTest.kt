package cn.varsa.pde.resolver.launch

import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Paths

class RenderersTest {
  private fun bundle(
    bsn: String,
    version: String,
    startLevel: Int = 4,
    auto: Boolean = true,
    pathSuffix: String = version
  ) = BundleStartSpec(
    bsn = bsn,
    version = Version.parseVersion(version),
    location = Paths.get("/tmp/$bsn/$pathSuffix"),
    startLevel = startLevel,
    autoStart = auto,
    isWorkspace = false
  )

  @Test
  fun configIniRendererProducesExpectedProperties() {
    val framework = bundle("org.eclipse.osgi", "3.14.0")
    val splash = bundle("org.example.splash", "1.0.0")
    val plan = LauncherPlan(
      bundles = listOf(framework, splash),
      framework = framework,
      properties = mapOf("custom.key" to "value")
    )
    val opts = LauncherOptions(
      product = "org.example.product",
      application = "org.example.app",
      splashBSN = splash.bsn,
      frameworkExtensions = listOf(splash.bsn)
    )

    val props = ConfigIniRenderer.toProperties(plan, opts)
    assertEquals("value", props["custom.key"])
    assertEquals("org.example.product", props["eclipse.product"])
    assertEquals("org.example.app", props["eclipse.application"])
    assertTrue((props["osgi.framework"] as String).contains("org.eclipse.osgi"))
    assertTrue((props["osgi.splashPath"] as String).contains("org.example.splash"))
    assertTrue((props["osgi.framework.extensions"] as String).contains("org.example.splash"))
  }

  @Test
  fun bundlesInfoRendererOutputsSortedList() {
    val plan = LauncherPlan(
      bundles = listOf(
        bundle("b.bundle", "1.0.0", startLevel = 5, auto = false),
        bundle("a.bundle", "2.0.0", startLevel = 3, auto = true)
      ),
      framework = null
    )

    val text = BundlesInfoRenderer.toText(plan).trim().lines()
    assertEquals("#version=1", text.first())
    assertTrue(text[1].startsWith("a.bundle"))
    assertTrue(text[2].startsWith("b.bundle"))
  }

  @Test
  fun devPropertiesRendererSerializesLaunchContext() {
    val ctx = LaunchContext(
      startupLevels = mapOf("a.bundle" to 3),
      devProperties = mapOf(
        "a.bundle" to listOf("path1", "path2"),
        "b.bundle" to listOf("path3")
      )
    )

    val props = DevPropertiesRenderer.toProperties(ctx)
    assertEquals("path1,path2", props["a.bundle"])
    assertEquals("path3", props["b.bundle"])
    assertEquals("true", props["@ignoredot@"])
  }
}
