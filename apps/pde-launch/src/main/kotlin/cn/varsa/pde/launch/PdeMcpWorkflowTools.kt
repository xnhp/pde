package cn.varsa.pde.launch

import cn.varsa.cli.core.CliCommandGroup
import cn.varsa.cli.core.CliCommandLeaf
import cn.varsa.cli.core.CliMcpRegistrationConfig
import cn.varsa.cli.core.CliToolBinding
import cn.varsa.cli.core.registerCliTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val pdeMcpWorkflowCommand = CliCommandGroup(
  name = "pde",
  description = "PDE MCP workflow tools",
  children = listOf(
    workflowLeaf(
      name = "compile-workspace",
      description = "Compile PDE Java bundles for a workspace or launch configuration.",
      tool = CliToolBinding(
        id = "pde_compile_workspace",
        title = "Compile PDE workspace",
        description = "Compile PDE Java bundles for a workspace or launch configuration.",
        inputSchema = workflowSchema(
          "config" to stringProperty("Path to pde.yaml or equivalent launch config."),
          "workspace" to stringArrayProperty("Workspace bundle directories to compile."),
          "fullRebuild" to booleanProperty("Force full rebuild of all workspace bundles.", default = false)
        ),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional("--config", arguments.string("config"))
            addRepeated("--workspace", arguments.stringArray("workspace"))
            addFlag("--full-rebuild", arguments.boolean("fullRebuild"))
          }
        }
      ),
      command = arrayOf("compile")
    ),
    workflowLeaf(
      name = "run-launch",
      description = "Run a configured PDE launch.",
      tool = CliToolBinding(
        id = "pde_run_launch",
        title = "Run PDE launch",
        description = "Run a configured PDE launch.",
        inputSchema = workflowSchema(
          "config" to stringProperty("Path to launch config."),
          "launch" to stringProperty("Launch name from the config.")
        ),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional("--config", arguments.string("config"))
            addOptional(null, arguments.string("launch"))
          }
        }
      ),
      command = arrayOf("run")
    ),
    workflowLeaf(
      name = "run-test",
      description = "Run one or more PDE test launches.",
      tool = CliToolBinding(
        id = "pde_run_test",
        title = "Run PDE tests",
        description = "Run one or more PDE test launches.",
        inputSchema = workflowSchema(
          "config" to stringProperty("Path to launch config."),
          "tests" to stringArrayProperty("Test names or indexes; empty means all configured tests.")
        ),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional("--config", arguments.string("config"))
            addRepeated(null, arguments.stringArray("tests"))
          }
        }
      ),
      command = arrayOf("test")
    ),
    workflowLeaf(
      name = "prepare-target",
      description = "Resolve or prepare target platform state for a config.",
      tool = CliToolBinding(
        id = "pde_prepare_target",
        title = "Prepare PDE target",
        description = "Resolve or prepare target platform state for a config.",
        inputSchema = workflowSchema(
          "config" to stringProperty("Path to launch config.")
        ),
        annotations = ToolAnnotations(destructiveHint = true),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional("--config", arguments.string("config"))
          }
        }
      ),
      command = arrayOf("target", "install")
    )
  )
)

internal fun Server.registerPdeWorkflowTools(config: CliMcpRegistrationConfig = CliMcpRegistrationConfig()) {
  registerCliTools(pdeMcpWorkflowCommand, config)
}

private fun workflowLeaf(
  name: String,
  description: String,
  tool: CliToolBinding,
  command: Array<String>
): CliCommandLeaf = CliCommandLeaf(
  name = name,
  description = description,
  tool = tool,
  handler = { args -> runPde(command + args) }
)

private fun workflowSchema(vararg properties: Pair<String, kotlinx.serialization.json.JsonElement>): ToolSchema = ToolSchema(
  properties = buildJsonObject {
    properties.forEach { (name, property) -> put(name, property) }
  }
)

private fun stringProperty(description: String) = buildJsonObject {
  put("type", JsonPrimitive("string"))
  put("description", JsonPrimitive(description))
}

private fun stringArrayProperty(description: String) = buildJsonObject {
  put("type", JsonPrimitive("array"))
  put("description", JsonPrimitive(description))
  put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
}

private fun booleanProperty(description: String, default: Boolean) = buildJsonObject {
  put("type", JsonPrimitive("boolean"))
  put("description", JsonPrimitive(description))
  put("default", JsonPrimitive(default))
}

private fun JsonObject?.string(name: String): String? = this
  ?.get(name)
  ?.jsonPrimitive
  ?.content
  ?.takeIf { it.isNotBlank() }

private fun JsonObject?.boolean(name: String): Boolean = this
  ?.get(name)
  ?.jsonPrimitive
  ?.booleanOrNull
  ?: false

private fun JsonObject?.stringArray(name: String): List<String> = when (val value = this?.get(name)) {
  is JsonArray -> value.mapNotNull { element -> element.jsonPrimitive.content.takeIf { it.isNotBlank() } }
  null -> emptyList()
  else -> listOfNotNull(value.jsonPrimitive.content.takeIf { it.isNotBlank() })
}

private fun buildArgs(block: MutableList<String>.() -> Unit): Array<String> = buildList(block).toTypedArray()

private fun MutableList<String>.addOptional(option: String?, value: String?) {
  if (value == null) return
  if (option != null) add(option)
  add(value)
}

private fun MutableList<String>.addRepeated(option: String?, values: List<String>) {
  values.forEach { value -> addOptional(option, value) }
}

private fun MutableList<String>.addFlag(option: String, enabled: Boolean) {
  if (enabled) add(option)
}
