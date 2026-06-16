package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.launch.BundleStartSpec
import cn.varsa.pde.resolver.launch.LauncherPlan
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Path
import kotlin.test.assertEquals

class PlanRewriterTest {

  @Test
  fun `rewrites workspace bundle locations to compiled outputs`() {
    val plan = LauncherPlan(
      bundles = listOf(
        BundleStartSpec("ws.bundle", Version.parseVersion("1.0.0"), Path.of("/ws/ws.bundle"), 4, true, true),
        BundleStartSpec("tp.bundle", Version.parseVersion("2.0.0"), Path.of("/tp/tp.bundle"), 4, true, false)
      ),
      framework = null
    )
    val specs = listOf(
      CompileSpec(
        bsn = "ws.bundle",
        version = "1.0.0",
        origin = "workspace",
        bundlePath = "/ws/ws.bundle",
        classpath = emptyList(),
        sourceRoots = emptyList(),
        resourceIncludes = emptyList(),
        resourceExcludes = emptyList(),
        compilerPrefs = emptyMap(),
        executionEnvironment = null,
        outputDirectory = "/ws/ws.bundle/bin",
        isWorkspace = true
      )
    )

    val rewritten = rewritePlanWithCompiledOutputs(plan, specs)
    val ws = rewritten.bundles.first { it.bsn == "ws.bundle" }
    val tp = rewritten.bundles.first { it.bsn == "tp.bundle" }

    // Compare Path objects (not OS-specific strings): the workspace location is the rewritten,
    // absolutized+normalized output dir; the target bundle is untouched.
    assertEquals(Path.of("/ws/ws.bundle/bin").toAbsolutePath().normalize(), ws.location)
    assertEquals(Path.of("/tp/tp.bundle"), tp.location)
  }
}
