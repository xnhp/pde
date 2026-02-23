package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.compileMain
import cn.varsa.pde.resolver.cli.launchMain
import pde.format.main as formatMain
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
  if (args.isNotEmpty() && args[0] == "jdtls-init") {
    val exitCode = JdtlsInitCommand.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "compile") {
    val exitCode = compileMain(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "format") {
    formatMain(args.drop(1).toTypedArray())
    return
  }
  if (args.isNotEmpty() && args[0] == "clone") {
    val exitCode = CloneCommand.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "fetch_jars") {
    val exitCode = FetchJarsCommand.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "codegen") {
    val exitCode = CodegenCommand.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "issue-new") {
    val exitCode = IssueNewCommand.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && args[0] == "foreach-repo") {
    val exitCode = ForeachRepoCommand.main(args.drop(1).toTypedArray())
    exitProcess(exitCode)
  }
  if (args.isNotEmpty() && (args[0] == "run" || args[0] == "launch")) {
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
  println("  clone          [usable] Clone repos and sparse-checkout bundles")
  println("  fetch_jars     [WIP] Run mvn clean package in lib/fetch_jars")
  println("  codegen        [WIP] Run gateway code generation")
  println("  issue-new      [WIP] Create issue config from template")
  println("  foreach-repo   [usable] Run a shell command in each configured repo")
  println("  run            [usable] Run a launch config")
  println("  compile        [usable] Compile PDE Java bundles")
  println("  target-install [usable] Resolve/prepare target platform")
  println("  format         [WIP] Format Java sources via Eclipse formatter")
  println("  test           [usable] Run PDE test launch")
  println("  api-analyze    [WIP] Run API analysis")
  println("  ij-init        [usable] Generate IntelliJ project")
  println("  jdtls-init     [WIP] Generate .project/.classpath for JDT LS")
  println()
  println("Run 'pde <command> --help' for command-specific options.")
  println()
  println("Maturity:")
  println("  [usable] ready for daily use within limits, may have quirks or known limitations but core functionality is stable")
  println("  [WIP] actively being worked on, might not be functional -- see gh issues")
}
