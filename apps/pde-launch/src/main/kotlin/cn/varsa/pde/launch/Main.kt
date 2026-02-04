package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.launchMain

fun main(args: Array<String>) {
  System.setProperty("pde.launch.disableStaleWorkspaceWarning", "true")
  launchMain(args)
}
