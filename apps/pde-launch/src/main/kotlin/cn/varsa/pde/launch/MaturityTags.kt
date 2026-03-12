package cn.varsa.pde.launch

import cn.varsa.cli.core.CliStyle
import cn.varsa.cli.core.ColorMode

private fun useAnsiColors(): Boolean = CliStyle.useColor(ColorMode.AUTO)

fun maturityTag(label: String): String {
  val useColor = useAnsiColors()
  return CliStyle.maturityTag(label, useColor)
}
