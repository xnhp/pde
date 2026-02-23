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
  println("  clone          ${maturityTag("usable")} Clone repos and sparse-checkout bundles")
  println("  fetch_jars     ${maturityTag("WIP")} Run mvn clean package in lib/fetch_jars")
  println("  codegen        ${maturityTag("WIP")} Run gateway code generation")
  println("  issue-new      ${maturityTag("WIP")} Create issue config from template")
  println("  foreach-repo   ${maturityTag("usable")} Run a shell command in each configured repo")
  println("  run            ${maturityTag("usable")} Run a launch config")
  println("  compile        ${maturityTag("usable")} Compile PDE Java bundles")
  println("  target-install ${maturityTag("usable")} Resolve/prepare target platform")
  println("  format         ${maturityTag("WIP")} Format Java sources via Eclipse formatter")
  println("  test           ${maturityTag("usable")} Run PDE test launch")
  println("  api-analyze    ${maturityTag("WIP")} Run API analysis")
  println("  ij-init        ${maturityTag("usable")} Generate IntelliJ project")
  println("  jdtls-init     ${maturityTag("WIP")} Generate .project/.classpath for JDT LS")
  println()
  println("Run 'pde <command> --help' for command-specific options.")
  println()
  println("Maturity:")
  println("  ${maturityTag("usable")} ready for daily use within limits, may have quirks or known limitations but core functionality is stable")
  println("  ${maturityTag("WIP")} actively being worked on, might not be functional -- see gh issues")
}
