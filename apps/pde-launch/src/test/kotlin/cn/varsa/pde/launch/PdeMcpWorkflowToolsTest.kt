package cn.varsa.pde.launch

import cn.varsa.cli.core.cliMcpTools
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PdeMcpWorkflowToolsTest {

  @Test
  fun `compile workflow tool does not accept workspace override`() {
    val tool = pdeMcpWorkflowCommand.cliMcpTools().single { it.name == "pde_compile_workspace" }
    val properties = assertNotNull(tool.inputSchema.properties)

    assertFalse(properties.containsKey("workspace"))
  }
}
