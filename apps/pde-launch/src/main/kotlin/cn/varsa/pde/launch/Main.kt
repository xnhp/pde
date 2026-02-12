package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.launchMain
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.isNotEmpty() && args[0] == "emacs-init") {
    val exitCode = EmacsInit.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  launchMain(args)
}
