package cn.varsa.pde.launch

import cn.varsa.cli.core.CliCommandLeaf
import cn.varsa.cli.core.CliToolBinding
import cn.varsa.cli.core.cliMcpTools
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PdeMcpWorkflowToolsTest {

  @Test
  fun `compile workflow tool does not accept workspace override`() {
    val tool = pdeMcpWorkflowCommand.cliMcpTools().single { it.name == "pde_compile_workspace" }
    val properties = assertNotNull(tool.inputSchema.properties)

    assertFalse(properties.containsKey("workspace"))
  }

  @Test
  fun `compile workflow tool exposes the warnings flag`() {
    val tool = pdeMcpWorkflowCommand.cliMcpTools().single { it.name == "pde_compile_workspace" }
    val properties = assertNotNull(tool.inputSchema.properties)

    val warnings = assertNotNull(properties["warnings"]).jsonObject
    assertEquals("boolean", warnings["type"]?.jsonPrimitive?.content)
  }

  @Test
  fun `compile workflow tool passes --warnings only when requested`() {
    val decode = compileBinding().decodeArguments

    assertEquals(emptyList<String>(), decode(buildJsonObject { }).toList())
    assertEquals(
      emptyList<String>(),
      decode(buildJsonObject { put("warnings", JsonPrimitive(false)) }).toList()
    )
    assertEquals(
      listOf("--warnings"),
      decode(buildJsonObject { put("warnings", JsonPrimitive(true)) }).toList()
    )
    assertEquals(
      listOf("--config", "pde.yaml", "--warnings"),
      decode(
        buildJsonObject {
          put("config", JsonPrimitive("pde.yaml"))
          put("warnings", JsonPrimitive(true))
        }
      ).toList()
    )
  }

  private fun compileBinding(): CliToolBinding {
    val leaf = pdeMcpWorkflowCommand.children
      .filterIsInstance<CliCommandLeaf>()
      .single { it.name == "compile-workspace" }
    return assertNotNull(leaf.tool)
  }
}
