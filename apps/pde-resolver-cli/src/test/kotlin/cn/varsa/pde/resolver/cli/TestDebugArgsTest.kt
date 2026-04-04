package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.cli.config.LaunchConfig
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchRuntime
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class TestDebugArgsTest {
  @Test
  fun `adds jdwp agent when debug is enabled for PDE JUnit app`() {
    val config = LaunchConfig()
    val context = LaunchConfigContext(
      file = Paths.get("pde.yaml"),
      baseDir = Paths.get("").toAbsolutePath(),
      config = config,
      workingDir = Paths.get("").toAbsolutePath(),
      runtime = LaunchRuntime(application = PDE_JUNIT_PLUGIN_TEST_APPLICATION),
      jvmDebug = true,
      jvmDebugRequiresPdeTestApp = true
    )

    val args = buildDebugVmArgs(context, emptyList())

    assertContains(args.first(), "jdwp")
    assertContains(args.first(), DEFAULT_TEST_DEBUG_PORT.toString())
    assertTrue(args.first().contains("suspend=y"))
  }

  @Test
  fun `skips jdwp agent when already present or non test app`() {
    val config = LaunchConfig()
    val context = LaunchConfigContext(
      file = Paths.get("pde.yaml"),
      baseDir = Paths.get("").toAbsolutePath(),
      config = config,
      workingDir = Paths.get("").toAbsolutePath(),
      runtime = LaunchRuntime(application = "org.example.app"),
      jvmDebug = true,
      jvmDebugRequiresPdeTestApp = true
    )

    val nonTestArgs = buildDebugVmArgs(context, emptyList())
    assertTrue(nonTestArgs.isEmpty())

    val testContext = context.copy(runtime = context.runtime.copy(application = PDE_JUNIT_PLUGIN_TEST_APPLICATION))
    val argsWithExisting = buildDebugVmArgs(testContext, listOf("-agentlib:jdwp=transport=dt_socket"))
    assertTrue(argsWithExisting.isEmpty())
  }

  @Test
  fun `adds jdwp agent for non test app when not restricted`() {
    val config = LaunchConfig()
    val context = LaunchConfigContext(
      file = Paths.get("pde.yaml"),
      baseDir = Paths.get("").toAbsolutePath(),
      config = config,
      workingDir = Paths.get("").toAbsolutePath(),
      runtime = LaunchRuntime(application = "org.example.app"),
      jvmDebug = true,
      jvmDebugRequiresPdeTestApp = false
    )

    val args = buildDebugVmArgs(context, emptyList())

    assertContains(args.first(), "jdwp")
  }
}
