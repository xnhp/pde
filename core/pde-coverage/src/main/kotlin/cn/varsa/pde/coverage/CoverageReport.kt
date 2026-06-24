package cn.varsa.pde.coverage

import cn.varsa.pde.cli.support.configureLogging
import cn.varsa.pde.cli.support.discoverConfigFile
import cn.varsa.pde.cli.support.logCommand
import cn.varsa.pde.cli.support.resolveLogLevel
import cn.varsa.pde.cli.support.shouldUseColor
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.config.WorkspaceModuleResolver
import cn.varsa.pde.resolver.cli.maturityTag
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("pde-coverage")

fun coverageReportMain(args: Array<String>): Int {
  val parser = ArgParser("pde coverage report ${maturityTag("WIP")}")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML config (workspace bundles -> class/source files)")
  val logLevelOpt by parser.option(ArgType.String, fullName = "log-level", description = "Logging level (error|warn|info|debug|trace)")
  val verbose by parser.option(ArgType.Boolean, fullName = "verbose", shortName = "v", description = "Enable INFO logging").default(false)
  val classfilesOpt by parser.option(ArgType.String, fullName = "classfiles", description = "Extra class dir or jar to analyze (repeatable)").multiple()
  val sourcefilesOpt by parser.option(ArgType.String, fullName = "sourcefiles", description = "Extra source dir (repeatable)").multiple()
  val outputOpt by parser.option(ArgType.String, fullName = "output", description = "JUnit XML report path (default: <dir>/jacoco.xml)")
  val htmlOpt by parser.option(ArgType.String, fullName = "html", description = "Also write an HTML report into this directory")
  val execDirArg by parser.argument(ArgType.String, description = "Directory containing the .exec files (from --coverage)")
  parser.parse(args)
  configureLogging(resolveLogLevel(logLevelOpt, verbose, false), shouldUseColor())

  val execDir = Paths.get(execDirArg)
  val execFiles = findExecFiles(execDir)
  if (execFiles.isEmpty()) {
    logger.severe("No .exec files under $execDir (run 'pde test'/'pde testflow' with --coverage $execDir first)")
    return 2
  }

  val classDirs = mutableListOf<Path>()
  val sourceDirs = mutableListOf<Path>()
  val configFile = configFileOpt?.let { Paths.get(it) } ?: discoverConfigFile()
  if (configFile != null) {
    val loaded = LaunchConfigLoader.load(configFile)
    for (def in WorkspaceModuleResolver.resolveDefinitions(loaded)) {
      val roots = def.classRoots?.takeIf { it.isNotEmpty() } ?: listOf("bin")
      roots.forEach { classDirs.addAll(coverageClassfileInputs(def.moduleDir.resolve(it))) }
      sourceDirs.add(def.moduleDir.resolve("src"))
    }
  }
  classfilesOpt.forEach { classDirs.add(Paths.get(it)) }
  sourcefilesOpt.forEach { sourceDirs.add(Paths.get(it)) }
  val existingClassDirs = classDirs.filter { Files.exists(it) }
  val existingSourceDirs = sourceDirs.filter { Files.isDirectory(it) }
  if (existingClassDirs.isEmpty()) {
    logger.severe(
      "No class files to analyze. Pass --classfiles <dir|jar>, or a --config with compiled workspace " +
        "bundles (run 'pde compile' first)."
    )
    return 2
  }

  val cli = findBundledJacocoCli()
  if (cli == null || !Files.isRegularFile(cli)) {
    logger.severe("Bundled jacococli not found in pde's lib/ (expected org.jacoco.cli-*.jar).")
    return 2
  }

  val xmlOut = (outputOpt?.let { Paths.get(it) } ?: execDir.resolve("jacoco.xml")).toAbsolutePath().normalize()
  xmlOut.parent?.let { Files.createDirectories(it) }
  val htmlOut = htmlOpt?.let { Paths.get(it).toAbsolutePath().normalize().also { dir -> Files.createDirectories(dir) } }

  val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
  val reportArgs = jacocoReportArgs(execFiles, existingClassDirs, existingSourceDirs, xmlOut, htmlOut)
  logger.info(
    "Generating JaCoCo report from ${execFiles.size} exec file(s) over ${existingClassDirs.size} class location(s)"
  )
  logCommand(listOf(javaBin, "-jar", cli.toString()) + reportArgs)
  val exit = runJacocoReport(cli, javaBin, reportArgs)
  if (exit != 0) {
    logger.severe("jacococli report failed with exit code $exit")
    return exit
  }
  logger.info("Wrote JaCoCo XML report to $xmlOut" + (htmlOut?.let { " (HTML: $it)" } ?: ""))
  return 0
}
