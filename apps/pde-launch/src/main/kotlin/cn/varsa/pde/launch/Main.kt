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
  if (args.isNotEmpty() && args[0] == "jdtls-init") {
    val exitCode = JdtlsInitCommand.main(args.drop(1).toTypedArray())
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
  println("  clone        Clone repos and sparse-checkout bundles")
  println("  fetch_jars   Run mvn clean package in lib/fetch_jars")
  println("  codegen      Run gateway code generation")
  println("  issue-new    Create issue config from template")
  println("  foreach-repo Run a shell command in each configured repo")
  println("  run          Run a launch config")
  println("  compile      Compile PDE Java bundles")
  println("  target-install  Resolve/prepare target platform")
  println("  test         Run PDE test launch")
  println("  api-analyze  Run API analysis")
  println("  ij-init      Generate IntelliJ project")
  println("  jdtls-init   Generate .project/.classpath for JDT LS")
  println()
  println("Run 'pde <command> --help' for command-specific options.")
}
