package cn.varsa.pde.launch

import cn.varsa.cli.core.CliCommandGroup
import cn.varsa.cli.core.CliCommandLeaf
import cn.varsa.cli.core.CliMain
import cn.varsa.pde.resolver.cli.compileMain
import cn.varsa.pde.resolver.cli.launchMain
import pde.format.main as formatMain
import picocli.CommandLine

private fun forwardToLaunch(commandName: String, vararg prefix: String): (Array<String>) -> Int = { args ->
  launchMain((prefix.toList() + args).toTypedArray(), commandName = commandName)
  0
}

private val pdeCommand = CliCommandGroup(
  name = "pde",
  description = "PDE tooling CLI",
  children = listOf(
    CliCommandGroup(
      name = "ide-init",
      description = "Generate IDE project files",
      children = listOf(
        CliCommandLeaf(
          name = "idea",
          description = "Generate IntelliJ project",
          handler = { args -> IjInit.main(args) }
        ),
        CliCommandLeaf(
          name = "jdtls",
          description = "Generate .project/.classpath for JDT LS",
          handler = { args -> JdtlsInitCommand.main(args) }
        )
      )
    ),
    CliCommandLeaf(
      name = "compile",
      description = "Compile PDE Java bundles",
      handler = { args -> compileMain(args) }
    ),
    CliCommandLeaf(
      name = "format",
      description = "Format Java sources via Eclipse formatter",
      handler = { args ->
        formatMain(args)
        0
      }
    ),
    CliCommandLeaf(
      name = "add-test",
      description = "Append a test entry to launch config",
      handler = { args -> AddTestCommand.main(args) }
    ),
    CliCommandLeaf(
      name = "add-test-helper",
      description = "Append a gateway helper test entry",
      handler = { args -> AddTestHelperCommand.main(args) }
    ),
    CliCommandLeaf(
      name = "run",
      description = "Run a launch config",
      handler = forwardToLaunch("pde run")
    ),
    CliCommandLeaf(
      name = "launch",
      description = "Run a launch config",
      handler = forwardToLaunch("pde launch")
    ),
    CliCommandGroup(
      name = "target",
      description = "Target platform commands (install, mirror)",
      children = listOf(
        CliCommandLeaf(
          name = "install",
          description = "Resolve/prepare target platform state",
          handler = forwardToLaunch("pde target install", "target", "install")
        ),
        CliCommandLeaf(
          name = "mirror",
          description = "Mirror update sites from a .target definition",
          handler = forwardToLaunch("pde target mirror", "target", "mirror")
        )
      )
    ),
    CliCommandLeaf(
      name = "test",
      description = "Run PDE test launch",
      handler = forwardToLaunch("pde test", "test")
    ),
    CliCommandLeaf(
      name = "api-analyze",
      description = "Run API analysis",
      handler = forwardToLaunch("pde api-analyze", "api-analyze")
    ),
    CliCommandLeaf(
      name = "schema",
      description = "Print the active pde schema path",
      handler = { args -> SchemaCommand.main(args) }
    )
  )
)

internal fun runPde(args: Array<String>) {
  CliMain.run(pdeCommand, args)
}

fun main(args: Array<String>) {
  runPde(args)
}

internal fun createPdeCommandLine(): CommandLine = CliMain.createCommandLine(pdeCommand)
