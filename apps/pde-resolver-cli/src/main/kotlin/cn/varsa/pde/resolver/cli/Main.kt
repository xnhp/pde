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
import cn.varsa.pde.resolver.compile.CompileService
import cn.varsa.pde.resolver.compile.CompileExecutor
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
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

internal const val PDE_JUNIT_PLUGIN_TEST_APPLICATION = "org.eclipse.pde.junit.runtime.coretestapplication"
internal const val DEFAULT_TEST_DEBUG_PORT = 5005
private val jsonMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
private val logger: Logger = Logger.getLogger("pde-resolver-cli")

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
  val parser = ArgParser("pde-launch")
  val configFileOpt by parser.option(
    ArgType.String,
    fullName = "config",
    description = "YAML launch configuration"
  )
  val configPos by parser.argument(
    ArgType.String,
    description = "YAML launch configuration (positional)"
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

  parser.parse(args)

  val configFile = configFileOpt ?: configPos

  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()

  if (discoveredConfig != null) {
    if (configFile == null) {
      logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
    }
    val configContext = LaunchConfigLoader.load(discoveredConfig)
    val targetPath = configContext.config.targetFile
      ?.let { configContext.baseDir.resolve(it).normalize() }
    val targetArgs = if (configContext.config.inheritTargetArgs && targetPath != null) {
      runCatching { TargetFileParser.parse(targetPath) }
        .onFailure { logger.warning("Failed to parse target file args: ${it.message}") }
        .getOrNull()
    } else null
    if (configContext.config.inheritTargetArgs && targetPath == null) {
      logger.warning("inheritTargetArgs=true but targetFile is not set; skipping target argument import.")
    }
    describeConfig(configContext, targetPath, targetArgs)
    if (dryRun) {
      logger.info("Dry run: validation only. Exiting.")
      return
    }
    executeLaunch(configContext, targetArgs)
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
  if (config.config.debugTests) {
    val applicable = config.config.application?.equals(PDE_JUNIT_PLUGIN_TEST_APPLICATION, ignoreCase = true) == true
    val message = if (applicable) {
      "enabled (JDWP on port $DEFAULT_TEST_DEBUG_PORT)"
    } else {
      "requested but only applies when application=$PDE_JUNIT_PLUGIN_TEST_APPLICATION"
    }
    logger.info("  test debug: $message")
  }
}

private fun executeLaunch(context: LaunchConfigContext, targetArgs: TargetLaunchArgs?) {
  val prepared = prepareLaunch(context, targetArgs)
  val planResult = prepared.planResult
  logger.info("Launch plan built:")
  logger.info("  bundles: ${planResult.plan.bundles.size}")
  logger.info("  workspace bundles: ${planResult.plan.bundles.count { it.isWorkspace }}")
  logger.info("  problems: ${planResult.problemsByScope.values.sumOf { it.size }}")
  if (planResult.problemsByScope.isNotEmpty()) {
    planResult.problemsByScope.forEach { (scope, probs) ->
      logger.info("    $scope -> ${probs.size} issues")
    }
  }
  logCommand(prepared.command)
  val process = ProcessBuilder(prepared.command)
    .directory(prepared.layout.workDir.toFile())
    .inheritIO()
    .start()
  val exit = process.waitFor()
  if (exit != 0) error("Launcher exited with code $exit")
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
  writeLaunchArtifacts(context, layout, planResult, options, profilePath, fallbackConfig)
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
    addAll(buildTestDebugVmArgs(context.config, this))
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

internal fun buildTestDebugVmArgs(config: LaunchConfig, existingVmArgs: List<String>): List<String> {
  if (!config.debugTests) return emptyList()
  if (!config.application.equals(PDE_JUNIT_PLUGIN_TEST_APPLICATION, ignoreCase = true)) return emptyList()
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
  fallbackConfig: Properties
) {
  val defaults = RuntimeLayoutWriter.Defaults(
    installArea = installArea,
    instanceArea = layout.dataDir,
    fallbackConfig = fallbackConfig
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

private fun writeOutputs(dir: Path, plan: LauncherPlan, ctx: LaunchContext, opts: LauncherOptions) {
  val layout = RuntimeLayoutWriter.LayoutPaths.fromConfigDir(dir)
  val defaults = RuntimeLayoutWriter.Defaults(
    installArea = dir,
    instanceArea = dir
  )
  RuntimeLayoutWriter.write(layout, plan, ctx, opts, defaults)
}

private fun testMain(args: Array<String>): Int {
  val parser = ArgParser("pde-launch test")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration")
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
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
  parser.parse(args)

  val configFile = configFileOpt ?: configPos

  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  if (discoveredConfig == null) {
    logger.severe("Missing --config and no launch config discovered in current directory")
    return 2
  }
  if (configFile == null) {
    logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
  }

  val configContext = applyTestDefaults(LaunchConfigLoader.load(discoveredConfig))
  val targetPath = configContext.config.targetFile
    ?.let { configContext.baseDir.resolve(it).normalize() }
  val targetArgs = if (configContext.config.inheritTargetArgs && targetPath != null) {
    runCatching { TargetFileParser.parse(targetPath) }
      .onFailure { logger.warning("Failed to parse target file args: ${it.message}") }
      .getOrNull()
  } else null
  if (configContext.config.inheritTargetArgs && targetPath == null) {
    logger.warning("inheritTargetArgs=true but targetFile is not set; skipping target argument import.")
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
  println(jsonMapper.writeValueAsString(announcement))
  logger.info("Listening on ${announcement.host}:${announcement.port}")
  logger.info("Waiting up to ${timeoutSeconds}s for RemoteTestRunner connection...")

  val prepared = runCatching { prepareLaunch(configContext, targetArgs, listOf("-port", server.localPort.toString())) }
    .getOrElse { error ->
      server.close()
      logger.severe("Failed to build launch plan: ${error.message}")
      return 2
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

private fun compileMain(args: Array<String>) {
  val parser = ArgParser("pde-compile")
  val targetRoots by parser.option(ArgType.String, fullName = "target-root", shortName = "t", description = "Target root/profile (repeatable)").multiple()
  val workspaceRoots by parser.option(ArgType.String, fullName = "workspace", shortName = "w", description = "Workspace bundle directory (repeatable)").multiple()
  val framework by parser.option(ArgType.String, fullName = "framework", description = "Framework BSN").default("org.eclipse.osgi")
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit compile specs as JSON").default(false)
  val execute by parser.option(ArgType.Boolean, fullName = "execute", description = "Run ECJ compilation (default: dry-run specs)").default(false)
  val resultsJson by parser.option(ArgType.String, fullName = "results-json", description = "Write compile results (when --execute) to JSON file")
  parser.parse(args)

  if (targetRoots.isEmpty()) {
    logger.severe("No --target-root specified")
    return
  }

  val targetPaths = targetRoots.map { Paths.get(it) }
  val targetIndex = TargetPlatformCache.buildWithCache(targetPaths)
  val workspaceDescriptors = workspaceRoots.map { loadWorkspaceDescriptor(it) }

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
  val specs = CompileService.buildSpecs(planResult, workspaceDescriptors).specs

  if (json) {
    println(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(specs))
    return
  }

  if (!execute) {
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

  val results = CompileExecutor.compile(specs)
  resultsJson?.let { path ->
    jsonMapper.writerWithDefaultPrettyPrinter().writeValue(java.io.File(path), results)
    logger.info("Wrote results to $path")
  }
  results.forEach { r ->
    val status = if (r.success) "OK" else "FAIL"
    logger.info("${r.bsn}: $status")
    if (!r.success) {
      logger.severe(r.output)
    }
  }
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
  if (args.isNotEmpty() && args[0] == "compile") {
    compileMain(args.drop(1).toTypedArray())
    return
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
