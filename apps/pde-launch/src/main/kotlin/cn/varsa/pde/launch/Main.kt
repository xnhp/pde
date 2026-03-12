package cn.varsa.pde.launch

import cn.varsa.cli.core.CliMain
import cn.varsa.pde.resolver.cli.compileMain
import cn.varsa.pde.resolver.cli.launchMain
import pde.format.main as formatMain
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import picocli.CommandLine.Model.CommandSpec
import java.util.concurrent.Callable

@Command(
  name = "pde",
  description = ["PDE tooling CLI"],
  mixinStandardHelpOptions = true,
  subcommands = [
    IdeInitSubcommand::class,
    CompileSubcommand::class,
    FormatSubcommand::class,
    FetchJarsSubcommand::class,
    CodegenSubcommand::class,
    AddTestSubcommand::class,
    AddTestHelperSubcommand::class,
    RunSubcommand::class,
    TargetSubcommand::class,
    TestSubcommand::class,
    ApiAnalyzeSubcommand::class
  ]
)
private class PdeCommand : Runnable {
  @Spec
  lateinit var spec: CommandSpec

  override fun run() {
    spec.commandLine().usage(System.out)
  }
}

@Command(
  name = "ide-init",
  description = ["Generate IDE project files"],
  mixinStandardHelpOptions = true,
  subcommands = [IdeInitIdeaSubcommand::class, IdeInitJdtlsSubcommand::class]
)
private class IdeInitSubcommand : Runnable {
  @Spec
  lateinit var spec: CommandSpec

  override fun run() {
    spec.commandLine().usage(System.out)
  }
}

@Command(name = "idea", description = ["Generate IntelliJ project"], mixinStandardHelpOptions = true) private class IdeInitIdeaSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int = IjInit.main(args.toTypedArray())
}

@Command(name = "jdtls", description = ["Generate .project/.classpath for JDT LS"], mixinStandardHelpOptions = true) private class IdeInitJdtlsSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int = JdtlsInitCommand.main(args.toTypedArray())
}

@Command(name = "compile", description = ["Compile PDE Java bundles"], mixinStandardHelpOptions = true) private class CompileSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int = compileMain(args.toTypedArray())
}

@Command(name = "format", description = ["Format Java sources via Eclipse formatter"], mixinStandardHelpOptions = true) private class FormatSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int {
    formatMain(args.toTypedArray())
    return 0
  }
}

@Command(name = "fetch_jars", description = ["Run mvn clean package in lib/fetch_jars"], mixinStandardHelpOptions = true) private class FetchJarsSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int = FetchJarsCommand.main(args.toTypedArray())
}

@Command(name = "codegen", description = ["Run gateway code generation"], mixinStandardHelpOptions = true) private class CodegenSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int = CodegenCommand.main(args.toTypedArray())
}

@Command(name = "add-test", description = ["Append a test entry to launch config"], mixinStandardHelpOptions = true) private class AddTestSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int = AddTestCommand.main(args.toTypedArray())
}

@Command(name = "add-test-helper", description = ["Append a gateway helper test entry"], mixinStandardHelpOptions = true) private class AddTestHelperSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int = AddTestHelperCommand.main(args.toTypedArray())
}

@Command(name = "run", aliases = ["launch"], description = ["Run a launch config"], mixinStandardHelpOptions = true) private class RunSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int {
    launchMain(args.toTypedArray())
    return 0
  }
}

@Command(name = "target", description = ["Target platform commands (install, mirror)"], mixinStandardHelpOptions = true) private class TargetSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int {
    launchMain((listOf("target") + args).toTypedArray())
    return 0
  }
}

@Command(name = "test", description = ["Run PDE test launch"], mixinStandardHelpOptions = true) private class TestSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int {
    launchMain((listOf("test") + args).toTypedArray())
    return 0
  }
}

@Command(name = "api-analyze", description = ["Run API analysis"], mixinStandardHelpOptions = true) private class ApiAnalyzeSubcommand : Callable<Int> {
  @Parameters(arity = "0..*")
  var args: List<String> = emptyList()
  override fun call(): Int {
    launchMain((listOf("api-analyze") + args).toTypedArray())
    return 0
  }
}

fun main(args: Array<String>) {
  CliMain.run(PdeCommand(), args)
}
