package cn.varsa.pde.launch

import cn.varsa.pde.remoterunner.ConsoleTags

private fun useAnsiColors(): Boolean = System.console() != null

fun maturityTag(label: String): String {
  val useColor = useAnsiColors()
  return when (label.lowercase()) {
    "usable" -> ConsoleTags.success(label, useColor)
    "wip" -> ConsoleTags.danger(label, useColor)
    else -> "[$label]"
  }
}
