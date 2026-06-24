package cn.varsa.pde.testflow

import cn.varsa.pde.resolver.cli.config.LaunchConfig
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchRuntime
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TestflowApplyTest {

  private fun context(runtime: LaunchRuntime): LaunchConfigContext = LaunchConfigContext(
    file = Paths.get("pde.yaml"),
    baseDir = Paths.get("").toAbsolutePath(),
    config = LaunchConfig(),
    workingDir = Paths.get("").toAbsolutePath(),
    runtime = runtime,
    jvmDebug = false,
    jvmDebugRequiresPdeTestApp = false,
  )

  @Test
  fun `selects the NG testflow runner application and clears product`() {
    val result = applyTestflowRun(
      context(LaunchRuntime(product = "org.knime.product.KNIME_PRODUCT")),
      TestflowRunOptions(rootDirs = listOf("/flows"), xmlResultDir = "/out"),
    )

    assertEquals("org.knime.testing.NGTestflowRunner", result.runtime.application)
    assertNull(result.runtime.product)
  }

  @Test
  fun `attaches the mapped testflow program args`() {
    val result = applyTestflowRun(
      context(LaunchRuntime()),
      TestflowRunOptions(rootDirs = listOf("/flows"), xmlResultDir = "/out", include = "/Misc/.*"),
    )

    assertEquals(
      listOf("-root", "/flows", "-xmlResultDir", "/out", "-include", "/Misc/.*"),
      result.runtime.programArgs,
    )
  }
}
