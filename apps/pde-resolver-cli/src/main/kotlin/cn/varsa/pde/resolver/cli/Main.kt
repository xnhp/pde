package cn.varsa.pde.resolver.cli

import cn.varsa.pde.remoterunner.CompositeRemoteTestListener
import cn.varsa.pde.remoterunner.JUnitXmlReporter
import cn.varsa.pde.remoterunner.LoggingRemoteTestListener
import cn.varsa.pde.remoterunner.PortAllocator
import cn.varsa.pde.remoterunner.RecordingRemoteTestListener
import cn.varsa.pde.remoterunner.RemoteTestListener
import cn.varsa.pde.remoterunner.RemoteTestProcessor
import cn.varsa.pde.remoterunner.RemoteTestSummary
import cn.varsa.pde.remoterunner.ReportTarget
import cn.varsa.pde.remoterunner.RunnerAnnouncement
import cn.varsa.pde.remoterunner.TeamCityRemoteTestListener
import cn.varsa.pde.remoterunner.parseForwardSpec
import cn.varsa.pde.remoterunner.parsePortRange
import cn.varsa.pde.remoterunner.parseReportTarget
import cn.varsa.pde.remoterunner.startForwarders
import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.TargetPlatformCache
import cn.varsa.pde.resolver.launch.*
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.compile.BundleCompileResult
import cn.varsa.pde.resolver.compile.CompileExecutor
import cn.varsa.pde.resolver.compile.CompileService
import cn.varsa.pde.resolver.compile.CompileSpec
import cn.varsa.pde.resolver.compile.rewritePlanWithCompiledOutputs
import cn.varsa.pde.resolver.product.ProductConfigurationParser
import cn.varsa.pde.resolver.cli.config.LaunchConfig
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.config.DEFAULT_STARTUP_LEVELS
import cn.varsa.pde.resolver.cli.config.LaunchLayout
import cn.varsa.pde.resolver.cli.config.LaunchLayoutResolver
import cn.varsa.pde.resolver.cli.config.TargetDefinitionStartupParser
import cn.varsa.pde.resolver.cli.config.TargetFileParser
import cn.varsa.pde.resolver.cli.config.TargetLaunchArgs
import cn.varsa.pde.resolver.cli.config.TestEntry
import cn.varsa.pde.resolver.cli.config.WorkspaceModuleResolver
import cn.varsa.pde.resolver.cli.config.WhitelistFileLoader
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.optional
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.Locale
import java.util.Properties
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.system.exitProcess
import cn.varsa.pde.resolver.workspace.WorkspaceBundleLoader
import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import cn.varsa.pde.resolver.launch.RuntimeLayoutWriter
import cn.varsa.pde.resolver.launch.LaunchContext

internal const val PDE_JUNIT_PLUGIN_TEST_APPLICATION = "org.eclipse.pde.junit.runtime.coretestapplication"
internal const val DEFAULT_TEST_DEBUG_PORT = 5005
private val jsonMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
private val logger: Logger = Logger.getLogger("pde-resolver-cli")
private val messageOnlyFormatter = object : Formatter() {
  override fun format(record: LogRecord): String {
    val message = formatMessage(record)
    val builder = StringBuilder()
    builder.append(record.level.name).append(": ").append(message).append('\n')
    record.thrown?.let { thrown ->
      val writer = java.io.StringWriter()
      val printer = java.io.PrintWriter(writer)
      thrown.printStackTrace(printer)
      printer.flush()
      builder.append(writer.toString())
    }
    return builder.toString()
  }
}
private fun configureLogging(level: Level) {
  logger.level = level
  val root = Logger.getLogger("")
  root.level = level
  root.handlers?.forEach { handler ->
    handler.level = level
    handler.formatter = messageOnlyFormatter
  }
}
private fun parseLogLevel(value: String?): Level = when (value?.lowercase()) {
  "error", "severe" -> Level.SEVERE
  "warn", "warning" -> Level.WARNING
  "info" -> Level.INFO
  "debug", "fine" -> Level.FINE
  "trace", "finest" -> Level.FINEST
  else -> Level.WARNING
}
private fun resolveLogLevel(logLevel: String?, verbose: Boolean, debug: Boolean): Level = when {
  logLevel != null -> parseLogLevel(logLevel)
  debug -> Level.FINE
  verbose -> Level.INFO
  else -> Level.WARNING
}
internal val launchOptionsRequiringValue = setOf(
  "--config",
  "--log",
  "--target-root",
  "-t",
  "--workspace",
  "-w",
  "--dev-prop",
  "--product",
  "--application",
  "--splash",
  "--framework",
  "--output"
)
internal val testOptionsRequiringValue = setOf(
  "--config",
  "--listen-host",
  "--listen-port",
  "--port-range",
  "--timeout",
  "--report",
  "--forward-log",
  "--include",
  "--exclude"
)
internal val compileOptionsRequiringValue = setOf(
  "--config",
  "--target-root",
  "-t",
  "--workspace",
  "-w",
  "--framework",
  "--results-json",
  "--output-root",
  "--bundles-info-out",
  "--runtime-out"
)

private fun looksLikeYamlFile(arg: String): Boolean {
  val lower = arg.lowercase()
  return lower.endsWith(".yaml") || lower.endsWith(".yml")
}

internal fun normalizeArgsWithImplicitConfig(
  rawArgs: Array<String>,
  optionsRequiringValue: Set<String>
): Array<String> {
  if (rawArgs.any { it == "--config" || it.startsWith("--config=") }) return rawArgs

  var skipNext = false
  rawArgs.forEachIndexed { index, arg ->
    if (skipNext) {
      skipNext = false
      return@forEachIndexed
    }

    if (arg.startsWith("-")) {
      val optionName = arg.substringBefore('=')
      if (optionsRequiringValue.contains(optionName) && !arg.contains('=')) {
        skipNext = true
      }
      return@forEachIndexed
    }

    if (looksLikeYamlFile(arg)) {
      val normalized = rawArgs.toMutableList()
      normalized.add(index, "--config")
      return normalized.toTypedArray()
    }
  }

  return rawArgs
}

data class PreparedLaunch(
  val command: List<String>,
  val planResult: LaunchPlanner.PlanResult,
  val layout: LaunchLayout
)

private fun logCommand(command: List<String>) {
  if (logger.isLoggable(Level.INFO)) {
    logger.log(Level.INFO, "Executing: {0}", command.joinToString(" "))
  }
}

fun launchMain(args: Array<String>) {
  if (args.isNotEmpty() && args[0] == "test") {
    val exit = testMain(args.drop(1).toTypedArray())
    exitProcess(exit)
  }
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, launchOptionsRequiringValue)

  val parser = ArgParser("pde-launch")
  val configFileOpt by parser.option(
    ArgType.String,
    fullName = "config",
    description = "YAML launch configuration (supports launches/tests)"
  )
  val logLevelOpt by parser.option(
    ArgType.String,
    fullName = "log-level",
    description = "Logging level (error|warn|info|debug|trace)"
  )
  val logFileOpt by parser.option(
    ArgType.String,
    fullName = "log",
    description = "Write application stdout/stderr to log file"
  )
  val verbose by parser.option(
    ArgType.Boolean,
    fullName = "verbose",
    shortName = "v",
    description = "Enable INFO logging"
  ).default(false)
  val debug by parser.option(
    ArgType.Boolean,
    fullName = "debug",
    description = "Enable DEBUG logging"
  ).default(false)
  val osgiDebug by parser.option(
    ArgType.Boolean,
    fullName = "osgiDebug",
    description = "Enable OSGi debug output (-debug)"
  ).default(false)
  val configPos by parser.argument(
    ArgType.String,
    description = "YAML launch configuration (positional)"
  ).optional()
  val launchPos by parser.argument(
    ArgType.String,
    description = "Launch name (optional, from launches entry)"
  ).optional()
  val dryRun by parser.option(ArgType.Boolean, fullName = "dry-run", description = "Parse configuration only").default(false)
  val targetRoots by parser.option(ArgType.String, fullName = "target-root", shortName = "t", description = "Target root (repeatable)").multiple()
  val workspaceRoots by parser.option(ArgType.String, fullName = "workspace", shortName = "w", description = "Workspace bundle directory (repeatable)").multiple()
  val devPropsOpt by parser.option(ArgType.String, fullName = "dev-prop", description = "Dev properties entry in form bsn=path1,path2").multiple()
  val product by parser.option(ArgType.String, fullName = "product")
  val application by parser.option(ArgType.String, fullName = "application")
  val splash by parser.option(ArgType.String, fullName = "splash")
  val framework by parser.option(ArgType.String, fullName = "framework", description = "Framework BSN").default("org.eclipse.osgi")
  val outputDirOpt by parser.option(ArgType.String, fullName = "output", shortName = "o", description = "Output directory for config.ini/bundles.info/dev.properties")

  parser.parse(normalizedArgs)
  configureLogging(resolveLogLevel(logLevelOpt, verbose, debug))

  val configPosValue = configPos
  val configFile = configFileOpt ?: configPosValue?.takeIf { looksLikeYamlFile(it) }
  val launchName = when {
    configPosValue != null && !looksLikeYamlFile(configPosValue) -> configPosValue
    launchPos != null -> launchPos
    else -> null
  }

  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()

  if (discoveredConfig != null) {
    if (configFile == null) {
      logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
    }
    val configContext = LaunchConfigLoader.load(discoveredConfig)
    val selected = selectLaunchConfig(configContext, launchName)
    if (selected == null) return
    val osgiContext = applyOsgiDebug(selected, osgiDebug)
    val targetPath = selected.config.targetFile
      ?.let { selected.baseDir.resolve(it).normalize() }
    val targetArgs = if (selected.config.inheritTargetArgs && targetPath != null) {
      runCatching { TargetFileParser.parse(targetPath) }
        .onFailure { logger.warning("Failed to parse target file args: ${it.message}") }
        .getOrNull()
    } else null
    if (selected.config.inheritTargetArgs && targetPath == null) {
      logger.warning("inheritTargetArgs=true but targetFile is not set; skipping target argument import.")
    }
    describeConfig(osgiContext, targetPath, targetArgs)
    if (dryRun) {
      logger.info("Dry run: validation only. Exiting.")
      return
    }
    val logFile = logFileOpt?.let { Paths.get(it) }
    executeLaunch(osgiContext, targetArgs, showDebugLogs = debug, logFile = logFile)
    return
  }

  if (targetRoots.isEmpty()) {
    logger.severe("No --target-root specified")
    return
  }

  val targetPaths = targetRoots.map { Paths.get(it) }
  val targetIndex = TargetPlatformCache.buildWithCache(targetPaths)

  val workspaceDescriptors = workspaceRoots.map { loadWorkspaceDescriptor(it) }
  val devProps = parseDevProperties(devPropsOpt)

  val environment = LaunchEnvironment(
    targetIndex = targetIndex,
    workspaceEntries = workspaceDescriptors,
    devProperties = devProps
  )
  val options = LauncherOptions(
    product = product,
    application = application,
    splashBSN = splash,
    frameworkBSN = framework,
    autoStartDefault = false
  )

  val planResult = LaunchPlanner.build(environment, options)
  val outDir = outputDirOpt?.let { Paths.get(it) }
  if (outDir != null) {
    writeOutputs(outDir, planResult.plan, planResult.context, options)
    logger.info("Wrote config.ini, bundles.info, dev.properties under $outDir")
  } else {
    logger.info("Framework: ${planResult.plan.framework?.bsn ?: "<none>"}; bundles=${planResult.plan.bundles.size}")
  }
}

private fun describeConfig(config: LaunchConfigContext, targetFile: Path?, targetArgs: TargetLaunchArgs?) {
  logger.info("Loaded launch config from ${config.file}")
  logger.info("  product: ${config.config.product?.takeUnless { it.isBlank() } ?: "<unspecified>"}")
  logger.info("  application: ${config.config.application ?: "<unspecified>"}")
  logger.info("  workspace modules: ${config.config.workspaceModules.size}")
  logger.info("  target file: ${targetFile ?: "<unspecified>"}")
  logger.info("  profile path: ${config.config.profilePath ?: "<unspecified>"}")
  logger.info("  target vm args: ${targetArgs?.vmArgs?.size ?: 0}")
  logger.info("  target program args: ${targetArgs?.programArgs?.size ?: 0}")
  if (config.jvmDebug) {
    val requiresTestApp = config.jvmDebugRequiresPdeTestApp
    val isTestApp = config.config.application?.equals(PDE_JUNIT_PLUGIN_TEST_APPLICATION, ignoreCase = true) == true
    val message = if (!requiresTestApp || isTestApp) {
      "enabled (JDWP on port $DEFAULT_TEST_DEBUG_PORT)"
    } else {
      "requested but only applies when application=$PDE_JUNIT_PLUGIN_TEST_APPLICATION"
    }
    logger.info("  jvm debug: $message")
  }
}

private fun selectLaunchConfig(
  context: LaunchConfigContext,
  launchName: String?
): LaunchConfigContext? {
  if (context.config.launches.isEmpty()) {
    logger.severe("No launches defined in ${context.file}. Add a 'launches' entry or pass a legacy launch.yaml.")
    return null
  }
  val selected = if (launchName == null) {
    context.config.launches.first()
  } else {
    val index = launchName.toIntOrNull()
    if (index != null) {
      if (index in 1..context.config.launches.size) {
        context.config.launches[index - 1]
      } else {
        logger.severe("Launch index $index out of range. Available launches: 1..${context.config.launches.size}")
        return null
      }
    } else {
      context.config.launches.firstOrNull { it.name == launchName }
    }
  }
  if (selected == null) {
    val available = context.config.launches.joinToString { it.name }
    logger.severe("Launch '$launchName' not found in ${context.file}. Available launches: $available")
    return null
  }
  val patched = context.config.copy(
    product = selected.product ?: context.config.product,
    application = selected.application ?: context.config.application,
    splash = selected.splash,
    env = mergeEnv(context.config.env, selected.env),
    additionalVmArgs = selected.vmArgs,
    programArgs = selected.programArgs
  )
  if (!context.config.splash.isNullOrBlank()) {
    logger.warning("Top-level 'splash' is no longer supported; use launches[].splash instead.")
  }
  if (launchName == null) {
    logger.info("Using default launch '${selected.name}'.")
  } else {
    logger.info("Using launch '${selected.name}'.")
  }
  return context.copy(
    config = patched,
    jvmDebug = selected.debug,
    jvmDebugRequiresPdeTestApp = false
  )
}

private fun selectTestConfig(
  context: LaunchConfigContext,
  testName: String?
): LaunchConfigContext? {
  if (context.config.tests.isEmpty()) {
    logger.severe("No tests defined in ${context.file}. Add a 'tests' entry or pass a legacy launch.yaml.")
    return null
  }
  val selected = if (testName == null) {
    context.config.tests.first()
  } else {
    val index = testName.toIntOrNull()
    if (index != null) {
      if (index in 1..context.config.tests.size) {
        context.config.tests[index - 1]
      } else {
        logger.severe("Test index $index out of range. Available tests: 1..${context.config.tests.size}")
        return null
      }
    } else {
    val exactMatches = context.config.tests.filter { entry ->
      entry.name == testName || entry.className == testName || entry.testPluginName == testName
    }
    if (exactMatches.size > 1) {
      val available = exactMatches.joinToString { entry ->
        entry.name ?: entry.className ?: entry.testPluginName ?: "<unnamed>"
      }
      logger.severe("Test '$testName' matches multiple entries: $available. Use a unique name/classname.")
      return null
    }
    if (exactMatches.size == 1) {
      exactMatches.first()
    } else {
      val substringMatches = context.config.tests.filter { entry ->
        entry.className?.contains(testName) == true
      }
      if (substringMatches.size > 1) {
        val available = substringMatches.joinToString { entry ->
          entry.className ?: entry.name ?: entry.testPluginName ?: "<unnamed>"
        }
        logger.severe("Test '$testName' matches multiple classnames: $available. Use the full classname.")
        return null
      }
      substringMatches.singleOrNull()
    }
    }
  }
  if (selected == null) {
    val available = context.config.tests.joinToString { entry ->
      entry.name ?: entry.className ?: entry.testPluginName ?: "<unnamed>"
    }
    logger.severe("Test '$testName' not found in ${context.file}. Available tests: $available")
    return null
  }
  return applyTestEntry(context, selected, testName, logSelection = true)
}

private fun testLabel(entry: TestEntry): String =
  entry.name ?: entry.className ?: entry.testPluginName ?: "<unnamed>"

private fun applyTestEntry(
  context: LaunchConfigContext,
  selected: TestEntry,
  testName: String?,
  logSelection: Boolean
): LaunchConfigContext {
  val programArgs = context.config.programArgs.toMutableList()
  programArgs.addAll(selected.programArgs)
  if (selected.testPluginName != null && "-testpluginname" !in programArgs) {
    programArgs += listOf("-testpluginname", selected.testPluginName)
  }
  if (selected.className != null && "-classname" !in programArgs) {
    programArgs += listOf("-classname", selected.className)
  }
  val vmArgs = context.config.additionalVmArgs + selected.vmArgs
  val patched = context.config.copy(
    additionalVmArgs = vmArgs,
    programArgs = programArgs,
    env = mergeEnv(context.config.env, selected.env)
  )
  val withDebug = context.copy(
    config = patched,
    jvmDebug = selected.debug,
    jvmDebugRequiresPdeTestApp = true
  )
  if (logSelection) {
    val label = testLabel(selected)
    if (testName == null) {
      logger.info("Using default test '$label'.")
    } else {
      logger.info("Using test '$label'.")
    }
  }
  return withDebug
}

private fun executeLaunch(
  context: LaunchConfigContext,
  targetArgs: TargetLaunchArgs?,
  showDebugLogs: Boolean,
  logFile: Path?
) {
  val prepared = prepareLaunch(context, targetArgs)
  logPlanSummary(prepared.planResult)
  if (showDebugLogs) {
    val logPath = prepared.layout.dataDir.resolve(".metadata").resolve(".log").toAbsolutePath().normalize()
    logger.info("OSGi log path: $logPath")
  }
  logCommand(prepared.command)
  val processBuilder = ProcessBuilder(prepared.command)
  val outputLog = logFile?.toAbsolutePath()?.normalize()
  if (outputLog != null) {
    outputLog.parent?.let { Files.createDirectories(it) }
    processBuilder.redirectErrorStream(true)
    processBuilder.redirectOutput(outputLog.toFile())
  } else {
    processBuilder.redirectErrorStream(true)
    processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
  }
  if (context.config.env.isNotEmpty()) {
    val env = processBuilder.environment()
    context.config.env.forEach { (key, value) ->
      env[key] = value
    }
  }
  val process = processBuilder
    .directory(prepared.layout.workDir.toFile())
    .inheritIO()
    .start()
  val exit = process.waitFor()
  if (exit != 0) error("Launcher exited with code $exit")
}

private fun logPlanSummary(planResult: LaunchPlanner.PlanResult) {
  logger.info("Launch plan built:")
  logger.info("  bundles: ${planResult.plan.bundles.size}")
  logger.info("  workspace bundles: ${planResult.plan.bundles.count { it.isWorkspace }}")
  logger.info("  problems: ${planResult.problemsByScope.values.sumOf { it.size }}")
  if (planResult.problemsByScope.isNotEmpty()) {
    val details = buildString {
      planResult.problemsByScope.forEach { (scope, probs) ->
        appendLine("$scope:")
        probs.forEach { p ->
          appendLine("  - [${p.type}] ${p.symbol}${p.range?.let { " $it" } ?: ""}: ${p.message}")
        }
      }
    }
    logger.warning(
      ("Launch plan has unresolved bundles/dependencies; continuing anyway.\n$details").trim()
    )
  }
}

private fun applyOsgiDebug(context: LaunchConfigContext, osgiDebug: Boolean): LaunchConfigContext {
  if (!osgiDebug) return context
  val programArgs = context.config.programArgs.toMutableList()
  if (!programArgs.contains("-debug")) {
    programArgs += "-debug"
  }
  return context.copy(config = context.config.copy(programArgs = programArgs))
}

internal fun mergeEnv(base: Map<String, String>, overrides: Map<String, String>): Map<String, String> {
  if (overrides.isEmpty()) return base
  if (base.isEmpty()) return overrides
  return base + overrides
}

private fun prepareLaunch(
  context: LaunchConfigContext,
  targetArgs: TargetLaunchArgs?,
  extraProgramArgs: List<String> = emptyList()
): PreparedLaunch {
  val workspaceInputs = WorkspaceModuleResolver.resolve(context)
  val profilePath = context.config.profilePath?.let { context.baseDir.resolve(it).normalize() }
    ?: error("profilePath missing in YAML config")
  if (!Files.exists(profilePath)) {
    error("profilePath does not exist: $profilePath (check launch.yaml or export the profile)")
  }
  val targetIndex = TargetPlatformCache.buildWithCache(listOf(profilePath))
  val layoutResolution = LaunchLayoutResolver.resolve(context)
  val layout = layoutResolution.layout
  LaunchLayoutResolver.cleanIfRequested(layout, context.config.cleanRuntime)
  val targetDefinitionStartupLevels = loadTargetDefinitionStartupLevels(context)
  val productStartupLevels = loadProductStartupLevels(context)
  val requestedStartupLevels = if (context.config.startupLevels.isNotEmpty()) {
    context.config.startupLevels
  } else {
    DEFAULT_STARTUP_LEVELS
  }
  val combinedStartup = DEFAULT_STARTUP_LEVELS +
    targetDefinitionStartupLevels +
    productStartupLevels +
    requestedStartupLevels
  val resolverWhitelist = resolveWhitelist(context)
  val hasWorkspaceModules = workspaceInputs.descriptors.isNotEmpty()
  val workspaceEntries = if (hasWorkspaceModules) {
    workspaceInputs.descriptors
  } else {
    listOfNotNull(syntheticEntry(context, targetIndex))
  }
  val supplementalBundles = targetIndex.bundlesByBsn().flatMap { (bsn, nav) ->
    nav.entries
      .map { it.key to it.value }
      .filter { (_, bundle) -> bundle.manifest.eclipseSourceBundle == null }
      .map { (version, bundle) ->
        LaunchEnvironment.SupplementalBundle(bsn, version, bundle.location, isWorkspace = false)
      }
  }
  val devProperties = workspaceInputs.devProperties
  val env = LaunchEnvironment(
    targetIndex = targetIndex,
    workspaceEntries = workspaceEntries,
    devProperties = devProperties,
    libraryBundles = supplementalBundles,
    resolverOptions = ResolveOptions(
      whitelistPrefixes = resolverWhitelist,
      preferWorkspace = hasWorkspaceModules,
      includeHostsForFragments = true
    ),
    requiredStartupBundles = combinedStartup.keys,
    startupLevels = combinedStartup,
    autoStartBundles = combinedStartup.keys.associateWith { true }
  )
  val productId = context.config.product?.takeUnless { it.isBlank() }
  val options = LauncherOptions(
    product = productId,
    application = context.config.application,
    splashBSN = context.config.splash,
    autoStartDefault = false
  )
  val planResult = LaunchPlanner.build(env, options)
  val fallbackConfig = loadExistingConfig(profilePath)
  val extraProperties = buildMap {
    val splash = context.config.splash?.takeIf { it.isNotBlank() }
    if (splash != null) {
      val bundle = targetIndex.get(splash)
        ?: targetIndex.get(splash.substringBeforeLast('.', missingDelimiterValue = splash))
      if (bundle != null) {
        put("osgi.splashPath", bundle.location.toUri().toString())
      } else {
        logger.warning("Splash bundle '$splash' not found in target platform; osgi.splashPath will be omitted.")
      }
    }
  }
  writeLaunchArtifacts(context, layout, planResult, options, profilePath, fallbackConfig, extraProperties)
  val launcherJar = resolveLauncherJar(targetIndex)
  val command = assembleCommand(context, layout, targetArgs, planResult, launcherJar, extraProgramArgs)
  return PreparedLaunch(command, planResult, layout)
}

private fun assembleCommand(
  context: LaunchConfigContext,
  layout: LaunchLayout,
  targetArgs: TargetLaunchArgs?,
  planResult: LaunchPlanner.PlanResult,
  launcherJar: Path,
  extraProgramArgs: List<String> = emptyList()
): List<String> {
  val javaBin = System.getenv("JAVA_HOME")?.let { Path.of(it, "bin", "java").toString() } ?: "java"
  val configVmArgs = expandArgs(context.config.additionalVmArgs)
  val configProgramArgs = expandArgs(context.config.programArgs)
  val vmArgs = mutableListOf<String>().apply {
    addAll(targetArgs?.vmArgs ?: emptyList())
    addAll(configVmArgs)
    addAll(buildDebugVmArgs(context, this))
  }
  val programArgs = mutableListOf<String>().apply {
    addAll(targetArgs?.programArgs ?: emptyList())
    addAll(configProgramArgs)
    addAll(extraProgramArgs)
  }
  val stdArgs = listOf(
    "-clean",
    "-os", "linux",
    "-ws", "gtk",
    "-arch", "x86_64",
    "-nl", "en_GB"
  )
  val launchArgs = mutableListOf<String>().apply {
    addAll(programArgs)
    addAll(stdArgs)
    add("-name")
    add("Eclipse")
    context.config.splash?.takeIf { it.isNotBlank() }?.let {
      add("-showsplash")
      add(it)
    }
    add("-application")
    add(context.config.application ?: error("application missing"))
    context.config.product?.takeUnless { it.isBlank() }?.let {
      add("-product")
      add(it)
    }
    add("-data")
    add(layout.dataDir.toString())
    add("-configuration")
    add(layout.configDir.toUri().toString())
    add("-dev")
    add(layout.devPropertiesFile.toUri().toString())
    add("-consoleLog")
  }
  return buildList {
    add(javaBin)
    addAll(vmArgs)
    add("-classpath")
    add(launcherJar.toString())
    add("org.eclipse.equinox.launcher.Main")
    addAll(launchArgs)
  }
}

internal fun buildDebugVmArgs(context: LaunchConfigContext, existingVmArgs: List<String>): List<String> {
  if (!context.jvmDebug) return emptyList()
  if (context.jvmDebugRequiresPdeTestApp &&
    !context.config.application.equals(PDE_JUNIT_PLUGIN_TEST_APPLICATION, ignoreCase = true)
  ) return emptyList()
  if (existingVmArgs.any { it.contains("jdwp", ignoreCase = true) }) return emptyList()
  return listOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:$DEFAULT_TEST_DEBUG_PORT")
}

private fun expandArgs(raw: List<String>): List<String> = raw.flatMap { tokenizeArgString(it) }

internal fun tokenizeArgString(input: String): List<String> {
  if (input.isBlank()) return emptyList()
  val tokens = mutableListOf<String>()
  val current = StringBuilder()
  var quote: Char? = null
  var index = 0
  while (index < input.length) {
    val ch = input[index]
    when {
      quote == null && ch.isWhitespace() -> {
        if (current.isNotEmpty()) {
          tokens += current.toString()
          current.setLength(0)
        }
      }
      ch == '\\' && index + 1 < input.length -> {
        current.append(input[index + 1])
        index++
      }
      ch == '"' || ch == '\'' -> {
        if (quote == null) {
          quote = ch
        } else if (quote == ch) {
          quote = null
        } else {
          current.append(ch)
        }
      }
      else -> current.append(ch)
    }
    index++
  }
  if (current.isNotEmpty()) {
    tokens += current.toString()
  }
  return tokens
}

private fun resolveLauncherJar(targetIndex: cn.varsa.pde.resolver.index.TargetPlatformIndex): Path {
  return targetIndex.get("org.eclipse.equinox.launcher")?.location
    ?: error("Target platform lacks org.eclipse.equinox.launcher bundle")
}

private fun syntheticEntry(
  context: LaunchConfigContext,
  targetIndex: cn.varsa.pde.resolver.index.TargetPlatformIndex
): WorkspaceBundleDescriptor? {
  val bsn = context.config.product ?: context.config.application ?: return null
  val resolved = targetIndex.get(bsn)
    ?: targetIndex.get(bsn.substringBeforeLast('.', missingDelimiterValue = bsn))
    ?: error("Target platform does not contain bundle $bsn required for product/application")
  return WorkspaceBundleDescriptor(
    path = resolved.location,
    manifest = resolved.manifest,
    classPathEntries = listOf(resolved.location)
  )
}

private fun writeLaunchArtifacts(
  context: LaunchConfigContext,
  layout: LaunchLayout,
  planResult: LaunchPlanner.PlanResult,
  options: LauncherOptions,
  installArea: Path,
  fallbackConfig: Properties,
  extraProperties: Map<String, String>
) {
  val defaults = RuntimeLayoutWriter.Defaults(
    installArea = installArea,
    instanceArea = layout.dataDir,
    fallbackConfig = fallbackConfig,
    extraProperties = extraProperties
  )
  RuntimeLayoutWriter.write(layout.asRuntimeLayout(), planResult.plan, planResult.context, options, defaults)
}

private fun loadWorkspaceDescriptor(root: String): WorkspaceBundleDescriptor {
  val path = Paths.get(root)
  val manifestFile = path.resolve("META-INF/MANIFEST.MF").toFile()
  val manifest = manifestFile.inputStream().use { BundleManifest.parse(java.util.jar.Manifest(it)) }
  return WorkspaceBundleDescriptor(path, manifest)
}

private fun loadProductStartupLevels(context: LaunchConfigContext): Map<String, Int> {
  if (context.config.productFiles.isEmpty()) return emptyMap()
  val productId = context.config.product
  context.config.productFiles.forEach { entry ->
    val path = context.baseDir.resolve(entry).normalize()
    if (Files.exists(path)) {
      val parsed = ProductConfigurationParser.parseAutoStartPlugins(path, productId)
      if (parsed != null) return parsed
    }
  }
  return emptyMap()
}

private fun loadTargetDefinitionStartupLevels(context: LaunchConfigContext): Map<String, Int> {
  val configured = context.config.startupLevelsFile?.let { context.baseDir.resolve(it).normalize() }
  val defaultYaml = context.baseDir.resolve("startupLevels.yaml")
  val defaultYml = context.baseDir.resolve("startupLevels.yml")
  val legacyXml = context.baseDir.resolve("startupLevels.xml")
  val candidates = listOfNotNull(configured, defaultYaml, defaultYml, legacyXml).distinct()
  candidates.forEach { candidate ->
    TargetDefinitionStartupParser.parse(candidate)?.let { return it }
  }
  return emptyMap()
}

private fun resolveWhitelist(context: LaunchConfigContext): Set<String> {
  val defaults = context.config.whitelist.toSet()
  val fileEntries = loadWhitelistOverrides(context)
  val combined = when {
    fileEntries != null -> fileEntries + defaults
    else -> defaults
  }
  return if (combined.isNotEmpty()) combined else DEFAULT_WHITELIST
}

private fun loadWhitelistOverrides(context: LaunchConfigContext): Set<String>? {
  val configured = context.config.whitelistFile?.let { context.baseDir.resolve(it).normalize() }
  val defaultFile = context.baseDir.resolve("whitelist.txt")
  val candidates = listOfNotNull(configured, defaultFile)
  candidates.forEach { candidate ->
    WhitelistFileLoader.load(candidate)?.let { return it }
  }
  return null
}

private val DEFAULT_WHITELIST = setOf(
  "org.eclipse.jdt.annotation",
  "org.eclipse.io",
  "org.eclipse.swt"
)

private fun loadExistingConfig(profilePath: Path): Properties {
  val props = Properties()
  val configPath = profilePath.resolve("configuration").resolve("config.ini")
  if (Files.exists(configPath)) {
    configPath.toFile().inputStream().use { props.load(it) }
  }
  return props
}

private fun parseDevProperties(entries: List<String>): Map<String, List<String>> = entries
  .mapNotNull { raw ->
    val parts = raw.split('=')
    if (parts.size != 2) null
    else parts[0] to parts[1].split(',').filter { it.isNotBlank() }
  }
  .toMap()

internal fun discoverConfigFile(baseDir: Path = Paths.get("").toAbsolutePath()): Path? {
  val candidates = listOf(
    "config.yaml",
    "config.yml",
    "launch.yaml",
    "launch.yml",
    "pde-launch.yaml",
    "pde-launch.yml"
  )
  return candidates
    .map { baseDir.resolve(it) }
    .firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
}

private fun applyTestDefaults(context: LaunchConfigContext): LaunchConfigContext {
  val programArgs = context.config.programArgs.toMutableList()
  fun hasFlag(flag: String): Boolean = programArgs.indexOf(flag) != -1

  if (!hasFlag("-testLoaderClass")) {
    programArgs += listOf("-testLoaderClass", "org.eclipse.jdt.internal.junit5.runner.JUnit5TestLoader")
  }
  if (!hasFlag("-loaderpluginname")) {
    programArgs += listOf("-loaderpluginname", "org.eclipse.jdt.junit5.runtime")
  }

  val patchedConfig = context.config.copy(
    application = context.config.application?.takeUnless { it.isBlank() } ?: PDE_JUNIT_PLUGIN_TEST_APPLICATION,
    programArgs = programArgs
  )
  return context.copy(config = patchedConfig)
}

private fun resolveTargetArgs(context: LaunchConfigContext): TargetLaunchArgs? {
  val targetPath = context.config.targetFile
    ?.let { context.baseDir.resolve(it).normalize() }
  val targetArgs = if (context.config.inheritTargetArgs && targetPath != null) {
    runCatching { TargetFileParser.parse(targetPath) }
      .onFailure { logger.warning("Failed to parse target file args: ${it.message}") }
      .getOrNull()
  } else null
  if (context.config.inheritTargetArgs && targetPath == null) {
    logger.warning("inheritTargetArgs=true but targetFile is not set; skipping target argument import.")
  }
  return targetArgs
}

private fun runTestLaunch(
  configContext: LaunchConfigContext,
  targetArgs: TargetLaunchArgs?,
  listenHost: String,
  listenPort: Int?,
  portRangeSpec: String?,
  timeoutSeconds: Int,
  reports: List<ReportTarget>,
  forwardSpecs: List<cn.varsa.pde.remoterunner.ForwardLogSpec>,
  includes: List<Regex>,
  excludes: List<Regex>,
  quiet: Boolean,
  noColor: Boolean,
  debug: Boolean
): Int {
  val allocator = PortAllocator(listenHost, listenPort, parsePortRange(portRangeSpec))
  val server = try {
    allocator.bind()
  } catch (ex: Exception) {
    logger.severe("Failed to bind server socket: ${ex.message}")
    return 2
  }
  server.soTimeout = timeoutSeconds.coerceAtLeast(1) * 1000

  val announcement = RunnerAnnouncement(
    host = listenHost,
    port = server.localPort,
    timeoutSeconds = timeoutSeconds,
    instructions = listOf(
      "Add '-port ${server.localPort}' to PDE launch program arguments.",
      "Example: pde-launch --programArg \"-port ${server.localPort}\""
    ),
    issuedAt = Instant.now().toString()
  )
  logger.info(jsonMapper.writeValueAsString(announcement))
  logger.info("Listening on ${announcement.host}:${announcement.port}")
  logger.info("Waiting up to ${timeoutSeconds}s for RemoteTestRunner connection...")

  val prepared = runCatching { prepareLaunch(configContext, targetArgs, listOf("-port", server.localPort.toString())) }
    .getOrElse { error ->
      server.close()
      logger.severe("Failed to build launch plan: ${error.message}")
      return 2
    }

  logPlanSummary(prepared.planResult)
  if (debug) {
    val logPath = prepared.layout.dataDir.resolve(".metadata").resolve(".log").toAbsolutePath().normalize()
    logger.info("OSGi log path: $logPath")
  }
  logCommand(prepared.command)
  val process = ProcessBuilder(prepared.command)
    .directory(prepared.layout.workDir.toFile())
    .inheritIO()
    .start()

  val client = try {
    server.accept()
  } catch (timeout: SocketTimeoutException) {
    logger.severe("Timed out waiting for PDE connection after ${timeoutSeconds}s")
    server.close()
    process.destroy()
    return 3
  }
  logger.info("Connection established from ${client.inetAddress.hostAddress}:${client.port}")

  startForwarders(forwardSpecs)

  val listeners = mutableListOf<RemoteTestListener>()
  if (!quiet) {
    val useColor = !noColor && System.console() != null
    listeners += LoggingRemoteTestListener(System.out, includes, excludes, color = useColor)
  }
  val recorder = RecordingRemoteTestListener()
  listeners += recorder
  if (reports.any { it is ReportTarget.TeamCity }) {
    listeners += TeamCityRemoteTestListener(System.out)
  }
  val composite = CompositeRemoteTestListener(listeners)
  val processor = RemoteTestProcessor(composite)

  val summary: RemoteTestSummary
  client.getInputStream().bufferedReader(StandardCharsets.UTF_8).use { reader ->
    summary = processor.process(reader)
  }
  client.close()
  server.close()

  reports.filterIsInstance<ReportTarget.JUnitXml>().forEach { target ->
    try {
      JUnitXmlReporter(target.path).write(summary, recorder.results)
      logger.info("Wrote JUnit report to ${target.path}")
    } catch (ex: Exception) {
      logger.severe("Failed to write JUnit report: ${ex.message}")
    }
  }

  val processExit = process.waitFor()
  val testExit = if (summary.failures == 0 && summary.errors == 0 && !summary.stopped) 0 else 1
  val exitCode = when {
    testExit != 0 -> testExit
    processExit != 0 -> processExit
    else -> 0
  }
  if (exitCode != 0) {
    logger.severe("Remote tests reported failures=${summary.failures} errors=${summary.errors} stopped=${summary.stopped}; processExit=$processExit")
  }
  return exitCode
}

private fun writeOutputs(dir: Path, plan: LauncherPlan, ctx: LaunchContext, opts: LauncherOptions) {
  val layout = RuntimeLayoutWriter.LayoutPaths.fromConfigDir(dir)
  val defaults = RuntimeLayoutWriter.Defaults(
    installArea = dir,
    instanceArea = dir
  )
  RuntimeLayoutWriter.write(layout, plan, ctx, opts, defaults)
}

private fun testMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, testOptionsRequiringValue)

  val parser = ArgParser("pde-launch test")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration")
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
  val launchPos by parser.argument(ArgType.String, description = "Test name (optional, ignored with --all)").optional()
  val logLevelOpt by parser.option(
    ArgType.String,
    fullName = "log-level",
    description = "Logging level (error|warn|info|debug|trace)"
  )
  val verbose by parser.option(
    ArgType.Boolean,
    fullName = "verbose",
    shortName = "v",
    description = "Enable INFO logging"
  ).default(false)
  val debug by parser.option(
    ArgType.Boolean,
    fullName = "debug",
    description = "Enable DEBUG logging"
  ).default(false)
  val osgiDebug by parser.option(
    ArgType.Boolean,
    fullName = "osgiDebug",
    description = "Enable OSGi debug output (-debug)"
  ).default(false)
  val debugJvm by parser.option(
    ArgType.Boolean,
    fullName = "debugJVM",
    description = "Enable JDWP for test JVM (equivalent to tests[].debug=true)"
  ).default(false)
  val runAll by parser.option(
    ArgType.Boolean,
    fullName = "all",
    description = "Run all tests defined in the config in sequence (ignores test name)"
  ).default(false)
  val listenHost by parser.option(ArgType.String, fullName = "listen-host", description = "Host to bind").default("127.0.0.1")
  val listenPort by parser.option(ArgType.Int, fullName = "listen-port", description = "Fixed port to bind")
  val portRangeSpec by parser.option(ArgType.String, fullName = "port-range", description = "Inclusive port range start-end")
  val timeoutSeconds by parser.option(ArgType.Int, fullName = "timeout", description = "Seconds to wait for PDE connection").default(180)
  val reportValues by parser.option(ArgType.String, fullName = "report", description = "Reporting sink (teamcity, junit-xml:/path)").multiple()
  val forwardValues by parser.option(ArgType.String, fullName = "forward-log", description = "Forward log in form label=path").multiple()
  val includePatterns by parser.option(ArgType.String, fullName = "include", description = "Regex filter to include tests").multiple()
  val excludePatterns by parser.option(ArgType.String, fullName = "exclude", description = "Regex filter to exclude tests").multiple()
  val quiet by parser.option(ArgType.Boolean, fullName = "quiet", description = "Suppress console test logs").default(false)
  val noColor by parser.option(ArgType.Boolean, fullName = "no-color", description = "Disable ANSI colors in console logs").default(false)
  parser.parse(normalizedArgs)
  configureLogging(resolveLogLevel(logLevelOpt, verbose, debug))

  val configPosValue = configPos
  val configFile = configFileOpt ?: configPosValue?.takeIf { looksLikeYamlFile(it) }
  val testName = when {
    configPosValue != null && !looksLikeYamlFile(configPosValue) -> configPosValue
    launchPos != null -> launchPos
    else -> null
  }

  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  if (discoveredConfig == null) {
    logger.severe("Missing --config and no launch config discovered in current directory")
    return 2
  }
  if (configFile == null) {
    logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
  }

  val loaded = LaunchConfigLoader.load(discoveredConfig)
  if (runAll && testName != null) {
    logger.warning("Ignoring test name '$testName' because --all was specified.")
  }

  val reports = runCatching { reportValues.map(::parseReportTarget) }
    .getOrElse { error ->
      logger.severe("Invalid --report value: ${error.message}")
      return 2
    }
  val forwardSpecs = runCatching { forwardValues.map(::parseForwardSpec) }
    .getOrElse { error ->
      logger.severe("Invalid --forward-log value: ${error.message}")
      return 2
    }
  val includes = runCatching { includePatterns.map(::Regex) }
    .getOrElse { error ->
      logger.severe("Invalid --include regex: ${error.message}")
      return 2
    }
  val excludes = runCatching { excludePatterns.map(::Regex) }
    .getOrElse { error ->
      logger.severe("Invalid --exclude regex: ${error.message}")
      return 2
    }

  if (runAll) {
    if (loaded.config.tests.isEmpty()) {
      logger.severe("No tests defined in ${loaded.file}. Add a 'tests' entry or pass a legacy launch.yaml.")
      return 2
    }
    var failures = 0
    loaded.config.tests.forEachIndexed { index, entry ->
      val label = testLabel(entry)
      logger.info("Running test ${index + 1}/${loaded.config.tests.size}: $label")
      val configured = applyTestEntry(loaded, entry, testName = null, logSelection = false)
      val configContext = applyTestDefaults(configured).let { ctx ->
        if (debugJvm) ctx.copy(jvmDebug = true, jvmDebugRequiresPdeTestApp = true) else ctx
      }.let { applyOsgiDebug(it, osgiDebug) }
      val targetArgs = resolveTargetArgs(configContext)
      val exitCode = runTestLaunch(
        configContext = configContext,
        targetArgs = targetArgs,
        listenHost = listenHost,
        listenPort = listenPort,
        portRangeSpec = portRangeSpec,
        timeoutSeconds = timeoutSeconds,
        reports = reports,
        forwardSpecs = forwardSpecs,
        includes = includes,
        excludes = excludes,
        quiet = quiet,
        noColor = noColor,
        debug = debug
      )
      if (exitCode != 0) {
        failures += 1
        logger.severe("Test '$label' failed with exit code $exitCode")
      }
    }
    if (failures > 0) {
      logger.severe("Completed ${loaded.config.tests.size} tests with $failures failures.")
      return 1
    }
    logger.info("Completed ${loaded.config.tests.size} tests successfully.")
    return 0
  }

  val selected = selectTestConfig(loaded, testName)
  if (selected == null) return 2
  val configContext = applyTestDefaults(selected).let { ctx ->
    if (debugJvm) ctx.copy(jvmDebug = true, jvmDebugRequiresPdeTestApp = true) else ctx
  }.let { applyOsgiDebug(it, osgiDebug) }
  val targetArgs = resolveTargetArgs(configContext)
  return runTestLaunch(
    configContext = configContext,
    targetArgs = targetArgs,
    listenHost = listenHost,
    listenPort = listenPort,
    portRangeSpec = portRangeSpec,
    timeoutSeconds = timeoutSeconds,
    reports = reports,
    forwardSpecs = forwardSpecs,
    includes = includes,
    excludes = excludes,
    quiet = quiet,
    noColor = noColor,
    debug = debug
  )
}

internal fun compileMain(args: Array<String>) {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, compileOptionsRequiringValue)
  val parser = ArgParser("pde-compile")
  val configFileOpt by parser.option(
    ArgType.String,
    fullName = "config",
    description = "YAML launch configuration"
  )
  val targetRoots by parser.option(ArgType.String, fullName = "target-root", shortName = "t", description = "Target root/profile (repeatable)").multiple()
  val workspaceRoots by parser.option(ArgType.String, fullName = "workspace", shortName = "w", description = "Workspace bundle directory (repeatable)").multiple()
  val framework by parser.option(ArgType.String, fullName = "framework", description = "Framework BSN").default("org.eclipse.osgi")
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit compile specs as JSON").default(false)
  val execute by parser.option(
    ArgType.Boolean,
    fullName = "execute",
    description = "Run ECJ compilation (default when using a launch config)"
  ).default(false)
  val fullRebuild by parser.option(
    ArgType.Boolean,
    fullName = "full-rebuild",
    description = "Force full rebuild of all workspace bundles (skip incremental cache)"
  ).default(false)
  val debugInfo by parser.option(ArgType.Boolean, fullName = "debug", description = "Emit debug info (lines/vars/source)").default(false)
  val resultsJson by parser.option(ArgType.String, fullName = "results-json", description = "Write compile results (when --execute) to JSON file")
  val outputRoot by parser.option(ArgType.String, fullName = "output-root", description = "Override workspace bundle output dir (relative to module root, e.g., bin)")
  val bundlesInfoOut by parser.option(ArgType.String, fullName = "bundles-info-out", description = "Write bundles.info reflecting compiled workspace outputs")
  val runtimeOut by parser.option(ArgType.String, fullName = "runtime-out", description = "Write config.ini/dev.properties/bundles.info for compiled outputs under this directory")
  val configPos by parser.argument(
    ArgType.String,
    description = "YAML launch configuration (positional)"
  ).optional()
  parser.parse(normalizedArgs)
  configureLogging(Level.INFO)

  val configFile = configFileOpt ?: configPos?.takeIf { looksLikeYamlFile(it) }
  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  val runCompile = execute || discoveredConfig != null

  if (discoveredConfig != null) {
    if (configFile == null) {
      logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
    }
    val configContext = LaunchConfigLoader.load(discoveredConfig)
    val profilePath = configContext.config.profilePath?.let { configContext.baseDir.resolve(it).normalize() }
    if (profilePath == null) {
      logger.severe("profilePath missing in YAML config; cannot derive target platform for compile.")
      return
    }
    if (!Files.exists(profilePath)) {
      logger.severe("profilePath does not exist: $profilePath (check launch.yaml or export the profile)")
      return
    }

    val targetIndex = TargetPlatformCache.buildWithCache(listOf(profilePath))
    val workspaceInputs = WorkspaceModuleResolver.resolve(configContext, allowMissingClasses = true)
    val hasWorkspaceModules = workspaceInputs.descriptors.isNotEmpty()
    val workspaceEntries = if (hasWorkspaceModules) {
      workspaceInputs.descriptors
    } else {
      listOfNotNull(syntheticEntry(configContext, targetIndex))
    }
    val env = LaunchEnvironment(
      targetIndex = targetIndex,
      workspaceEntries = workspaceEntries,
      resolverOptions = ResolveOptions(
        whitelistPrefixes = emptySet(),
        preferWorkspace = hasWorkspaceModules,
        includeHostsForFragments = true
      ),
      autoStartBundles = emptyMap(),
      startupLevels = emptyMap(),
      devProperties = emptyMap()
    )
    val options = LauncherOptions(
      frameworkBSN = framework,
      autoStartDefault = false
    )
    val planResult = LaunchPlanner.build(env, options)
    val specs = CompileService.buildSpecs(planResult, workspaceInputs.descriptors)
      .specs
      .map { spec ->
        val withDebug = if (debugInfo && spec.isWorkspace) {
          val prefs = spec.compilerPrefs.toMutableMap().apply {
            putIfAbsent("org.eclipse.jdt.core.compiler.debug.localVariable", "generate")
            putIfAbsent("org.eclipse.jdt.core.compiler.debug.lineNumber", "generate")
            putIfAbsent("org.eclipse.jdt.core.compiler.debug.sourceFile", "generate")
          }
          spec.copy(compilerPrefs = prefs)
        } else spec
        if (spec.isWorkspace && outputRoot != null) {
          val overrideDir = Paths.get(spec.bundlePath).resolve(outputRoot).normalize()
          withDebug.copy(outputDirectory = overrideDir.toString())
        } else withDebug
      }

    if (json) {
      println(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(specs))
      return
    }

    if (!runCompile) {
      logger.info("Resolved ${specs.size} bundles (dry-run; use --execute to compile).")
      specs.forEachIndexed { idx, spec ->
        logger.info("${idx + 1}. ${spec.bsn}@${spec.version} [${spec.origin}]")
        logger.info("    classpath: ${spec.classpath.joinToString(File.pathSeparator)}")
        if (spec.isWorkspace) {
          logger.info("    sources: ${spec.sourceRoots.joinToString(File.pathSeparator)}")
          logger.info("    resources include: ${spec.resourceIncludes.joinToString()}")
          logger.info("    resources exclude: ${spec.resourceExcludes.joinToString()}")
          logger.info("    EE: ${spec.executionEnvironment ?: "<unspecified>"}  output: ${spec.outputDirectory ?: "<bin>"}")
        }
      }
      return
    }

    val results = CompileExecutor.compile(
      specs,
      workspaceDependencies = planResult.workspaceDependencies,
      forceFullRebuild = fullRebuild
    )
    resultsJson?.let { path ->
      jsonMapper.writerWithDefaultPrettyPrinter().writeValue(java.io.File(path), results)
      logger.info("Wrote results to $path")
    }
    val specsByBsn = specs.associateBy { it.bsn }
    results.forEach { result ->
      val spec = specsByBsn[result.bsn]
      if (spec?.isWorkspace == true && result.success && !result.skipped) {
        logger.info("built ${result.bsn} in ${formatDuration(result.durationMillis)}")
      } else if (spec?.isWorkspace == true && result.success && result.skipped) {
        logger.info("skipped ${result.bsn}: ${result.output}")
      }
    }
    val allOk = results.all { it.success }
    if (bundlesInfoOut != null) {
      if (!allOk) {
        logger.severe("Skipping bundles.info write because some bundles failed to compile.")
      } else {
        val rewrittenPlan = rewritePlanWithCompiledOutputs(planResult.plan, specs)
        val outPath = Paths.get(bundlesInfoOut)
        outPath.parent?.let { Files.createDirectories(it) }
        val text = BundlesInfoRenderer.toText(rewrittenPlan)
        Files.writeString(outPath, text)
        logger.info("Wrote bundles.info with compiled workspace outputs to $outPath")
      }
    }
    results.forEach { r ->
      if (!r.success) {
        val errorsOnly = extractErrorBlocks(r.output)
        if (errorsOnly != null) {
          logger.info("${r.bsn}: FAIL")
          logger.severe(errorsOnly)
        } else {
          logger.info("${r.bsn}: FAIL (warnings only)")
        }
      }
    }
    if (runtimeOut != null) {
      if (!allOk) {
        logger.severe("Skipping runtime layout write because some bundles failed to compile.")
      } else {
        val rewrittenPlan = rewritePlanWithCompiledOutputs(planResult.plan, specs)
        val outDir = Paths.get(runtimeOut)
        val devProps = buildDevProperties(specs, results)
        val context = LaunchContext(
          startupLevels = planResult.context.startupLevels,
          devProperties = devProps
        )
        val defaults = RuntimeLayoutWriter.Defaults(
          installArea = profilePath,
          instanceArea = outDir.resolve("workspace")
        )
        RuntimeLayoutWriter.write(
          layout = RuntimeLayoutWriter.LayoutPaths.fromConfigDir(outDir),
          plan = rewrittenPlan,
          context = context,
          options = options,
          defaults = defaults
        )
        logger.info("Wrote config.ini, dev.properties, bundles.info under $outDir")
      }
    }
    return
  }

  if (targetRoots.isEmpty()) {
    logger.severe("No --target-root specified")
    return
  }

  val targetPaths = targetRoots.map { Paths.get(it) }
  val targetIndex = TargetPlatformCache.buildWithCache(targetPaths)
  val workspaceDescriptors = workspaceRoots.mapNotNull { root ->
    runCatching { WorkspaceBundleLoader.load(Paths.get(root)) }
      .onFailure { logger.severe("Failed to load workspace bundle at $root: ${it.message}") }
      .getOrNull()
  }

  val env = LaunchEnvironment(
    targetIndex = targetIndex,
    workspaceEntries = workspaceDescriptors,
    resolverOptions = ResolveOptions(
      whitelistPrefixes = emptySet(),
      preferWorkspace = true,
      includeHostsForFragments = true
    ),
    autoStartBundles = emptyMap(),
    startupLevels = emptyMap(),
    devProperties = emptyMap()
  )
  val options = LauncherOptions(
    frameworkBSN = framework,
    autoStartDefault = false
  )

  val planResult = LaunchPlanner.build(env, options)
  val specs = CompileService.buildSpecs(planResult, workspaceDescriptors)
    .specs
    .map { spec ->
      if (spec.isWorkspace && outputRoot != null) {
        val overrideDir = Paths.get(spec.bundlePath).resolve(outputRoot).normalize()
        spec.copy(outputDirectory = overrideDir.toString())
      } else spec
    }

  if (json) {
    println(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(specs))
    return
  }

    if (!runCompile) {
      logger.info("Resolved ${specs.size} bundles (dry-run; use --execute to compile).")
      specs.forEachIndexed { idx, spec ->
        logger.info("${idx + 1}. ${spec.bsn}@${spec.version} [${spec.origin}]")
      logger.info("    classpath: ${spec.classpath.joinToString(File.pathSeparator)}")
      if (spec.isWorkspace) {
        logger.info("    sources: ${spec.sourceRoots.joinToString(File.pathSeparator)}")
        logger.info("    resources include: ${spec.resourceIncludes.joinToString()}")
        logger.info("    resources exclude: ${spec.resourceExcludes.joinToString()}")
        logger.info("    EE: ${spec.executionEnvironment ?: "<unspecified>"}  output: ${spec.outputDirectory ?: "<bin>"}")
      }
    }
    return
  }

    val results = CompileExecutor.compile(
      specs,
      workspaceDependencies = planResult.workspaceDependencies,
      forceFullRebuild = fullRebuild
    )
    resultsJson?.let { path ->
      jsonMapper.writerWithDefaultPrettyPrinter().writeValue(java.io.File(path), results)
      logger.info("Wrote results to $path")
    }
    val specsByBsn = specs.associateBy { it.bsn }
    results.forEach { result ->
      val spec = specsByBsn[result.bsn]
      if (spec?.isWorkspace == true && result.success && !result.skipped) {
        logger.info("built ${result.bsn} in ${formatDuration(result.durationMillis)}")
      } else if (spec?.isWorkspace == true && result.success && result.skipped) {
        logger.info("skipped ${result.bsn}: ${result.output}")
      }
    }
    val allOk = results.all { it.success }
  if (bundlesInfoOut != null) {
    if (!allOk) {
      logger.severe("Skipping bundles.info write because some bundles failed to compile.")
    } else {
      val rewrittenPlan = rewritePlanWithCompiledOutputs(planResult.plan, specs)
      val outPath = Paths.get(bundlesInfoOut)
      outPath.parent?.let { Files.createDirectories(it) }
      val text = BundlesInfoRenderer.toText(rewrittenPlan)
      Files.writeString(outPath, text)
      logger.info("Wrote bundles.info with compiled workspace outputs to $outPath")
    }
  }
  results.forEach { r ->
    if (!r.success) {
      val errorsOnly = extractErrorBlocks(r.output)
      if (errorsOnly != null) {
        logger.info("${r.bsn}: FAIL")
        logger.severe(errorsOnly)
      } else {
        logger.info("${r.bsn}: FAIL (warnings only)")
      }
    }
  }
  if (runtimeOut != null) {
    if (!allOk) {
      logger.severe("Skipping runtime layout write because some bundles failed to compile.")
      return
    }
    val outDir = Paths.get(runtimeOut)
    val rewrittenPlan = rewritePlanWithCompiledOutputs(planResult.plan, specs)
    val devProps = buildDevProperties(specs, results)
    val context = LaunchContext(
      startupLevels = planResult.context.startupLevels,
      devProperties = devProps
    )
    val defaults = RuntimeLayoutWriter.Defaults(
      installArea = targetPaths.firstOrNull() ?: outDir,
      instanceArea = outDir.resolve("workspace")
    )
    RuntimeLayoutWriter.write(
      layout = RuntimeLayoutWriter.LayoutPaths.fromConfigDir(outDir),
      plan = rewrittenPlan,
      context = context,
      options = options,
      defaults = defaults
    )
    logger.info("Wrote config.ini, dev.properties, bundles.info under $outDir")
  }
}

private fun buildDevProperties(specs: List<CompileSpec>, results: List<BundleCompileResult>): Map<String, List<String>> {
  val success = results.filter { it.success }.map { it.bsn }.toSet()
  return specs
    .filter { it.isWorkspace && success.contains(it.bsn) }
    .associate { spec ->
      val out = spec.outputDirectory
        ?: Paths.get(spec.bundlePath).resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR).toString()
      spec.bsn to listOf(out)
    }
}

private fun formatDuration(durationMillis: Long): String {
  val seconds = durationMillis / 1000.0
  return String.format(Locale.US, "%.2fs", seconds)
}

private fun extractErrorBlocks(output: String): String? {
  val trimmed = output.trim()
  if (trimmed.isEmpty()) return null
  if (!trimmed.contains("ERROR in")) {
    val knownFailures = listOf(
      "Classpath contains class files requiring a newer Java version",
      "Annotation processors detected; javac fallback not implemented"
    )
    return if (knownFailures.any { trimmed.contains(it) }) trimmed else null
  }
  val lines = output.lineSequence().toList()
  val blocks = mutableListOf<String>()
  var i = 0
  val entryRegex = Regex("""^\d+\.\s+(ERROR|WARNING|INFO)\s+in\b""")
  while (i < lines.size) {
    val line = lines[i]
    if (!line.contains("ERROR in")) {
      i++
      continue
    }
    val sb = StringBuilder()
    while (i < lines.size) {
      val current = lines[i]
      sb.append(current).append('\n')
      i++
      if (current.startsWith("----------") && i < lines.size && entryRegex.containsMatchIn(lines[i])) {
        break
      }
    }
    blocks += sb.toString().trimEnd()
  }
  return blocks.joinToString("\n")
}

fun main(args: Array<String>) {
  if (args.isNotEmpty() && args[0] == "launch") {
    launchMain(args.drop(1).toTypedArray())
    return
  }
  if (args.isNotEmpty() && args[0] == "test") {
    val exit = testMain(args.drop(1).toTypedArray())
    exitProcess(exit)
  }

  val parser = ArgParser("pde-resolver")

  val rootsOpt by parser.option(ArgType.String, fullName = "root", shortName = "r", description = "Root path (repeatable)").multiple()
  val cacheFilePath by parser.option(ArgType.String, fullName = "cache-file", shortName = "c", description = "Cache file path (optional)")
  val filterBsn by parser.option(ArgType.String, fullName = "bsn", shortName = "b", description = "Filter by bundle symbolic name")
  val json by parser.option(ArgType.Boolean, fullName = "json", shortName = "j", description = "Output JSON").default(false)

  parser.parse(args)

  val roots = rootsOpt
  if (roots.isEmpty()) {
    logger.severe("No roots provided. Pass one or more paths with --root/-r.")
    return
  }

  val paths: List<Path> = roots.map { File(it).toPath() }
  val cacheFile = cacheFilePath?.let { File(it) }
  val index = TargetPlatformCache.buildWithCache(paths, cacheFile)

  val bundles = index.bundlesByBsn()
  val flat = bundles.entries
    .sortedBy { it.key }
    .flatMap { (bsn, nav) -> nav.entries.map { e -> Triple(bsn, e.key.toString(), e.value.location.toString()) } }
    .let { list -> if (filterBsn != null) list.filter { it.first == filterBsn } else list }

  if (json) {
    val items = flat.joinToString(",") { (bsn, ver, path) ->
      "{" +
        "\"bsn\":\"${escape(bsn)}\"," +
        "\"version\":\"${escape(ver)}\"," +
        "\"path\":\"${escape(path)}\"" +
      "}"
    }
    val src = index.source.name
    println("{" +
      "\"source\":\"$src\"," +
      "\"count\":${flat.size}," +
      "\"bundles\":[" + items + "]" +
    "}")
  } else {
    logger.info("Source: ${index.source}; bundles: ${flat.size}")
    flat.forEach { (bsn, ver, path) -> logger.info("$bsn@$ver -> $path") }
  }
}

private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
