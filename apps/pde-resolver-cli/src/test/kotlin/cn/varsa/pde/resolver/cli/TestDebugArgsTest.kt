package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.cli.config.LaunchConfig
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class TestDebugArgsTest {
  @Test
  fun `adds jdwp agent when debug is enabled for PDE JUnit app`() {
    val config = LaunchConfig(application = PDE_JUNIT_PLUGIN_TEST_APPLICATION)
    val context = LaunchConfigContext(
      file = Paths.get("config.yaml"),
      baseDir = Paths.get("").toAbsolutePath(),
      config = config,
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
    val config = LaunchConfig(application = "org.example.app")
    val context = LaunchConfigContext(
      file = Paths.get("config.yaml"),
      baseDir = Paths.get("").toAbsolutePath(),
      config = config,
      jvmDebug = true,
      jvmDebugRequiresPdeTestApp = true
    )

    val nonTestArgs = buildDebugVmArgs(context, emptyList())
    assertTrue(nonTestArgs.isEmpty())

    val testContext = context.copy(config = config.copy(application = PDE_JUNIT_PLUGIN_TEST_APPLICATION))
    val argsWithExisting = buildDebugVmArgs(testContext, listOf("-agentlib:jdwp=transport=dt_socket"))
    assertTrue(argsWithExisting.isEmpty())
  }

  @Test
  fun `adds jdwp agent for non test app when not restricted`() {
    val config = LaunchConfig(application = "org.example.app")
    val context = LaunchConfigContext(
      file = Paths.get("config.yaml"),
      baseDir = Paths.get("").toAbsolutePath(),
      config = config,
      jvmDebug = true,
      jvmDebugRequiresPdeTestApp = false
    )

    val args = buildDebugVmArgs(context, emptyList())

    assertContains(args.first(), "jdwp")
  }
}
