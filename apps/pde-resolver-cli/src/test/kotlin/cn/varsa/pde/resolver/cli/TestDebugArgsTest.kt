package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.cli.config.LaunchConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class TestDebugArgsTest {
  @Test
  fun `adds jdwp agent when test debugging is enabled for PDE JUnit app`() {
    val config = LaunchConfig(application = PDE_JUNIT_PLUGIN_TEST_APPLICATION, debugTests = true)

    val args = buildTestDebugVmArgs(config, emptyList())

    assertContains(args.first(), "jdwp")
    assertContains(args.first(), DEFAULT_TEST_DEBUG_PORT.toString())
    assertTrue(args.first().contains("suspend=y"))
  }

  @Test
  fun `skips jdwp agent when already present or non test app`() {
    val config = LaunchConfig(application = "org.example.app", debugTests = true)

    val nonTestArgs = buildTestDebugVmArgs(config, emptyList())
    assertTrue(nonTestArgs.isEmpty())

    val testConfig = config.copy(application = PDE_JUNIT_PLUGIN_TEST_APPLICATION)
    val argsWithExisting = buildTestDebugVmArgs(testConfig, listOf("-agentlib:jdwp=transport=dt_socket"))
    assertTrue(argsWithExisting.isEmpty())
  }
}
