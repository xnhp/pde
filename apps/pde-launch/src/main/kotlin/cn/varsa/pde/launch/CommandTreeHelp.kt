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

  // Some leaves accept a mode positional (e.g. `pde target install api-baseline`) instead of
  // being modeled as a real subcommand. List them here so the tree documents them as if they
  // were child leaves, without changing actual CLI dispatch.
  val virtualChildren: Map<List<String>, List<Pair<String, String>>> = mapOf(
    listOf("target", "install") to listOf(
      "api-baseline" to "Provision the API baseline profile instead of the primary target"
    )
  )

  val rows = mutableListOf<Row>()

  fun visit(node: CliCommandNode, path: List<String>, prefix: String, isLast: Boolean) {
    val connector = if (isLast) "└── " else "├── "
    val summary = node.description.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    rows += Row("$prefix$connector${node.name}", summary)

    val nodePath = path + node.name
    val childPrefix = prefix + if (isLast) "    " else "│   "
    val groupChildren = if (node is CliCommandGroup) node.children else emptyList()
    val extra = virtualChildren[nodePath].orEmpty()
    groupChildren.forEachIndexed { index, child ->
      visit(child, nodePath, childPrefix, index == groupChildren.lastIndex && extra.isEmpty())
    }
    extra.forEachIndexed { index, (name, summaryText) ->
      val extraConnector = if (index == extra.lastIndex) "└── " else "├── "
      rows += Row("$childPrefix$extraConnector$name", summaryText)
    }
  }

  root.children.forEachIndexed { index, child ->
    visit(child, emptyList(), "", index == root.children.lastIndex)
  }

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
