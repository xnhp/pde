package cn.varsa.pde.testflow

import cn.varsa.pde.cli.support.configureLogging
import cn.varsa.pde.cli.support.deleteRecursivelyQuietly
import cn.varsa.pde.cli.support.discoverConfigFile
import cn.varsa.pde.cli.support.looksLikeYamlFile
import cn.varsa.pde.cli.support.normalizeArgsWithImplicitConfig
import cn.varsa.pde.cli.support.resolveLogLevel
import cn.varsa.pde.cli.support.shouldUseColor
import cn.varsa.pde.remoterunner.ReportTarget
import cn.varsa.pde.remoterunner.parseReportTarget
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.coverageVmArgOrNull
import cn.varsa.pde.resolver.cli.executeLaunch
import cn.varsa.pde.resolver.cli.maturityTag
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.optional
import java.nio.file.Files
import java.nio.file.Paths
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("pde-testflow")

private val testflowOptionsRequiringValue = setOf(
  "--config",
  "--log",
  "--log-level",
  "--root",
  "--knwf",
  "--include",
  "--timeout",
  "--workflow-var",
  "--xml-result-dir",
  "--report",
  "--coverage",
  "--jacoco-agent"
)

private fun applyOsgiDebug(context: LaunchConfigContext, osgiDebug: Boolean): LaunchConfigContext {
  if (!osgiDebug) return context
  val programArgs = context.runtime.programArgs.toMutableList()
  if (!programArgs.contains("-debug")) {
    programArgs += "-debug"
  }
  return context.copy(runtime = context.runtime.copy(programArgs = programArgs))
}

fun testflowMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, testflowOptionsRequiringValue)
  val parser = ArgParser("pde testflow ${maturityTag("WIP")}")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch config (target)")
  val logLevelOpt by parser.option(ArgType.String, fullName = "log-level", description = "Logging level (error|warn|info|debug|trace)")
  val logFileOpt by parser.option(ArgType.String, fullName = "log", description = "Write runner stdout/stderr to a file")
  val verbose by parser.option(ArgType.Boolean, fullName = "verbose", shortName = "v", description = "Enable INFO logging").default(false)
  val debug by parser.option(ArgType.Boolean, fullName = "debug", description = "Enable JDWP for the runner JVM").default(false)
  val osgiDebug by parser.option(ArgType.Boolean, fullName = "osgiDebug", description = "Enable OSGi debug output (-debug)").default(false)
  val rootOpt by parser.option(ArgType.String, fullName = "root", description = "Local testflow root directory (repeatable; testflows under all roots run in one pass)").multiple()
  val knwfOpt by parser.option(ArgType.String, fullName = "knwf", description = "Local .knwf archive to unzip and run")
  val includeOpt by parser.option(ArgType.String, fullName = "include", description = "Only run testflows whose path matches this regex")
  val timeoutOpt by parser.option(ArgType.Int, fullName = "timeout", description = "Per-workflow timeout in seconds")
  val loadSaveLoad by parser.option(ArgType.Boolean, fullName = "load-save-load", description = "Load/save/load before execution").default(false)
  val streaming by parser.option(ArgType.Boolean, fullName = "streaming", description = "Enable streaming execution test").default(false)
  val views by parser.option(ArgType.Boolean, fullName = "views", description = "Open/close views during the test").default(false)
  val dialogs by parser.option(ArgType.Boolean, fullName = "dialogs", description = "Test node dialogs").default(false)
  val checkLogMessages by parser.option(ArgType.Boolean, fullName = "check-log-messages", description = "Assert on required/unexpected log messages").default(false)
  val ignoreNodeMessages by parser.option(ArgType.Boolean, fullName = "ignore-node-messages", description = "Ignore node warning messages").default(false)
  val deprecated by parser.option(ArgType.Boolean, fullName = "deprecated", description = "Report deprecated nodes as failures").default(false)
  val workflowVar by parser.option(ArgType.String, fullName = "workflow-var", description = "Define a flow variable: name,value,type").multiple()
  val xmlResultDirOpt by parser.option(ArgType.String, fullName = "xml-result-dir", description = "Directory for JUnit XML results (default: temp dir)")
  val reportValues by parser.option(ArgType.String, fullName = "report", description = "Reporting sink (teamcity, junit-xml:/path)").multiple()
  val coverageOpt by parser.option(ArgType.String, fullName = "coverage", description = "Record JaCoCo coverage (.exec) into this directory")
  val jacocoAgentOpt by parser.option(ArgType.String, fullName = "jacoco-agent", description = "Path to jacocoagent.jar (default: resolved from the target)")
  val dryRun by parser.option(ArgType.Boolean, fullName = "dry-run", description = "Resolve the launch but do not run it").default(false)
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
  parser.parse(normalizedArgs)
  configureLogging(resolveLogLevel(logLevelOpt, verbose, false), shouldUseColor())

  val reports = runCatching { reportValues.map(::parseReportTarget) }.getOrElse { ex ->
    logger.severe("Invalid --report value: ${ex.message}")
    return 2
  }

  val configFile = configFileOpt ?: configPos?.takeIf { looksLikeYamlFile(it) }
  val discovered = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  if (discovered == null) {
    logger.severe("Missing --config and no launch config discovered in current directory")
    return 2
  }
  if (configFile == null) {
    logger.info("Discovered launch config in ${discovered.toAbsolutePath()} and will use it.")
  }
  val loaded = LaunchConfigLoader.load(discovered)

  val knwf = knwfOpt
  val rootDirs: List<String> = if (knwf != null) {
    val knwfPath = Paths.get(knwf)
    if (!Files.isRegularFile(knwfPath)) {
      logger.severe("--knwf archive not found: $knwfPath")
      return 2
    }
    val dest = Files.createTempDirectory("pde-testflow-")
    logger.info("Unzipping $knwfPath into $dest")
    Files.newInputStream(knwfPath).use { unzipInto(it, dest) }
    listOf(dest.toString())
  } else if (rootOpt.isNotEmpty()) {
    rootOpt.map { r ->
      val root = Paths.get(r).toAbsolutePath()
      if (!Files.isDirectory(root)) {
        logger.severe("--root testflow folder not found or not a directory: $root")
        return 2
      }
      root.toString()
    }
  } else {
    logger.severe("No testflow source: pass --root <dir> or --knwf <file>")
    return 2
  }

  val resultDir =
    (xmlResultDirOpt?.let { Paths.get(it) } ?: Files.createTempDirectory("pde-testflow-results-")).toAbsolutePath()
  Files.createDirectories(resultDir)
  // pde-created temp dirs (unzipped flows + a temp results dir) can be large — remove them on exit.
  // Never the user's --root folder or an explicit --xml-result-dir.
  val tempDirs = buildList {
    if (knwf != null) addAll(rootDirs.map { Paths.get(it) })
    if (xmlResultDirOpt == null) add(resultDir)
  }
  if (tempDirs.isNotEmpty()) {
    Runtime.getRuntime().addShutdownHook(Thread { tempDirs.forEach(::deleteRecursivelyQuietly) })
  }
  val options = TestflowRunOptions(
    rootDirs = rootDirs,
    xmlResultDir = resultDir.toString(),
    include = includeOpt,
    timeoutSeconds = timeoutOpt,
    loadSaveLoad = loadSaveLoad,
    streaming = streaming,
    views = views,
    dialogs = dialogs,
    checkLogMessages = checkLogMessages,
    ignoreNodeMessages = ignoreNodeMessages,
    deprecated = deprecated,
    workflowVariables = workflowVar,
  )

  var context = applyTestflowRun(loaded, options).copy(jvmDebug = debug, jvmDebugRequiresPdeTestApp = false)
  // Run headless (no GUI windows / focus stealing) and, on macOS, give SWT the Cocoa main thread.
  context = context.copy(runtime = context.runtime.copy(vmArgs = context.runtime.vmArgs + testflowRuntimeVmArgs()))
  val coverageArg = try {
    coverageVmArgOrNull(coverageOpt, jacocoAgentOpt, "testflow")
  } catch (ex: IllegalStateException) {
    logger.severe(ex.message)
    return 2
  }
  if (coverageArg != null) {
    context = context.copy(runtime = context.runtime.copy(vmArgs = context.runtime.vmArgs + coverageArg))
    logger.info("Recording JaCoCo coverage into ${Paths.get(coverageOpt!!).toAbsolutePath().normalize()}")
  }
  context = applyOsgiDebug(context, osgiDebug)

  if (dryRun) {
    logger.info("Dry run — resolved testflow launch:")
    logger.info("  application: ${context.runtime.application}")
    logger.info("  programArgs: ${context.runtime.programArgs.joinToString(" ")}")
    logger.info("  vmArgs: ${context.runtime.vmArgs.joinToString(" ")}")
    logger.info("  results dir: $resultDir")
    return 0
  }

  // Bug A: load the result-model classes before the framework teardown invalidates the app classloader.
  preloadTestflowResultClasses()
  val logFile = logFileOpt?.let { Paths.get(it) }
  try {
    executeLaunch(context, showLogPathWhenDebugging = debug, logFile = logFile)
  } catch (ex: IllegalStateException) {
    logger.severe("Testflow runner failed to complete: ${ex.message}")
    return 2
  }

  // The workflows ran and any coverage .exec is already flushed; never let a summary-phase failure
  // (e.g. lazy class loading after the OSGi teardown — Bug A) crash an otherwise-successful run.
  val summary = try {
    summarizeTestflowResults(resultDir)
  } catch (ex: Throwable) {
    logger.severe(
      "Testflows executed and any coverage was recorded, but the result summary could not be parsed: " +
        "${ex.message}. Inspect results at $resultDir"
    )
    return 1
  }
  reports.forEach { target ->
    when (target) {
      is ReportTarget.JUnitXml -> {
        target.path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(target.path, mergeJUnitXml(resultDir))
        logger.info("Wrote JUnit XML report to ${target.path.toAbsolutePath()}")
      }
      ReportTarget.TeamCity -> teamCityTestflowMessages(resultDir).forEach { println(it) }
    }
  }
  return if (summary.passed) {
    logger.info("Testflows passed: ${summary.tests} test(s), 0 failures, 0 errors (results: $resultDir)")
    0
  } else {
    logger.severe(
      "Testflows did not pass: ${summary.tests} test(s), ${summary.failures} failure(s), " +
        "${summary.errors} error(s) (results: $resultDir)"
    )
    1
  }
}
