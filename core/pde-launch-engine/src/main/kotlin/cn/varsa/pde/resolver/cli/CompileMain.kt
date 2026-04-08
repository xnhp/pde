package cn.varsa.pde.resolver.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val exitCode = compileMain(args)
  exitProcess(exitCode)
}
