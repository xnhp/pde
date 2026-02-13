package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.compileMain
import cn.varsa.pde.resolver.cli.launchMain
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    printHelp()
    return
  }
  if (args.isNotEmpty() && (args[0] == "-h" || args[0] == "--help" || args[0] == "help")) {
    printHelp()
    return
  }
  if (args.isNotEmpty() && args[0] == "ij-init") {
    val exitCode = IjInit.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "emacs-init") {
    val exitCode = EmacsInit.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "compile") {
    val exitCode = compileMain(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "clone") {
    val exitCode = CloneCommand.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "launch") {
    launchMain(args.drop(1).toTypedArray())
    return
  }
  launchMain(args)
}

private fun printHelp() {
  println("pde - PDE tooling CLI")
  println()
  println("Usage:")
  println("  pde <command> [options]")
  println()
  println("Commands:")
  println("  clone        Clone repos and sparse-checkout bundles")
  println("  launch       Run a launch config")
  println("  compile      Compile workspace bundles")
  println("  target       Resolve/prepare target platform")
  println("  test         Run PDE test launch")
  println("  api-analyze  Run API analysis")
  println("  ij-init      Generate IntelliJ/PDE project")
  println("  emacs-init   Generate Emacs/JDT LS workspace")
  println()
  println("Run 'pde <command> --help' for command-specific options.")
}
