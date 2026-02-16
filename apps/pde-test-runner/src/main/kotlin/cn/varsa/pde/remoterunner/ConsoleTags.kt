package cn.varsa.pde.remoterunner

object ConsoleTags {
  private const val reset = "\u001B[0m"
  private const val green = "\u001B[32m"
  private const val red = "\u001B[31m"
  private const val yellow = "\u001B[33m"
  private const val cyan = "\u001B[36m"
  private const val blue = "\u001B[34m"

  fun info(color: Boolean): String = paint("[INFO]", blue, color)
  fun warn(color: Boolean): String = paint("[WARN]", yellow, color)
  fun error(color: Boolean): String = paint("[ERROR]", red, color)
  fun debug(color: Boolean): String = paint("[DEBUG]", blue, color)
  fun trace(color: Boolean): String = paint("[TRACE]", blue, color)
  fun test(color: Boolean): String = paint("[TEST]", cyan, color)
  fun pass(color: Boolean): String = paint("[PASS]", green, color)
  fun fail(color: Boolean): String = paint("[FAIL]", red, color)
  fun label(label: String, color: Boolean): String = paint("[$label]", cyan, color)

  private fun paint(text: String, colorCode: String, enabled: Boolean): String =
    if (enabled) "$colorCode$text$reset" else text
}
