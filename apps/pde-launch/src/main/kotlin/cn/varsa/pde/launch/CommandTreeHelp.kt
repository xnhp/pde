package cn.varsa.pde.launch

import cn.varsa.cli.core.CliCommandGroup
import cn.varsa.cli.core.CliCommandNode

/**
 * Renders the full `pde` command tree (every group and leaf, recursively indented under its
 * parent) with a one-line description per node. Used for top-level `pde` / `pde --help` only --
 * subcommand-level `--help` keeps picocli's default per-command usage (options/positionals).
 */
internal fun pdeCommandTreeHelpText(root: CliCommandGroup): String {
  data class Row(val label: String, val summary: String)

  val rows = mutableListOf<Row>()

  fun visit(node: CliCommandNode, depth: Int) {
    val indent = "  ".repeat(depth)
    val summary = node.description.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    rows += Row("$indent${node.name}", summary)
    if (node is CliCommandGroup) {
      node.children.forEach { child -> visit(child, depth + 1) }
    }
  }

  root.children.forEach { child -> visit(child, 1) }

  val labelWidth = (rows.maxOfOrNull { it.label.length } ?: 0) + 2

  return buildString {
    appendLine("${root.name} - ${root.description}")
    appendLine()
    rows.forEach { row ->
      if (row.summary.isBlank()) {
        appendLine(row.label)
      } else {
        appendLine(row.label.padEnd(labelWidth) + row.summary)
      }
    }
  }.trimEnd()
}
