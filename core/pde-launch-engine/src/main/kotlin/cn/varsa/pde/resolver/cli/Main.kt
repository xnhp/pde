package cn.varsa.pde.resolver.cli

import cn.varsa.cli.core.CliStyle
import cn.varsa.cli.core.ColorMode
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
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.index.getBundlePoolPath
import cn.varsa.pde.resolver.launch.*
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.compile.BundleCompileCache
import cn.varsa.pde.resolver.compile.BundleCompileResult
import cn.varsa.pde.resolver.compile.CompileExecutor
import cn.varsa.pde.resolver.compile.CompileService
import cn.varsa.pde.resolver.compile.CompileSpec
import cn.varsa.pde.resolver.compile.rewritePlanWithCompiledOutputs
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
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.optional
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.Locale
import java.util.Properties
import java.util.jar.Manifest
import java.util.zip.GZIPInputStream
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import cn.varsa.pde.remoterunner.ConsoleTags
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.io.path.inputStream
import kotlin.system.exitProcess
import cn.varsa.pde.resolver.workspace.WorkspaceBundleLoader
import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import cn.varsa.pde.resolver.launch.RuntimeLayoutWriter
import cn.varsa.pde.resolver.launch.LaunchContext
import org.osgi.framework.Version

internal const val PDE_JUNIT_PLUGIN_TEST_APPLICATION = "org.eclipse.pde.junit.runtime.coretestapplication"
internal const val PDE_API_ANALYZER_APPLICATION = "org.eclipse.pde.api.tools.apiAnalyzer"
private const val CRAC_CHECKPOINT_EXIT_CODE = 137
private const val CRAC_CHECKPOINT_ARG_PREFIX = "-XX:CRaCCheckpointTo="
internal const val KNIME_API_ANALYZER_APPLICATION = "com.knime.enterprise.devops.eclipse.ApiAnalyzer"
internal const val API_ANALYZER_BASELINE_PROFILE_ID = "api-analyzer-baseline"
internal const val P2_METADATA_MIRROR_APPLICATION = "org.eclipse.equinox.p2.metadata.repository.mirrorApplication"
internal const val P2_ARTIFACT_MIRROR_APPLICATION = "org.eclipse.equinox.p2.artifact.repository.mirrorApplication"
internal const val DEFAULT_TEST_DEBUG_PORT = 5005
private const val TARGET_INSTALLER_LAUNCHER_JAR = "target-installer-launcher.jar"
private const val TARGET_INSTALLER_OVERRIDE_PROPERTY = "pde.targetInstaller"
private val jsonMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
private val logger: Logger = Logger.getLogger("pde-launch-engine")

internal data class TargetInstallInputs(
  val profileId: String,
  val p2Path: Path,
  val targetDefinition: Path,
  val installRoot: Path,
  val bundlePool: Path,
  val installerJar: Path,
  val includeConfigurePhase: Boolean
) {
  fun installerArgs(): List<String> = buildTargetInstallerArgs(
    profileId = profileId,
    p2Path = p2Path,
    targetDefinition = targetDefinition,
    installPath = installRoot,
    bundlePool = bundlePool,
    includeConfigurePhase = includeConfigurePhase
  )
}

private fun createFormatter(useColor: Boolean) = object : Formatter() {
  override fun format(record: LogRecord): String {
    val message = formatMessage(record)
    val builder = StringBuilder()
    builder.append(logPrefix(record.level, useColor)).append(' ').append(message).append('\n')
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
private fun logPrefix(level: Level, useColor: Boolean): String {
  val value = level.intValue()
  return when {
    value >= Level.SEVERE.intValue() -> ConsoleTags.error(useColor)
    value >= Level.WARNING.intValue() -> ConsoleTags.warn(useColor)
    value >= Level.INFO.intValue() -> ConsoleTags.info(useColor)
    value >= Level.FINE.intValue() -> ConsoleTags.debug(useColor)
    else -> ConsoleTags.trace(useColor)
  }
}
private fun configureLogging(level: Level, useColor: Boolean) {
  logger.level = level
  val root = Logger.getLogger("")
  root.level = level
  val formatter = createFormatter(useColor)
  root.handlers?.forEach { handler ->
    root.removeHandler(handler)
  }
  val handler = object : java.util.logging.StreamHandler(System.err, formatter) {
    override fun publish(record: LogRecord) {
      super.publish(record)
      flush()
    }

    override fun close() {
      flush()
    }
  }.apply {
    this.level = level
  }
  root.addHandler(handler)
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
private fun shouldUseColor(noColor: Boolean = false): Boolean = !noColor && System.console() != null
fun maturityTag(label: String): String = CliStyle.maturityTag(label, CliStyle.useColor(ColorMode.AUTO))
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
  "--output",
  "--launch"
)
internal val testOptionsRequiringValue = setOf(
  "--config",
  "--log",
  "--listen-host",
  "--listen-port",
  "--port-range",
  "--timeout",
  "--report",
  "--forward-log"
)
internal val compileOptionsRequiringValue = setOf(
  "--config",
  "--workspace",
  "-w",
  "--framework",
  "--results-json",
  "--output-root",
  "--bundles-info-out",
  "--runtime-out"
)
internal val apiAnalyzeOptionsRequiringValue = setOf(
  "--config",
  "--log",
  "--baseline-root",
  "--dependency-list",
  "--baseline-list",
  "--application",
  "--report"
)
internal val targetMirrorOptionsRequiringValue = setOf(
  "--config",
  "--log",
  "--destination",
  "--write-mode",
  "-d"
)
internal val targetInspectOptionsRequiringValue = setOf(
  "--config",
  "--limit"
)

fun looksLikeYamlFile(arg: String): Boolean {
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

internal data class NormalizedTestArgs(
  val parserArgs: Array<String>,
  val requestedTests: List<String>
)

internal fun normalizeTestArgs(rawArgs: Array<String>): NormalizedTestArgs {
  val hasExplicitConfig = rawArgs.any { it == "--config" || it.startsWith("--config=") }
  val parserTokens = mutableListOf<String>()
  val requestedTests = mutableListOf<String>()

  var index = 0
  while (index < rawArgs.size) {
    val arg = rawArgs[index]
    if (arg.startsWith("-")) {
      parserTokens += arg
      val optionName = arg.substringBefore('=')
      if (testOptionsRequiringValue.contains(optionName) && !arg.contains('=') && index + 1 < rawArgs.size) {
        parserTokens += rawArgs[index + 1]
        index += 2
      } else {
        index += 1
      }
      continue
    }

    if (!hasExplicitConfig && parserTokens.isEmpty() && looksLikeYamlFile(arg)) {
      parserTokens += arg
    } else {
      requestedTests += arg
    }
    index += 1
  }

  return NormalizedTestArgs(
    parserArgs = parserTokens.toTypedArray(),
    requestedTests = requestedTests
  )
}

internal fun inferTestConfigFile(configFileOpt: String?, normalizedTestArgs: NormalizedTestArgs): String? =
  configFileOpt ?: normalizedTestArgs.parserArgs.firstOrNull()?.takeIf(::looksLikeYamlFile)

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

internal fun resolveJavaBin(env: Map<String, String> = emptyMap()): String {
  val javaExecutable = if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) "java.exe" else "java"
  val javaHome = env["JAVA_HOME"] ?: System.getenv("JAVA_HOME")
  return javaHome?.let { Path.of(it, "bin", javaExecutable).toString() } ?: javaExecutable
}

private fun buildCompilePlanForWarning(
  context: LaunchConfigContext,
  targetIndex: cn.varsa.pde.resolver.index.TargetPlatformIndex,
  workspaceInputs: cn.varsa.pde.resolver.launch.WorkspaceInputs
): LaunchPlanner.PlanResult {
  val extraBundles = configuredExtraBundles(context)
  logActiveExtraBundles(extraBundles)
  val pinnedVersions = configuredPinnedVersions(context)
  val hasWorkspaceModules = workspaceInputs.descriptors.isNotEmpty()
  val workspaceEntries = if (hasWorkspaceModules) {
    workspaceInputs.descriptors
  } else {
    listOfNotNull(syntheticEntry(context, targetIndex))
  }
  val env = LaunchEnvironment(
    targetIndex = targetIndex,
    workspaceEntries = workspaceEntries,
    resolverOptions = ResolveOptions(
      whitelistPrefixes = emptySet(),
      preferWorkspace = hasWorkspaceModules,
      includeHostsForFragments = true,
      pinnedVersions = pinnedVersions,
      extraBundles = extraBundles
    ),
    autoStartBundles = emptyMap(),
    startupLevels = emptyMap(),
    devProperties = emptyMap()
  )
  val options = LauncherOptions(frameworkBSN = "org.eclipse.osgi", autoStartDefault = false)
  return LaunchPlanner.build(env, options)
}

fun launchMain(args: Array<String>, commandName: String = "pde run") {
  if (args.isNotEmpty() && args[0] == "target") {
    val subcommand = args.getOrNull(1)
    when {
      subcommand == null || subcommand == "-h" || subcommand == "--help" || subcommand == "help" -> {
        printTargetHelp()
        return
      }
      subcommand == "install" -> {
        val exit = targetMain(args.drop(2).toTypedArray())
        exitProcess(exit)
      }
      subcommand == "mirror" -> {
        val exit = targetMirrorMain(args.drop(2).toTypedArray())
        exitProcess(exit)
      }
      subcommand == "inspect" -> {
        val inspectSubcommand = args.getOrNull(2)
        when {
          inspectSubcommand == null || inspectSubcommand == "-h" || inspectSubcommand == "--help" || inspectSubcommand == "help" -> {
            printTargetInspectHelp()
            return
          }
          inspectSubcommand == "profile" -> exitProcess(targetInspectProfileMain(args.drop(3).toTypedArray()))
          inspectSubcommand == "ius" -> exitProcess(targetInspectIusMain(args.drop(3).toTypedArray()))
          inspectSubcommand == "diff" -> exitProcess(targetInspectDiffMain(args.drop(3).toTypedArray()))
          inspectSubcommand == "health" -> exitProcess(targetInspectHealthMain(args.drop(3).toTypedArray()))
          inspectSubcommand == "snapshots" -> exitProcess(targetInspectSnapshotsMain(args.drop(3).toTypedArray()))
          else -> {
            logger.severe("Unknown target inspect subcommand '$inspectSubcommand'. Use 'pde target inspect --help'.")
            exitProcess(2)
          }
        }
      }
      else -> {
        logger.severe("Unknown target subcommand '$subcommand'. Use 'pde target --help'.")
        exitProcess(2)
      }
    }
  }
  if (args.isNotEmpty() && args[0] == "test") {
    val exit = testMain(args.drop(1).toTypedArray())
    exitProcess(exit)
  }
  if (args.isNotEmpty() && args[0] == "api-filters") {
    val exit = apiFiltersMain(args.drop(1).toTypedArray())
    exitProcess(exit)
  }
  if (args.isNotEmpty() && (args[0] == "api-analyze" || args[0] == "api-analyzer")) {
    val subcommand = args.getOrNull(1)
    val exit = when (subcommand) {
      null -> apiAnalyzeMain(emptyArray())
      "-h", "--help", "help" -> {
        printApiAnalyzeHelp()
        0
      }
      "install" -> apiAnalyzeInstallMain(args.drop(2).toTypedArray())
      "run" -> apiAnalyzeMain(args.drop(2).toTypedArray())
      else -> apiAnalyzeMain(args.drop(1).toTypedArray())
    }
    exitProcess(exit)
  }
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, launchOptionsRequiringValue)

  val parser = ArgParser("$commandName ${maturityTag("usable")}")
  val configFileOpt by parser.option(
    ArgType.String,
    fullName = "config",
    description = "YAML launch configuration (supports launches/tests, target.extraBundles, and target.pinnedVersions)"
  )
  val logLevelOpt by parser.option(
    ArgType.String,
    fullName = "log-level",
    description = "Logging level (error|warn|info|debug|trace)"
  )
  val logFileOpt by parser.option(
    ArgType.String,
    fullName = "log",
    description = "Write launched PDE process stdout/stderr to a file"
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
    description = "Enable JDWP for launch JVM"
  ).default(false)
  val osgiDebug by parser.option(
    ArgType.Boolean,
    fullName = "osgiDebug",
    description = "Enable OSGi debug output (-debug)"
  ).default(false)
  val clean by parser.option(
    ArgType.Boolean,
    fullName = "clean",
    description = "Launch with Eclipse -clean and rebuild OSGi framework state"
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
  configureLogging(resolveLogLevel(logLevelOpt, verbose, false), shouldUseColor())

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
    val configContext = LaunchConfigLoader.load(discoveredConfig).copy(clean = clean)
    val selected = selectLaunchConfig(configContext, launchName, debug)
    if (selected == null) return
    val osgiContext = applyOsgiDebug(selected, osgiDebug)
    val targetDefinition = resolveTargetDefinition(osgiContext)
    val targetArgs = resolveTargetArgs(osgiContext, targetDefinition)
    val profilePath = resolveProfilePath(osgiContext)
    describeConfig(osgiContext, targetDefinition, profilePath, targetArgs)
    if (dryRun) {
      logger.info("Dry run: validation only. Exiting.")
      return
    }
    val logFile = logFileOpt?.let { Paths.get(it) }
    executeLaunch(osgiContext, targetArgs, showLogPathWhenDebugging = debug, logFile = logFile)
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

private fun printTargetHelp() {
  println("pde target ${maturityTag("usable")} - target platform commands")
  println()
  println("Usage:")
  println("  pde target <subcommand> [options]")
  println()
  println("Subcommands:")
  println("  install ${maturityTag("usable")} Resolve/prepare target platform state")
  println("  mirror  ${maturityTag("usable")} Mirror update sites from a .target definition")
  println("  inspect ${maturityTag("usable")} Inspect target profile state and health")
  println()
  println("See also:")
  println("  pde target install --help")
  println("  pde target mirror --help")
  println("  pde target inspect --help")
  println("  docs/cli-quickstart.md")
}

private fun printTargetInspectHelp() {
  println("pde target inspect ${maturityTag("usable")} - inspect target profile state")
  println()
  println("Usage:")
  println("  pde target inspect <subcommand> [options]")
  println()
  println("Subcommands:")
  println("  profile   ${maturityTag("usable")} Show profile location and bundle-pool basics")
  println("  ius       ${maturityTag("usable")} List installable units from latest profile snapshot")
  println("  diff      ${maturityTag("usable")} Diff latest and previous profile snapshots")
  println("  health    ${maturityTag("usable")} Run consistency checks for profile and bundle pool")
  println("  snapshots ${maturityTag("usable")} List available profile snapshots")
  println()
  println("Examples:")
  println("  pde target inspect profile --config pde.yaml")
  println("  pde target inspect ius --list")
  println("  pde target inspect health")
}

private data class ProfileArtifact(
  val classifier: String,
  val id: String,
  val version: String
)

private data class TargetInspectionContext(
  val configContext: LaunchConfigContext,
  val profileDir: Path,
  val snapshots: List<Path>,
  val latestSnapshot: Path,
  val previousSnapshot: Path?
)

private fun loadTargetInspectionContext(configFileOpt: String?, configPos: String?): TargetInspectionContext? {
  val configFile = configFileOpt ?: configPos?.takeIf { looksLikeYamlFile(it) }
  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  if (discoveredConfig == null) {
    logger.severe("Missing --config and no launch config discovered in current directory")
    return null
  }
  if (configFile == null) {
    logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
  }

  val configContext = LaunchConfigLoader.load(discoveredConfig)
  val profilePath = resolveProfilePath(configContext)
  if (profilePath == null) {
    logger.severe("target profile path missing in YAML config; set target.profileId + target.p2Path.")
    return null
  }
  if (!Files.exists(profilePath)) {
    logger.severe(
      "target profile registry does not exist: $profilePath (check target.profileId/target.p2Path or run pde target install)"
    )
    return null
  }
  if (!Files.isDirectory(profilePath)) {
    logger.severe("target profile path is not a directory: ${profilePath.toAbsolutePath().normalize()}")
    return null
  }
  val snapshots = listProfileSnapshots(profilePath)
  if (snapshots.isEmpty()) {
    logger.severe("No profile snapshots found under ${profilePath.toAbsolutePath().normalize()}")
    return null
  }
  return TargetInspectionContext(
    configContext = configContext,
    profileDir = profilePath,
    snapshots = snapshots,
    latestSnapshot = snapshots.last(),
    previousSnapshot = snapshots.getOrNull(snapshots.size - 2)
  )
}

private fun listProfileSnapshots(profileDir: Path): List<Path> {
  return Files.newDirectoryStream(profileDir).use { stream ->
    stream
      .filter { Files.isRegularFile(it) }
      .filter {
        val name = it.fileName.toString().lowercase(Locale.ROOT)
        name.endsWith(".profile") || name.endsWith(".profile.gz")
      }
      .sortedWith(compareBy({ snapshotTimestamp(it) }, { it.fileName.toString() }))
      .toList()
  }
}

private fun snapshotTimestamp(snapshot: Path): Long {
  return snapshot.fileName.toString().substringBefore('.').toLongOrNull() ?: Long.MIN_VALUE
}

private fun parseProfileArtifacts(snapshot: Path): List<ProfileArtifact> {
  val artifacts = mutableListOf<ProfileArtifact>()
  val xmlFactory = XMLInputFactory.newInstance()
  val input = snapshot.inputStream().let { raw ->
    if (snapshot.fileName.toString().lowercase(Locale.ROOT).endsWith(".gz")) GZIPInputStream(raw) else raw
  }
  input.use { stream ->
    val reader = xmlFactory.createXMLStreamReader(stream)
    try {
      while (reader.hasNext()) {
        if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "artifact") {
          val classifier = reader.getAttributeValue("", "classifier") ?: continue
          val id = reader.getAttributeValue("", "id") ?: continue
          val version = reader.getAttributeValue("", "version") ?: continue
          artifacts += ProfileArtifact(classifier = classifier, id = id, version = version)
        }
      }
    } finally {
      reader.close()
    }
  }
  return artifacts
}

internal fun targetInspectProfileMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, targetInspectOptionsRequiringValue)
  val parser = ArgParser("pde target inspect profile ${maturityTag("usable")}")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration (supports target.extraBundles and target.pinnedVersions)")
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit JSON output").default(false)
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
  parser.parse(normalizedArgs)
  configureLogging(Level.WARNING, shouldUseColor())

  val context = loadTargetInspectionContext(configFileOpt, configPos) ?: return 2
  val bundlePool = getBundlePoolPath(context.latestSnapshot.toFile())
  val payload = mapOf(
    "config" to context.configContext.file.toAbsolutePath().normalize().toString(),
    "profileDir" to context.profileDir.toAbsolutePath().normalize().toString(),
    "latestSnapshot" to context.latestSnapshot.fileName.toString(),
    "snapshotCount" to context.snapshots.size,
    "bundlePool" to bundlePool,
    "bundlePoolExists" to (bundlePool?.let { Files.exists(Paths.get(it.removePrefix("file:"))) } == true)
  )
  if (json) {
    println(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
  } else {
    println("Config: ${payload["config"]}")
    println("Profile dir: ${payload["profileDir"]}")
    println("Latest snapshot: ${payload["latestSnapshot"]}")
    println("Snapshots: ${payload["snapshotCount"]}")
    println("Bundle pool: ${payload["bundlePool"] ?: "<missing in profile properties>"}")
    println("Bundle pool exists: ${payload["bundlePoolExists"]}")
  }
  return 0
}

internal fun targetInspectSnapshotsMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, targetInspectOptionsRequiringValue)
  val parser = ArgParser("pde target inspect snapshots ${maturityTag("usable")}")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration")
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit JSON output").default(false)
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
  parser.parse(normalizedArgs)
  configureLogging(Level.WARNING, shouldUseColor())

  val context = loadTargetInspectionContext(configFileOpt, configPos) ?: return 2
  val rows = context.snapshots.map { snapshot ->
    mapOf(
      "file" to snapshot.fileName.toString(),
      "timestamp" to snapshotTimestamp(snapshot)
    )
  }
  if (json) {
    println(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows))
  } else {
    println("Profile dir: ${context.profileDir.toAbsolutePath().normalize()}")
    rows.forEach { row ->
      println("- ${row["file"]} (timestamp=${row["timestamp"]})")
    }
  }
  return 0
}

internal fun targetInspectIusMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, targetInspectOptionsRequiringValue)
  val parser = ArgParser("pde target inspect ius ${maturityTag("usable")}")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration")
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit JSON output").default(false)
  val list by parser.option(ArgType.Boolean, fullName = "list", description = "Print bundle names only, one per line").default(false)
  val limit by parser.option(ArgType.Int, fullName = "limit", description = "Maximum number of IUs to print").default(200)
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
  parser.parse(normalizedArgs)
  configureLogging(Level.WARNING, shouldUseColor())

  val context = loadTargetInspectionContext(configFileOpt, configPos) ?: return 2
  val artifacts = parseProfileArtifacts(context.latestSnapshot)
  val filtered = artifacts
    .filter { it.classifier == "osgi.bundle" }
    .sortedWith(compareBy<ProfileArtifact> { it.id }.thenBy { it.version })
  val selected = filtered.take(limit.coerceAtLeast(1))
  if (list) {
    selected.forEach { println(it.id) }
  } else if (json) {
    println(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(selected))
  } else {
    println("Profile snapshot: ${context.latestSnapshot.fileName}")
    println("Bundles: ${filtered.size}")
  }
  return 0
}

internal fun targetInspectDiffMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, targetInspectOptionsRequiringValue)
  val parser = ArgParser(
    "pde target inspect diff ${maturityTag("usable")} " +
      "(uses latest + previous snapshots from target profile; run 'pde target inspect snapshots' to inspect available files)"
  )
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration")
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit JSON output").default(false)
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
  parser.parse(normalizedArgs)
  configureLogging(Level.WARNING, shouldUseColor())

  val context = loadTargetInspectionContext(configFileOpt, configPos) ?: return 2
  val previous = context.previousSnapshot
  if (previous == null) {
    logger.severe("Need at least two snapshots to diff.")
    return 2
  }

  fun toMap(artifacts: List<ProfileArtifact>): Map<String, String> {
    return artifacts
      .filter { it.classifier == "osgi.bundle" }
      .associate { it.id to it.version }
  }

  val oldMap = toMap(parseProfileArtifacts(previous))
  val newMap = toMap(parseProfileArtifacts(context.latestSnapshot))
  val added = (newMap.keys - oldMap.keys).sorted().map { mapOf("id" to it, "version" to newMap.getValue(it)) }
  val removed = (oldMap.keys - newMap.keys).sorted().map { mapOf("id" to it, "version" to oldMap.getValue(it)) }
  val changed = (newMap.keys intersect oldMap.keys)
    .filter { oldMap[it] != newMap[it] }
    .sorted()
    .map { id -> mapOf("id" to id, "from" to oldMap.getValue(id), "to" to newMap.getValue(id)) }

  val payload = mapOf(
    "from" to previous.fileName.toString(),
    "to" to context.latestSnapshot.fileName.toString(),
    "added" to added,
    "removed" to removed,
    "changed" to changed
  )
  if (json) {
    println(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
  } else {
    println("Diff: ${payload["from"]} -> ${payload["to"]}")
    println("Added: ${added.size}")
    println("Removed: ${removed.size}")
    println("Changed: ${changed.size}")
    changed.take(25).forEach { println("- ${it["id"]}: ${it["from"]} -> ${it["to"]}") }
  }
  return 0
}

internal fun targetInspectHealthMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, targetInspectOptionsRequiringValue)
  val parser = ArgParser("pde target inspect health ${maturityTag("usable")}")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration")
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit JSON output").default(false)
  val limit by parser.option(ArgType.Int, fullName = "limit", description = "Maximum number of missing artifacts to print").default(100)
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
  parser.parse(normalizedArgs)
  configureLogging(Level.WARNING, shouldUseColor())

  val context = loadTargetInspectionContext(configFileOpt, configPos) ?: return 2
  val bundlePool = getBundlePoolPath(context.latestSnapshot.toFile())
  val bundlePoolPath = bundlePool?.removePrefix("file:")?.let { Paths.get(it) }
  val pluginsDir = bundlePoolPath?.resolve("plugins")
  val artifacts = parseProfileArtifacts(context.latestSnapshot)
    .filter { it.classifier == "osgi.bundle" }
  val missing = if (pluginsDir != null && Files.isDirectory(pluginsDir)) {
    artifacts.filter { artifact ->
      val jar = pluginsDir.resolve("${artifact.id}_${artifact.version}.jar")
      val dir = pluginsDir.resolve("${artifact.id}_${artifact.version}")
      !Files.exists(jar) && !Files.exists(dir)
    }
  } else {
    artifacts
  }
  val payload = mapOf(
    "snapshot" to context.latestSnapshot.fileName.toString(),
    "bundleCount" to artifacts.size,
    "bundlePool" to bundlePool,
    "bundlePoolExists" to (bundlePoolPath?.let { Files.exists(it) } == true),
    "pluginsDirExists" to (pluginsDir?.let { Files.isDirectory(it) } == true),
    "missingArtifactsCount" to missing.size,
    "missingArtifacts" to missing.take(limit.coerceAtLeast(1)).map { "${it.id}@${it.version}" },
    "healthy" to (missing.isEmpty() && (pluginsDir?.let { Files.isDirectory(it) } == true))
  )
  if (json) {
    println(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
  } else {
    println("Snapshot: ${payload["snapshot"]}")
    println("Bundles declared: ${payload["bundleCount"]}")
    println("Bundle pool: ${payload["bundlePool"] ?: "<missing in profile properties>"}")
    println("Bundle pool exists: ${payload["bundlePoolExists"]}")
    println("Plugins dir exists: ${payload["pluginsDirExists"]}")
    println("Missing artifacts: ${payload["missingArtifactsCount"]}")
    (payload["missingArtifacts"] as List<*>).forEach { println("- $it") }
    println("Healthy: ${payload["healthy"]}")
  }
  return 0
}

private fun printApiAnalyzeHelp() {
  println("pde api-analyze ${maturityTag("WIP")} - API analysis commands")
  println()
  println("Usage:")
  println("  pde api-analyze [run] [options]")
  println("  pde api-analyze install [options]")
  println()
  println("Subcommands:")
  println("  run     ${maturityTag("WIP")} Run API analysis (default)")
  println("  install ${maturityTag("WIP")} Provision/update baseline profile for API analysis")
  println()
  println("Examples:")
  println("  pde api-analyze --report build/api-report.json")
  println("  pde api-analyze install --baseline-root target/p2")
  println()
  println("See also:")
  println("  pde api-analyze run --help")
  println("  pde api-filters add-from-report --help")
  println("  pde --help")
}

private fun describeConfig(
  config: LaunchConfigContext,
  targetDefinition: Path?,
  profilePath: Path?,
  targetArgs: TargetLaunchArgs?
) {
  val workspaceCount = WorkspaceModuleResolver.resolveDefinitions(config).size
  val runtime = config.runtime
  logger.info("Loaded launch config from ${config.file}")
  logger.info("  product: ${runtime.product?.takeUnless { it.isBlank() } ?: "<unspecified>"}")
  logger.info("  application: ${runtime.application ?: "<unspecified>"}")
  logger.info("  workspace bundles: $workspaceCount")
  logger.info("  target definition: ${targetDefinition ?: "<unspecified>"}")
  logger.info("  profile path: ${profilePath ?: "<unspecified>"}")
  logger.info("  target vm args: ${targetArgs?.vmArgs?.size ?: 0}")
  logger.info("  target program args: ${targetArgs?.programArgs?.size ?: 0}")
  logActiveExtraBundles(configuredExtraBundles(config))
  if (config.jvmDebug) {
    val requiresTestApp = config.jvmDebugRequiresPdeTestApp
    val isTestApp = runtime.application?.equals(PDE_JUNIT_PLUGIN_TEST_APPLICATION, ignoreCase = true) == true
    val message = if (!requiresTestApp || isTestApp) {
      "enabled (JDWP on port $DEFAULT_TEST_DEBUG_PORT)"
    } else {
      "requested but only applies when application=$PDE_JUNIT_PLUGIN_TEST_APPLICATION"
    }
    logger.info("  jvm debug: $message")
  }
}

private fun configuredExtraBundles(config: LaunchConfigContext): List<String> =
  config.config.target?.extraBundles.orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() }

private fun configuredPinnedVersions(config: LaunchConfigContext): Map<String, Version> =
  config.config.target?.pinnedVersions.orEmpty()
    .mapKeys { it.key.trim() }
    .filterKeys { it.isNotEmpty() }
    .mapValues { (bsn, version) ->
      try {
        Version.parseVersion(version.trim())
      } catch (ex: IllegalArgumentException) {
        error("Invalid target.pinnedVersions entry for '$bsn': '$version'")
      }
    }

private fun logActiveExtraBundles(extraBundles: List<String>) {
  if (extraBundles.isNotEmpty()) {
    logger.info("target.extraBundles active: ${extraBundles.joinToString(", ")}")
  }
}

private fun parseEnvFile(path: Path): Map<String, String> {
  if (!Files.exists(path)) {
    logger.warning("envFile not found: $path")
    return emptyMap()
  }
  return try {
    Files.readAllLines(path, StandardCharsets.UTF_8)
      .mapNotNull(::parseEnvLine)
      .toMap()
  } catch (ex: RuntimeException) {
    logger.warning("Failed to load envFile $path: ${ex.message}")
    emptyMap()
  }
}

private fun parseEnvLine(line: String): Pair<String, String>? {
  val trimmed = line.trim()
  if (trimmed.isEmpty() || trimmed.startsWith('#')) return null
  val assignment = trimmed.removePrefix("export ").trimStart()
  val separator = assignment.indexOf('=')
  if (separator <= 0) return null
  val key = assignment.substring(0, separator).trim()
  if (key.isEmpty()) return null
  val value = parseEnvValue(assignment.substring(separator + 1))
  return key to value
}

private fun parseEnvValue(raw: String): String {
  val trimmed = raw.trim()
  if (trimmed.length >= 2 && (trimmed.first() == '"' || trimmed.first() == '\'')) {
    val quote = trimmed.first()
    val end = trimmed.indexOf(quote, startIndex = 1)
    if (end > 0) return trimmed.substring(1, end)
  }
  return trimmed.replace(Regex("\\s+#.*$"), "").trim()
}

private fun selectLaunchConfig(
  context: LaunchConfigContext,
  launchName: String?,
  debugJvm: Boolean
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
  val fileEnv: Map<String, String> = selected.envFile
    ?.let { context.baseDir.resolve(it) }
    ?.let { parseEnvFile(it) }
    ?: emptyMap()
  val env = fileEnv + selected.env
  val runtime = context.runtime.copy(
    product = selected.product,
    application = selected.application,
    splash = selected.splash,
    vmArgs = selected.vmArgs,
    programArgs = selected.programArgs,
    dataDir = selected.dataDir,
    configDir = selected.configDir,
    workDir = selected.workDir,
    env = env
  )
  if (launchName == null) {
    logger.info("Using default launch '${selected.name}'.")
  } else {
    logger.info("Using launch '${selected.name}'.")
  }
  return context.copy(
    runtime = runtime,
    jvmDebug = debugJvm,
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
  val programArgs = context.runtime.programArgs.toMutableList()
  programArgs.addAll(selected.programArgs)
  if (selected.testPluginName != null && "-testpluginname" !in programArgs) {
    programArgs += listOf("-testpluginname", selected.testPluginName)
  }
  if (selected.className != null && "-classname" !in programArgs) {
    programArgs += listOf("-classname", selected.className)
  }
  val normalizedRunner = selected.runner?.trim()?.lowercase()
    ?: error("Missing required test runner in config test entry '${testLabel(selected)}'")
  fun hasFlag(flag: String): Boolean = programArgs.indexOf(flag) != -1
  when (normalizedRunner) {
    "junit5" -> {
      if (!hasFlag("-testLoaderClass")) {
        programArgs += listOf("-testLoaderClass", "org.eclipse.jdt.internal.junit5.runner.JUnit5TestLoader")
      }
      if (!hasFlag("-loaderpluginname")) {
        programArgs += listOf("-loaderpluginname", "org.eclipse.jdt.junit5.runtime")
      }
    }
    "junit4" -> {
      if (!hasFlag("-testLoaderClass")) {
        programArgs += listOf("-testLoaderClass", "org.eclipse.jdt.internal.junit4.runner.JUnit4TestLoader")
      }
      if (!hasFlag("-loaderpluginname")) {
        programArgs += listOf("-loaderpluginname", "org.eclipse.jdt.junit4.runtime")
      }
    }
    else -> logger.warning("Unknown test runner '${selected.runner}'.")
  }
  val vmArgs = context.runtime.vmArgs + selected.vmArgs
  val runtime = context.runtime.copy(
    product = null,
    application = PDE_JUNIT_PLUGIN_TEST_APPLICATION,
    splash = null,
    vmArgs = vmArgs,
    programArgs = programArgs,
    dataDir = selected.dataDir ?: context.runtime.dataDir,
    configDir = selected.configDir ?: context.runtime.configDir,
    workDir = selected.workDir ?: context.runtime.workDir
  )
  val withDebug = context.copy(
    runtime = runtime,
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
  showLogPathWhenDebugging: Boolean,
  logFile: Path?,
  extraProgramArgs: List<String> = emptyList(),
  includeDevProperties: Boolean = true,
  preLaunch: ((PreparedLaunch) -> Unit)? = null
) {
  val prepared = prepareLaunch(context, targetArgs, extraProgramArgs, includeDevProperties)
  preLaunch?.invoke(prepared)
  logPlanSummary(prepared.planResult)
  if (showLogPathWhenDebugging) {
    val logPath = prepared.layout.dataDir.resolve(".metadata").resolve(".log").toAbsolutePath().normalize()
    logger.info("OSGi log path: $logPath")
  }
  logCommand(prepared.command)
  val processBuilder = ProcessBuilder(prepared.command)
  if (context.runtime.env.isNotEmpty()) {
    processBuilder.environment().putAll(context.runtime.env)
  }
  val outputLog = logFile?.toAbsolutePath()?.normalize()
  if (outputLog != null) {
    outputLog.parent?.let { Files.createDirectories(it) }
    processBuilder.redirectErrorStream(true)
    processBuilder.redirectOutput(outputLog.toFile())
  } else {
    processBuilder.redirectErrorStream(true)
  }
  val process = processBuilder
    .directory(prepared.layout.workDir.toFile())
    .start()
  val outputThread = if (outputLog == null) {
    Thread {
      process.inputStream.use { input -> input.copyTo(System.out) }
    }.also { thread ->
      thread.isDaemon = true
      thread.start()
    }
  } else {
    null
  }
  val exit = process.waitFor()
  outputThread?.join()
  if (isSuccessfulCracCheckpointExit(exit, prepared.command, prepared.layout.workDir)) {
    logger.info("Checkpoint completed; launcher exited with CRaC checkpoint code $exit")
    return
  }
  if (exit != 0) error("Launcher exited with code $exit")
}

private fun isSuccessfulCracCheckpointExit(exit: Int, command: List<String>, workDir: Path): Boolean {
  if (exit != CRAC_CHECKPOINT_EXIT_CODE) return false
  val checkpointDir = cracCheckpointDirectory(command, workDir) ?: return false
  return hasCheckpointImageFiles(checkpointDir)
}

private fun cracCheckpointDirectory(command: List<String>, workDir: Path): Path? {
  for (index in command.indices) {
    val rawPath = when {
      command[index].startsWith(CRAC_CHECKPOINT_ARG_PREFIX) -> command[index].removePrefix(CRAC_CHECKPOINT_ARG_PREFIX)
      command[index] == "-XX:CRaCCheckpointTo" -> command.getOrNull(index + 1)
      else -> null
    }?.takeIf { it.isNotBlank() } ?: continue
    val path = Paths.get(rawPath)
    return if (path.isAbsolute) path.normalize() else workDir.resolve(path).normalize()
  }
  return null
}

private fun hasCheckpointImageFiles(checkpointDir: Path): Boolean {
  if (!Files.isDirectory(checkpointDir)) return false
  return Files.newDirectoryStream(checkpointDir).use { entries ->
    entries.iterator().hasNext()
  }
}

private fun writePdeTargetPreferences(layout: LaunchLayout, targetDefinition: Path) {
  val settingsDirs = listOf(
    layout.dataDir
      .resolve(".metadata")
      .resolve(".plugins")
      .resolve("org.eclipse.core.runtime")
      .resolve(".settings"),
    layout.dataDir
      .resolve(".metadata")
      .resolve(".plugins")
      .resolve("org.eclipse.pde.core")
      .resolve(".settings")
  )
  val targetHandle = targetDefinition.toUri().toString()
  settingsDirs.forEach { settingsDir ->
    Files.createDirectories(settingsDir)
    val prefsFile = settingsDir.resolve("org.eclipse.pde.core.prefs")
    Files.newBufferedWriter(prefsFile, StandardCharsets.UTF_8).use { writer ->
      writer.write("eclipse.preferences.version=1")
      writer.newLine()
      writer.write("workspace_target_handle=$targetHandle")
      writer.newLine()
    }
    logger.info("Wrote PDE target preferences to ${prefsFile.toAbsolutePath().normalize()}")
  }
}

private fun writeBundlePoolTarget(outputRoot: Path, bundlePool: Path): Path? {
  val pluginsDir = when {
    Files.isDirectory(bundlePool.resolve("plugins")) -> bundlePool.resolve("plugins")
    Files.isDirectory(bundlePool) -> bundlePool
    else -> null
  } ?: run {
    logger.warning("Bundle pool path does not exist: ${bundlePool.toAbsolutePath().normalize()}")
    return null
  }
  val targetFile = outputRoot.resolve("bundle-pool.target")
  Files.createDirectories(targetFile.parent)
  Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8).use { writer ->
    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>")
    writer.newLine()
    writer.write("<?pde version=\"3.8\"?>")
    writer.newLine()
    writer.write("<target name=\"Bundle Pool Target\" sequenceNumber=\"1\">")
    writer.newLine()
    writer.write("  <locations>")
    writer.newLine()
    writer.write("    <location type=\"Directory\" includeAllPlatforms=\"false\" includeSource=\"true\" path=\"")
    writer.write(pluginsDir.toAbsolutePath().normalize().toString())
    writer.write("\"/>")
    writer.newLine()
    writer.write("  </locations>")
    writer.newLine()
    writer.write("</target>")
    writer.newLine()
  }
  logger.info("Wrote bundle pool target to ${targetFile.toAbsolutePath().normalize()}")
  return targetFile
}

private fun prepareBaselineWorkspace(outputRoot: Path, baselineTarget: Path): Pair<Path, Path> {
  val baselineWorkspace = outputRoot.resolve("baseline-workspace")
  Files.createDirectories(baselineWorkspace)
  val targetCopy = baselineWorkspace.resolve(baselineTarget.fileName)
  Files.copy(baselineTarget, targetCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  return baselineWorkspace to targetCopy
}

private fun cleanBaselineWorkspace(workspaceDir: Path) {
  val pluginsDir = workspaceDir.resolve(".metadata").resolve(".plugins")
  val resourceDir = pluginsDir.resolve("org.eclipse.core.resources")
  val jdtDir = pluginsDir.resolve("org.eclipse.jdt.core")
  if (Files.exists(resourceDir)) {
    resourceDir.toFile().deleteRecursively()
  }
  if (Files.exists(jdtDir)) {
    jdtDir.toFile().deleteRecursively()
  }
}

private fun copyDirectory(source: Path, target: Path) {
  if (Files.exists(target)) {
    target.toFile().deleteRecursively()
  }
  Files.walk(source).use { stream ->
    stream.forEach { path ->
      val relative = source.relativize(path)
      val destination = target.resolve(relative)
      if (Files.isDirectory(path)) {
        Files.createDirectories(destination)
      } else {
        destination.parent?.let { Files.createDirectories(it) }
        Files.copy(path, destination)
      }
    }
  }
}

private fun updateJdtPrefs(projectDir: Path, compliance: String) {
  val prefs = projectDir.resolve(".settings").resolve("org.eclipse.jdt.core.prefs")
  if (!Files.exists(prefs)) return
  val updated = Files.readString(prefs, StandardCharsets.UTF_8)
    .replace("org.eclipse.jdt.core.compiler.compliance=21", "org.eclipse.jdt.core.compiler.compliance=$compliance")
    .replace("org.eclipse.jdt.core.compiler.source=21", "org.eclipse.jdt.core.compiler.source=$compliance")
    .replace("org.eclipse.jdt.core.compiler.codegen.targetPlatform=21", "org.eclipse.jdt.core.compiler.codegen.targetPlatform=$compliance")
  Files.writeString(prefs, updated, StandardCharsets.UTF_8)
}

private fun updateClasspathJre(projectDir: Path, compliance: String) {
  val classpath = projectDir.resolve(".classpath")
  if (!Files.exists(classpath)) return
  val updated = Files.readString(classpath, StandardCharsets.UTF_8)
    .replace("/JavaSE-21", "/JavaSE-$compliance")
  Files.writeString(classpath, updated, StandardCharsets.UTF_8)
}


private fun runApiAnalyzer(
  launcherExecutable: Path,
  dataDir: String,
  applicationId: String,
  args: List<String>,
  logFile: Path?
): Int {
  val command = mutableListOf(
    launcherExecutable.toString(),
    "-nosplash",
    "-consoleLog",
    "-data",
    dataDir,
    "-application",
    applicationId
  )
  command.addAll(args)
  val process = ProcessBuilder(command).apply {
    redirectErrorStream(true)
    if (logFile != null) {
      logFile.parent?.let { Files.createDirectories(it) }
      redirectOutput(logFile.toFile())
    } else {
      inheritIO()
    }
  }.start()
  val exitCode = process.waitFor()
  if (exitCode != 0) {
    logger.severe("API analyzer exited with code $exitCode")
  }
  return exitCode
}

private fun resolveP2DirectorLauncher(
  installPath: Path?,
  installerPath: Path?,
  targetDefinition: Path?
): Path? {
  val localP2Director = Paths.get("/home/ben/Desktop/ap-dev-setup/p2-director/p2-director")
  if (Files.exists(localP2Director)) {
    return localP2Director
  }
  val fallbackEclipse = Paths.get("/home/ben/eclipse/eclipse")
  if (Files.exists(fallbackEclipse)) {
    return fallbackEclipse
  }
  val candidates = mutableListOf<Path>()
  if (installPath != null) {
    candidates.add(installPath.resolve("eclipse"))
    candidates.add(installPath.resolve("launcher"))
    candidates.add(installPath.resolve("target").resolve("install").resolve("eclipse"))
    candidates.add(installPath.resolve("target").resolve("install").resolve("launcher"))
  }
  val installerRoot = when {
    installerPath == null -> null
    Files.isDirectory(installerPath) -> installerPath
    Files.isRegularFile(installerPath) -> installerPath.parent
    else -> installerPath
  }
  if (installerRoot != null) {
    candidates.add(installerRoot.resolve("eclipse"))
    candidates.add(installerRoot.resolve("launcher"))
    candidates.add(installerRoot.resolve("target").resolve("install").resolve("eclipse"))
    candidates.add(installerRoot.resolve("target").resolve("install").resolve("launcher"))
  }
  candidates.firstOrNull { Files.exists(it) }?.let { return it }

  val roots = buildList {
    if (installPath != null) add(installPath)
    if (installerRoot != null) {
      add(installerRoot)
      add(installerRoot.resolve("target"))
    }
    if (targetDefinition != null) {
      targetDefinition.parent?.let { add(it) }
    }
  }
  roots.forEach { root ->
    if (Files.isDirectory(root)) {
      Files.newDirectoryStream(root).use { stream ->
        stream.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("standalone-install-") }
          .map { it.resolve("eclipse") }
          .filter { Files.exists(it) }
          .sortedBy { it.toAbsolutePath().normalize().toString() }
          .lastOrNull()
          ?.let { return it }
      }
    }
  }
  return null
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
      val hints = buildResolutionHints(planResult)
      if (hints.isNotEmpty()) {
        appendLine("Potential root causes:")
        hints.forEach { hint -> appendLine("  - $hint") }
      }
    }
    logger.warning(
      ("Launch plan has unresolved bundles/dependencies; continuing anyway.\n$details").trim()
    )
  }
}

private fun buildResolutionHints(planResult: LaunchPlanner.PlanResult): List<String> {
  val workspaceByBsn = planResult.selectedBundles
    .filter { it.isWorkspace }
    .associateBy { it.bsn }
  if (workspaceByBsn.isEmpty()) return emptyList()

  val hints = linkedSetOf<String>()
  planResult.problemsByScope.forEach { (scope, problems) ->
    problems.forEach { problem ->
      val workspaceBundle = workspaceByBsn[problem.symbol] ?: return@forEach
      val requestedRange = problem.range ?: return@forEach
      if (!requestedRange.includes(workspaceBundle.version)) {
        val firstHop = when {
          scope == "Launch Plan" -> ""
          scope == problem.symbol -> ""
          else -> "First hop: $scope -> ${problem.symbol} $requestedRange. "
        }
        hints +=
          firstHop +
            "Workspace override for ${problem.symbol} in scope '$scope' does not satisfy $requestedRange " +
            "(selected ${workspaceBundle.version} from ${workspaceBundle.path})."
      }
    }
  }
  return hints.toList()
}

private fun applyOsgiDebug(context: LaunchConfigContext, osgiDebug: Boolean): LaunchConfigContext {
  if (!osgiDebug) return context
  val programArgs = context.runtime.programArgs.toMutableList()
  if (!programArgs.contains("-debug")) {
    programArgs += "-debug"
  }
  return context.copy(runtime = context.runtime.copy(programArgs = programArgs))
}


private fun collectBundlePaths(targetIndex: TargetPlatformIndex, excludedBsns: Set<String> = emptySet()): List<Path> {
  return targetIndex.bundlesByBsn()
    .entries
    .flatMap { (bsn, nav) ->
      if (excludedBsns.contains(bsn)) emptyList() else nav.values
    }
    .filter { it.manifest.eclipseSourceBundle == null }
    .map { it.location.toAbsolutePath().normalize() }
    .distinct()
    .sortedBy { it.toString() }
}

private fun writePathList(output: Path, entries: List<Path>) {
  output.toAbsolutePath().normalize().parent?.let { Files.createDirectories(it) }
  Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { writer ->
    entries.forEach { entry ->
      writer.write(entry.toString())
      writer.newLine()
    }
  }
}

internal fun expandBundleClassPathEntries(entries: List<Path>): List<Path> {
  val expanded = linkedSetOf<Path>()
  entries.forEach { entry ->
    val normalizedEntry = entry.toAbsolutePath().normalize()
    expanded.add(normalizedEntry)
    if (!Files.isDirectory(normalizedEntry)) {
      return@forEach
    }
    val manifestPath = normalizedEntry.resolve("META-INF").resolve("MANIFEST.MF")
    if (!Files.isRegularFile(manifestPath)) {
      return@forEach
    }
    val bundleClassPath = runCatching {
      Files.newInputStream(manifestPath).use { input ->
        Manifest(input).mainAttributes.getValue("Bundle-ClassPath")
      }
    }.getOrNull() ?: return@forEach
    bundleClassPath.split(',')
      .map { it.trim() }
      .filter { it.isNotEmpty() && it != "." }
      .forEach { classPathEntry ->
        val classPathPath = normalizedEntry.resolve(classPathEntry).normalize()
        if (Files.exists(classPathPath)) {
          expanded.add(classPathPath)
        }
      }
  }
  return expanded.toList()
}

private fun prepareLaunch(
  context: LaunchConfigContext,
  targetArgs: TargetLaunchArgs?,
  extraProgramArgs: List<String> = emptyList(),
  includeDevProperties: Boolean = true
): PreparedLaunch {
  val workspaceInputs = WorkspaceModuleResolver.resolve(context)
  val profilePath = resolveProfilePath(context)
    ?: error(
      "Target profile path missing in YAML config. Add a target section to pde.yaml " +
        "(set target.profileId + target.p2Path). See docs/config-yaml.md#target."
    )
  if (!Files.exists(profilePath)) {
    error("profilePath does not exist: $profilePath (check launch.yaml or export the profile)")
  }
  val targetIndex = TargetPlatformCache.buildWithCache(listOf(profilePath))
  val layoutResolution = LaunchLayoutResolver.resolve(context)
  val layout = layoutResolution.layout
  LaunchLayoutResolver.cleanIfRequested(layout, false)
  val targetDefinitionStartupLevels = loadTargetDefinitionStartupLevels(context)
  val requestedStartupLevels = DEFAULT_STARTUP_LEVELS
  val combinedStartup = DEFAULT_STARTUP_LEVELS +
    targetDefinitionStartupLevels +
    requestedStartupLevels
  val resolverWhitelist = DEFAULT_WHITELIST
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
  val extraBundles = configuredExtraBundles(context)
  val pinnedVersions = configuredPinnedVersions(context)
  val env = LaunchEnvironment(
    targetIndex = targetIndex,
    workspaceEntries = workspaceEntries,
    devProperties = devProperties,
    libraryBundles = supplementalBundles,
    resolverOptions = ResolveOptions(
      whitelistPrefixes = resolverWhitelist,
      preferWorkspace = hasWorkspaceModules,
      includeHostsForFragments = true,
      pinnedVersions = pinnedVersions,
      extraBundles = extraBundles
    ),
    requiredStartupBundles = combinedStartup.keys,
    startupLevels = combinedStartup,
    autoStartBundles = combinedStartup.keys.associateWith { true }
  )
  val productId = context.runtime.product?.takeUnless { it.isBlank() }
  val options = LauncherOptions(
    product = productId,
    application = context.runtime.application,
    splashBSN = context.runtime.splash,
    autoStartDefault = false
  )
  val planResult = LaunchPlanner.build(env, options)
  val fallbackConfig = loadExistingConfig(profilePath)
  val extraProperties = buildMap {
    val splash = context.runtime.splash?.takeIf { it.isNotBlank() }
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
  val command = assembleCommand(
    context,
    layout,
    targetArgs,
    planResult,
    launcherJar,
    extraProgramArgs,
    includeDevProperties
  )
  return PreparedLaunch(command, planResult, layout)
}

private fun assembleCommand(
  context: LaunchConfigContext,
  layout: LaunchLayout,
  targetArgs: TargetLaunchArgs?,
  planResult: LaunchPlanner.PlanResult,
  launcherJar: Path,
  extraProgramArgs: List<String> = emptyList(),
  includeDevProperties: Boolean = true
): List<String> {
  val javaBin = resolveJavaBin(context.runtime.env)
  val configVmArgs = expandArgs(context.runtime.vmArgs)
  val configProgramArgs = expandArgs(context.runtime.programArgs)
  val vmArgs = mutableListOf<String>().apply {
    addAll(targetArgs?.vmArgs ?: emptyList())
    addAll(configVmArgs)
    addAll(buildDebugVmArgs(context, this))
  }
  val programArgs = mutableListOf<String>().apply {
    addAll(targetArgs?.programArgs ?: emptyList())
    addAll(configProgramArgs)
  }
  val stdArgs = mutableListOf<String>().apply {
    if (context.clean) {
      add("-clean")
    }
    addAll(listOf(
      "-os", currentOsgiOs(),
      "-ws", currentOsgiWs(),
      "-arch", currentOsgiArch(),
      "-nl", "en_GB"
    ))
  }
  if (context.clean && context.runtime.configDir != null) {
    logger.info("Using -clean with persistent configDir '${context.runtime.configDir}'; OSGi framework state will be rebuilt.")
  }
  val launchArgs = mutableListOf<String>().apply {
    addAll(programArgs)
    addAll(stdArgs)
    add("-name")
    add("Eclipse")
    context.runtime.splash?.takeIf { it.isNotBlank() }?.let {
      add("-showsplash")
      add(it)
    }
    add("-application")
    add(context.runtime.application ?: error("application missing"))
    context.runtime.product?.takeUnless { it.isBlank() }?.let {
      add("-product")
      add(it)
    }
    add("-data")
    add(layout.dataDir.toString())
    add("-configuration")
    add(layout.configDir.toUri().toString())
    if (includeDevProperties) {
      add("-dev")
      add(layout.devPropertiesFile.toUri().toString())
    }
    add("-consoleLog")
    addAll(extraProgramArgs)
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
    !context.runtime.application.equals(PDE_JUNIT_PLUGIN_TEST_APPLICATION, ignoreCase = true)
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
  val bsn = context.runtime.product ?: context.runtime.application ?: return null
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

private fun loadTargetDefinitionStartupLevels(context: LaunchConfigContext): Map<String, Int> {
  val defaultYaml = context.baseDir.resolve("startupLevels.yaml")
  val defaultYml = context.baseDir.resolve("startupLevels.yml")
  val legacyXml = context.baseDir.resolve("startupLevels.xml")
  val candidates = listOf(defaultYaml, defaultYml, legacyXml).distinct()
  candidates.forEach { candidate ->
    TargetDefinitionStartupParser.parse(candidate)?.let { return it }
  }
  return emptyMap()
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

fun discoverConfigFile(baseDir: Path = Paths.get("").toAbsolutePath()): Path? {
  val candidates = listOf(
    "pde.yaml",
    "launch.yaml",
    "launch.yml",
    "pde-launch.yaml",
    "pde-launch.yml"
  )
  return candidates
    .map { baseDir.resolve(it) }
    .firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
}

private fun resolveTargetArgs(
  context: LaunchConfigContext,
  targetDefinition: Path? = resolveTargetDefinition(context)
): TargetLaunchArgs? {
  val targetArgs = if (targetDefinition != null) {
    runCatching { TargetFileParser.parse(targetDefinition) }
      .onFailure { logger.warning("Failed to parse target file args: ${it.message}") }
      .getOrNull()
  } else null
  if (targetDefinition == null) {
    logger.warning("target.definition is not set; skipping target argument import.")
  }
  return targetArgs
}

private fun resolveTargetDefinition(context: LaunchConfigContext): Path? {
  val baseDir = context.baseDir
  val targetConfig = context.config.target
  val targetDefinition = targetConfig?.definition?.takeUnless { it.isBlank() }
    ?.let { baseDir.resolve(it).normalize() }
  if (targetDefinition != null) return targetDefinition
  val discovered = discoverTargetDefinition(baseDir)
  if (discovered != null) {
    logger.info("Discovered target definition at ${discovered.toAbsolutePath()}")
  }
  return discovered
}

private fun discoverTargetDefinition(baseDir: Path): Path? {
  if (!Files.isDirectory(baseDir)) return null
  val matches = Files.newDirectoryStream(baseDir, "*.target").use { stream ->
    stream.toList()
  }
  return when {
    matches.isEmpty() -> null
    matches.size == 1 -> matches.first().toAbsolutePath().normalize()
    else -> error("Multiple .target files found in $baseDir; set target.definition explicitly.")
  }
}

private fun resolveProfilePath(context: LaunchConfigContext): Path? {
  val baseDir = context.baseDir
  val targetConfig = context.config.target
  if (targetConfig == null) return null
  val profileId = targetConfig.profileId?.takeUnless { it.isBlank() }
    ?: error("Missing target.profileId after schema validation")
  val p2Path = targetConfig.p2Path?.takeUnless { it.isBlank() }
    ?: error("Missing target.p2Path after schema validation")
  val registryDir = baseDir.resolve(p2Path)
    .resolve("org.eclipse.equinox.p2.engine/profileRegistry")
    .normalize()
  val preferred = registryDir.resolve("$profileId.Profile").normalize()
  if (Files.exists(preferred)) return preferred
  val lowercase = registryDir.resolve("$profileId.profile").normalize()
  if (Files.exists(lowercase)) {
    logger.warning("Using lowercase profile registry path: $lowercase")
    return lowercase
  }
  return preferred
}

private fun copyStringToClipboard(value: String): Boolean {
  return try {
    if (GraphicsEnvironment.isHeadless()) {
      return false
    }
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val selection = StringSelection(value)
    clipboard.setContents(selection, selection)
    true
  } catch (ex: Throwable) {
    false
  }
}

private fun resolveTargetInstallerJar(context: LaunchConfigContext): Path? {
  val configured = context.config.target?.installer?.takeUnless { it.isBlank() }
  if (configured != null) {
    return context.baseDir.resolve(configured).normalize()
  }
  System.getProperty(TARGET_INSTALLER_OVERRIDE_PROPERTY)?.takeUnless { it.isBlank() }?.let {
    return Paths.get(it).toAbsolutePath().normalize()
  }
  return findBundledTargetInstallerJar()
}

private fun findBundledTargetInstallerJar(): Path? {
  val classPath = System.getProperty("java.class.path").orEmpty()
  return classPath.split(File.pathSeparatorChar)
    .asSequence()
    .mapNotNull { entry -> entry.takeUnless { it.isBlank() }?.let(Paths::get) }
    .flatMap { entry ->
      sequenceOf(
        entry.takeIf { it.fileName?.toString() == TARGET_INSTALLER_LAUNCHER_JAR },
        entry.parent?.resolve(TARGET_INSTALLER_LAUNCHER_JAR)
      ).filterNotNull()
    }
    .firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
    ?.toAbsolutePath()
    ?.normalize()
}

private fun validateTargetInstallerJar(installerJar: Path, missingMessage: String): Boolean {
  if (Files.isDirectory(installerJar)) {
    logger.severe("target.installer must point to the target-installer launcher jar, not a directory: $installerJar")
    return false
  }
  if (!Files.exists(installerJar)) {
    logger.severe(missingMessage)
    return false
  }
  if (!installerJar.toString().lowercase(Locale.ROOT).endsWith(".jar")) {
    logger.severe("target.installer must be a launcher jar: $installerJar")
    return false
  }
  return true
}

internal fun buildTargetInstallInputs(
  context: LaunchConfigContext,
  installerJar: Path,
  targetDefinition: Path
): TargetInstallInputs {
  val targetConfig = context.config.target
    ?: error("Missing target config while building target install inputs. See docs/config-yaml.md#target.")
  val baseDir = context.baseDir
  val profileId = targetConfig.profileId?.takeUnless { it.isBlank() }
    ?: error("Missing target.profileId after schema validation")
  val p2Path = targetConfig.p2Path?.takeUnless { it.isBlank() }
    ?: error("Missing target.p2Path after schema validation")
  val installPath = targetConfig.install?.takeUnless { it.isBlank() }
    ?: error("Missing target.install after schema validation")
  val bundlePool = targetConfig.bundlePool?.takeUnless { it.isBlank() }
    ?: error("Missing target.bundlePool after schema validation")
  val resolvedP2 = baseDir.resolve(p2Path).normalize()
  val resolvedInstall = baseDir.resolve(installPath).normalize()
  val resolvedBundlePool = baseDir.resolve(bundlePool).normalize()
  val targetContents = TargetFileParser.parseContents(targetDefinition)
  return TargetInstallInputs(
    profileId = profileId,
    p2Path = resolvedP2,
    targetDefinition = targetDefinition,
    installRoot = resolvedInstall,
    bundlePool = resolvedBundlePool,
    installerJar = installerJar.toAbsolutePath().normalize(),
    includeConfigurePhase = targetContents.includeConfigurePhase
  )
}

private fun validateTargetDefinition(targetDefinition: Path, configFile: Path): Boolean {
  if (Files.isDirectory(targetDefinition)) {
    logger.severe("target.definition must point to a .target file, not a directory: $targetDefinition")
    return false
  }
  if (!Files.exists(targetDefinition)) {
    logger.severe("target.definition does not exist: $targetDefinition (from $configFile)")
    return false
  }
  if (!targetDefinition.toString().lowercase(Locale.ROOT).endsWith(".target")) {
    logger.severe("target.definition must point to a .target file: $targetDefinition")
    return false
  }
  return true
}

private fun buildTargetInstallerArgs(
  profileId: String,
  p2Path: Path,
  targetDefinition: Path,
  installPath: Path,
  bundlePool: Path,
  includeConfigurePhase: Boolean = true
): List<String> {
  return listOf(
    "-profileId", profileId,
    "-p2Path", p2Path.toString(),
    "-targetDefinition", targetDefinition.toString(),
    "-install-folder", installPath.toString(),
    "-bundlePool", bundlePool.toString(),
    "-includeConfigurePhase", includeConfigurePhase.toString()
  )
}

private fun runTargetInstallerLauncher(
  installerJar: Path,
  installerArgs: List<String>,
  workingDir: Path,
  logFile: Path?
): Int {
  val javaBin = resolveJavaBin()
  val command = mutableListOf(
    javaBin,
    "-jar",
    installerJar.toString(),
    "--cache=persistent",
    "--"
  ).apply { addAll(installerArgs) }
  logCommand(command)
  val processBuilder = ProcessBuilder(command)
    .directory(workingDir.toFile())
    .redirectErrorStream(true)
  if (logFile != null) {
    val outputLog = logFile.toAbsolutePath().normalize()
    outputLog.parent?.let { Files.createDirectories(it) }
    processBuilder.redirectOutput(outputLog.toFile())
  } else {
    processBuilder.inheritIO()
  }
  return processBuilder.start().waitFor()
}

private fun provisionBaselineTargetProfile(
  context: LaunchConfigContext,
  outputRoot: Path,
  baselineTargetDefinition: Path,
  logFile: Path?
): Path? {
  val targetConfig = context.config.target
  if (targetConfig == null) {
    logger.severe("Missing target config; cannot provision baseline target definition for api-analyze.")
    return null
  }
  val installerJar = resolveTargetInstallerJar(context)
  if (installerJar == null) {
    logger.severe("target.installer is required to use a .target baseline in api-analyze unless the packaged target installer is available.")
    return null
  }
  if (!validateTargetInstallerJar(installerJar, "target.installer does not exist: $installerJar")) {
    return null
  }
  val bundlePoolValue = targetConfig.bundlePool?.takeUnless { it.isBlank() }
    ?: run {
      logger.severe("target.bundlePool is required to use a .target baseline in api-analyze.")
      return null
    }
  val bundlePoolPath = context.baseDir.resolve(bundlePoolValue).normalize()
  Files.createDirectories(bundlePoolPath)

  val baselineInstallRoot = outputRoot.resolve("baseline-target")
  val baselineP2Path = baselineInstallRoot.resolve("p2")
  val baselineInstallPath = baselineInstallRoot.resolve("install")
  val baselineProfileId = API_ANALYZER_BASELINE_PROFILE_ID
  val baselineTargetContents = runCatching { TargetFileParser.parseContents(baselineTargetDefinition) }
    .getOrElse { error ->
      logger.severe("Failed to parse baseline target definition $baselineTargetDefinition: ${error.message}")
      return null
    }

  val installerArgs = buildTargetInstallerArgs(
    profileId = baselineProfileId,
    p2Path = baselineP2Path,
    targetDefinition = baselineTargetDefinition,
    installPath = baselineInstallPath,
    bundlePool = bundlePoolPath,
    includeConfigurePhase = baselineTargetContents.includeConfigurePhase
  )
  val exitCode = runTargetInstallerLauncher(
    installerJar = installerJar,
    installerArgs = installerArgs,
    workingDir = context.baseDir,
    logFile = logFile
  )
  if (exitCode != 0) {
    logger.severe("Target installer exited with code $exitCode while provisioning baseline target.")
    return null
  }

  val baselineContext = context.copy(
    config = context.config.copy(
      target = targetConfig.copy(
        profileId = baselineProfileId,
        p2Path = baselineP2Path.toString()
      )
    )
  )
  val profilePath = resolveProfilePath(baselineContext)
  if (profilePath == null || !Files.exists(profilePath)) {
    logger.severe("Provisioned baseline profile path does not exist: ${profilePath?.toAbsolutePath()?.normalize()}")
    return null
  }
  return profilePath
}

private fun resolveApiAnalyzeBaselineRootPath(
  context: LaunchConfigContext,
  baselineRootValue: String?,
  profilePath: Path
): Path {
  val targetConfig = context.config.target
  fun resolveTargetConfigPath(path: String): Path = context.baseDir.resolve(path).normalize()
  return when {
    baselineRootValue != null -> Paths.get(baselineRootValue)
    !targetConfig?.apiBaselineRoot.isNullOrBlank() -> {
      logger.info("Using target.apiBaselineRoot from config as baseline root.")
      resolveTargetConfigPath(targetConfig.apiBaselineRoot)
    }
    !targetConfig?.install.isNullOrBlank() -> {
      logger.info("Using target.install from config as baseline root.")
      resolveTargetConfigPath(targetConfig.install)
    }
    !targetConfig?.p2Path.isNullOrBlank() -> {
      logger.info("Using target.p2Path from config as baseline root.")
      resolveTargetConfigPath(targetConfig.p2Path)
    }
    else -> {
      logger.info("Using target profile path as baseline root.")
      profilePath
    }
  }
}

private fun resolveApiAnalyzeProvisionedBaselineProfilePath(
  context: LaunchConfigContext,
  outputRoot: Path
): Path? {
  val targetConfig = context.config.target ?: return null
  val baselineP2Path = outputRoot.resolve("baseline-target").resolve("p2")
  val baselineContext = context.copy(
    config = context.config.copy(
      target = targetConfig.copy(
        profileId = API_ANALYZER_BASELINE_PROFILE_ID,
        p2Path = baselineP2Path.toString()
      )
    )
  )
  return resolveProfilePath(baselineContext)
}

private fun apiAnalyzeInstallMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, apiAnalyzeOptionsRequiringValue)
  val parser = ArgParser("pde api-analyze install ${maturityTag("WIP")}")
  val configFileOpt by parser.option(
    ArgType.String,
    fullName = "config",
    description = "Path to launch config YAML"
  )
  val logLevelOpt by parser.option(
    ArgType.String,
    fullName = "log-level",
    description = "Log level (trace, debug, info, warn, error)"
  )
  val logFileOpt by parser.option(
    ArgType.String,
    fullName = "log",
    description = "Redirect launcher output to file"
  )
  val verboseOpt by parser.option(
    ArgType.Boolean,
    shortName = "v",
    fullName = "verbose",
    description = "Enable verbose logging"
  ).default(false)
  val debugOpt by parser.option(
    ArgType.Boolean,
    fullName = "debug",
    description = "Enable debug logging"
  ).default(false)
  val baselineRootOpt by parser.option(
    ArgType.String,
    fullName = "baseline-root",
    description = "Baseline target root, profile path, or .target file (defaults to target.apiBaselineRoot, target.install, target.p2Path, or target profile)"
  )
  val configPosOpt by parser.argument(
    ArgType.String,
    description = "Launch config YAML"
  ).optional()

  parser.parse(normalizedArgs)
  configureLogging(resolveLogLevel(logLevelOpt, verboseOpt, debugOpt), shouldUseColor())

  val configFile = configFileOpt ?: configPosOpt?.takeIf { looksLikeYamlFile(it) }
  val resolvedConfigFile = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  if (resolvedConfigFile == null) {
    logger.severe("Missing --config and no launch config discovered in current directory.")
    return 2
  }
  if (configFile == null) {
    logger.info("Discovered launch config in ${resolvedConfigFile.toAbsolutePath().normalize()} and will use it.")
  }

  val apiContext = LaunchConfigLoader.load(resolvedConfigFile)
  val outputRoot = apiContext.file.parent?.resolve("api-analyzer") ?: Paths.get("api-analyzer")
  val profilePath = resolveProfilePath(apiContext)
  if (profilePath == null) {
    logger.severe("No target profile path resolved from config.")
    return 2
  }

  val baselineRootPath = resolveApiAnalyzeBaselineRootPath(apiContext, baselineRootOpt, profilePath)
  if (!Files.exists(baselineRootPath)) {
    logger.severe("Baseline root path does not exist: ${baselineRootPath.toAbsolutePath().normalize()}")
    return 2
  }
  val baselineTargetDefinition = baselineRootPath.takeIf { it.toString().lowercase(Locale.ROOT).endsWith(".target") }
  if (baselineTargetDefinition == null) {
    logger.info("Baseline root is not a .target file; nothing to provision.")
    return 0
  }

  logger.info("Provisioning baseline target definition via target installer and configured bundle pool.")
  val provisioned = provisionBaselineTargetProfile(
    context = apiContext,
    outputRoot = outputRoot,
    baselineTargetDefinition = baselineTargetDefinition,
    logFile = logFileOpt?.let { Paths.get(it) }
  ) ?: return 2
  logger.info("Provisioned baseline profile: ${provisioned.toAbsolutePath().normalize()}")
  return 0
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
  quiet: Boolean,
  logFile: Path?,
  debug: Boolean
): Int {
  val useColor = shouldUseColor()
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
      "Example: pde run --programArg \"-port ${server.localPort}\""
    ),
    issuedAt = Instant.now().toString()
  )
  logger.info(jsonMapper.writeValueAsString(announcement))
  logger.info("Listening on ${announcement.host}:${announcement.port}")
  logger.info("Waiting up to ${timeoutSeconds}s for RemoteTestRunner connection...")

  logActiveExtraBundles(configuredExtraBundles(configContext))
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
  if (configContext.jvmDebug) {
    logger.info("Waiting for debugger to attach on port $DEFAULT_TEST_DEBUG_PORT...")
  }
  logCommand(prepared.command)
  val processBuilder = ProcessBuilder(prepared.command)
    .directory(prepared.layout.workDir.toFile())
  if (configContext.runtime.env.isNotEmpty()) {
    processBuilder.environment().putAll(configContext.runtime.env)
  }
  val outputLog = logFile?.toAbsolutePath()?.normalize()
  if (outputLog != null) {
    outputLog.parent?.let { Files.createDirectories(it) }
    processBuilder.redirectErrorStream(true)
    processBuilder.redirectOutput(outputLog.toFile())
  } else {
    processBuilder.redirectErrorStream(true)
    processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
  }
  val process = processBuilder.start()

  val client = try {
    server.accept()
  } catch (timeout: SocketTimeoutException) {
    logger.severe("Timed out waiting for PDE connection after ${timeoutSeconds}s")
    server.close()
    process.destroy()
    return 3
  }
  logger.info("Connection established from ${client.inetAddress.hostAddress}:${client.port}")

  startForwarders(forwardSpecs, color = useColor)

  val listeners = mutableListOf<RemoteTestListener>()
  if (!quiet) {
    listeners += LoggingRemoteTestListener(System.out, emptyList(), emptyList(), color = useColor)
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

  val junit4InvalidTests = recorder.results
    .asSequence()
    .filter { it.trace?.contains("InvalidTestClassError") == true }
    .filter { it.trace?.contains("JUnit4TestLoader") == true }
    .map { it.descriptor.className }
    .distinct()
    .toList()
  val junit5Configured = configContext.config.tests
    .any { it.runner?.lowercase(Locale.ROOT) == "junit5" }
  if (junit4InvalidTests.isNotEmpty() && !junit5Configured) {
    val preview = junit4InvalidTests.take(3).joinToString(", ")
    val suffix = if (junit4InvalidTests.size > 3) " ... and ${junit4InvalidTests.size - 3} more" else ""
    logger.warning(
      "Hint: JUnit4 reported InvalidTestClassError for $preview$suffix. " +
        "If these are JUnit 5 tests, set tests[].runner: junit5 in pde.yaml."
    )
  }

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

internal fun targetMain(
  args: Array<String>,
  runInstallerLauncher: (installerJar: Path, installerArgs: List<String>, workingDir: Path, logFile: Path?) -> Int = ::runTargetInstallerLauncher,
  clipboardCopier: (String) -> Boolean = ::copyStringToClipboard
): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, launchOptionsRequiringValue)
  val parser = ArgParser("pde target install ${maturityTag("usable")}")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration")
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
  val launchOpt by parser.option(ArgType.String, fullName = "launch", description = "Installer launch name (defaults to 'install' if present)")
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
  val copyPath by parser.option(
    ArgType.Boolean,
    fullName = "copy-path",
    description = "Copy target profile path to clipboard after successful install"
  ).default(false)
  parser.parse(normalizedArgs)
  configureLogging(resolveLogLevel(logLevelOpt, verbose, debug), shouldUseColor())

  val configPosValue = configPos
  val configFile = configFileOpt ?: configPosValue?.takeIf { looksLikeYamlFile(it) }
  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  if (discoveredConfig == null) {
    logger.severe("Missing --config and no launch config discovered in current directory")
    return 2
  }
  if (configFile == null) {
    logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
  }
  val issueContext = LaunchConfigLoader.load(discoveredConfig)
  val targetConfig = issueContext.config.target
  if (targetConfig == null) {
    logger.severe(
      "Missing target config in ${issueContext.file}. " +
        "Add a target section to pde.yaml and see docs/config-yaml.md#target."
    )
    return 2
  }
  val installerJar = resolveTargetInstallerJar(issueContext)
  if (installerJar == null) {
    logger.severe("target.installer is required in ${issueContext.file} unless the packaged target installer is available")
    return 2
  }
  if (!validateTargetInstallerJar(installerJar, "target.installer does not exist: $installerJar")) {
    return 2
  }
  if (launchOpt != null) {
    logger.warning("--launch is ignored when target.installer points to a launcher jar.")
  }
  val targetDefinition = resolveTargetDefinition(issueContext)
  if (targetDefinition == null) {
    logger.severe("Missing target.definition (or a discoverable .target) in ${issueContext.file}")
    return 2
  }
  if (!validateTargetDefinition(targetDefinition, issueContext.file)) {
    return 2
  }
  val installInputs = runCatching { buildTargetInstallInputs(issueContext, installerJar, targetDefinition) }
    .getOrElse { error ->
      logger.severe("Failed to parse target.definition $targetDefinition: ${error.message}")
      return 2
    }
  val logFile = logFileOpt?.let { Paths.get(it) }
  val exit = runInstallerLauncher(installInputs.installerJar, installInputs.installerArgs(), issueContext.baseDir, logFile)
  if (exit != 0) error("Target installer exited with code $exit")
  if (copyPath) {
    val profilePath = resolveProfilePath(issueContext)
    if (profilePath == null) {
      val message = "Unable to resolve target profile path for clipboard copy"
      logger.warning(message)
      println(message)
    } else {
      val absolutePath = profilePath.toAbsolutePath().normalize()
      val didCopy = clipboardCopier(absolutePath.toString())
      if (didCopy) {
        val message = "Copied target profile path to clipboard: $absolutePath"
        logger.info(message)
        println(message)
      } else {
        val message = "Failed to copy target profile path to clipboard: $absolutePath"
        logger.warning(message)
        println(message)
      }
    }
  }
  return 0
}

internal fun targetMirrorMain(
  args: Array<String>,
  launcherResolver: (installPath: Path?, installerPath: Path?, targetDefinition: Path?) -> Path? = ::resolveP2DirectorLauncher,
  mirrorRunner: (
    launcherExecutable: Path,
    applicationId: String,
    source: URI,
    destination: URI,
    writeMode: String?,
    logFile: Path?
  ) -> Int = ::runMirrorApplication
): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, targetMirrorOptionsRequiringValue)
  val parser = ArgParser("pde target mirror ${maturityTag("usable")}")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration")
  val configPos by parser.argument(ArgType.String, description = "YAML launch configuration (positional)").optional()
  val destinationOpt by parser.option(
    ArgType.String,
    fullName = "destination",
    shortName = "d",
    description = "Destination repository path or URI"
  )
  val writeModeOpt by parser.option(
    ArgType.String,
    fullName = "write-mode",
    description = "Write mode (clean)"
  )
  val metadataOnly by parser.option(
    ArgType.Boolean,
    fullName = "metadata-only",
    description = "Mirror metadata only"
  ).default(false)
  val artifactsOnly by parser.option(
    ArgType.Boolean,
    fullName = "artifacts-only",
    description = "Mirror artifacts only"
  ).default(false)
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
  parser.parse(normalizedArgs)
  configureLogging(resolveLogLevel(logLevelOpt, verbose, debug), shouldUseColor())

  val configPosValue = configPos
  val configFile = configFileOpt ?: configPosValue?.takeIf { looksLikeYamlFile(it) }
  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  if (discoveredConfig == null) {
    logger.severe("Missing --config and no launch config discovered in current directory")
    return 2
  }
  if (configFile == null) {
    logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
  }

  val issueContext = LaunchConfigLoader.load(discoveredConfig)
  val targetConfig = issueContext.config.target
  if (targetConfig == null) {
    logger.severe(
      "Missing target config in ${issueContext.file}. " +
        "Add a target section to pde.yaml and see docs/config-yaml.md#target."
    )
    return 2
  }

  if (metadataOnly && artifactsOnly) {
    logger.severe("Cannot use --metadata-only and --artifacts-only together")
    return 2
  }

  val targetDefinition = resolveTargetDefinition(issueContext)
  if (targetDefinition == null) {
    logger.severe("Missing target.definition (or a discoverable .target) in ${issueContext.file}")
    return 2
  }
  if (!Files.exists(targetDefinition)) {
    logger.severe("Target definition does not exist: ${targetDefinition.toAbsolutePath().normalize()}")
    return 2
  }

  val repositories = TargetFileParser.parseContents(targetDefinition).repositories
    .distinct()
  if (repositories.isEmpty()) {
    logger.severe("Target definition does not list any repositories: ${targetDefinition.toAbsolutePath().normalize()}")
    return 2
  }

  val mirrorConfig = targetConfig.mirror
  val destinationValue = destinationOpt ?: mirrorConfig?.destination
  if (destinationValue.isNullOrBlank()) {
    logger.severe("Missing mirror destination (set target.mirror.destination or pass --destination)")
    return 2
  }
  val destination = resolveMirrorDestination(issueContext.baseDir, destinationValue)

  val includeMetadata = when {
    metadataOnly -> true
    artifactsOnly -> false
    mirrorConfig?.includeMetadata != null -> mirrorConfig.includeMetadata
    else -> true
  }
  val includeArtifacts = when {
    artifactsOnly -> true
    metadataOnly -> false
    mirrorConfig?.includeArtifacts != null -> mirrorConfig.includeArtifacts
    else -> true
  }
  if (!includeMetadata && !includeArtifacts) {
    logger.severe("Nothing to mirror: both metadata and artifacts are disabled")
    return 2
  }

  val writeMode = resolveMirrorWriteMode(writeModeOpt ?: mirrorConfig?.writeMode)

  val installPath = targetConfig.install?.let { issueContext.baseDir.resolve(it).normalize() }
  val installerPath = targetConfig.installer?.let { issueContext.baseDir.resolve(it).normalize() }
  val launcherExecutable = launcherResolver(installPath, installerPath, targetDefinition)
  if (launcherExecutable == null) {
    logger.severe("Missing p2 director launcher under target.install or target.installer.")
    return 2
  }

  val logFileBase = logFileOpt?.let { Paths.get(it) }
  if (includeMetadata) {
    val exit = mirrorRepositories(
      launcherExecutable = launcherExecutable,
      applicationId = P2_METADATA_MIRROR_APPLICATION,
      repositories = repositories,
      destination = destination,
      writeMode = writeMode,
      logFileBase = logFileBase,
      labelPrefix = "metadata",
      mirrorRunner = mirrorRunner
    )
    if (exit != 0) return exit
  }
  if (includeArtifacts) {
    val exit = mirrorRepositories(
      launcherExecutable = launcherExecutable,
      applicationId = P2_ARTIFACT_MIRROR_APPLICATION,
      repositories = repositories,
      destination = destination,
      writeMode = writeMode,
      logFileBase = logFileBase,
      labelPrefix = "artifacts",
      mirrorRunner = mirrorRunner
    )
    if (exit != 0) return exit
  }
  return 0
}

internal fun resolveMirrorDestination(baseDir: Path, value: String): URI {
  val trimmed = value.trim()
  val uri = runCatching { URI(trimmed) }.getOrNull()
  if (uri != null && uri.scheme != null) {
    return uri
  }
  val path = baseDir.resolve(trimmed).normalize()
  return path.toUri()
}

internal fun resolveMirrorWriteMode(value: String?): String? {
  val normalized = value?.trim()?.lowercase(Locale.ROOT)
  if (normalized.isNullOrBlank()) return null
  if (normalized != "clean") {
    logger.warning("Unsupported mirror write-mode '$value' (only 'clean' is supported); ignoring.")
    return null
  }
  return "clean"
}

private fun mirrorRepositories(
  launcherExecutable: Path,
  applicationId: String,
  repositories: List<URI>,
  destination: URI,
  writeMode: String?,
  logFileBase: Path?,
  labelPrefix: String,
  mirrorRunner: (
    launcherExecutable: Path,
    applicationId: String,
    source: URI,
    destination: URI,
    writeMode: String?,
    logFile: Path?
  ) -> Int
): Int {
  repositories.forEachIndexed { index, source ->
    val effectiveWriteMode = if (writeMode == "clean" && index == 0) "clean" else null
    val logFile = logFileBase?.let { deriveMirrorLogFile(it, "$labelPrefix-${index + 1}") }
    val exit = mirrorRunner(
      launcherExecutable,
      applicationId,
      source,
      destination,
      effectiveWriteMode,
      logFile
    )
    if (exit != 0) return exit
  }
  return 0
}

internal fun deriveMirrorLogFile(base: Path, label: String): Path {
  val fileName = base.fileName.toString()
  val dot = fileName.lastIndexOf('.')
  val suffix = label.replace(Regex("[^A-Za-z0-9_.-]"), "_")
  val derived = if (dot > 0) {
    fileName.substring(0, dot) + "-" + suffix + fileName.substring(dot)
  } else {
    fileName + "-" + suffix
  }
  return base.parent?.resolve(derived) ?: Paths.get(derived)
}

private fun runMirrorApplication(
  launcherExecutable: Path,
  applicationId: String,
  source: URI,
  destination: URI,
  writeMode: String?,
  logFile: Path?
): Int {
  val command = mutableListOf(
    launcherExecutable.toString(),
    "-nosplash",
    "-consoleLog",
    "-application",
    applicationId,
    "-source",
    source.toString(),
    "-destination",
    destination.toString()
  )
  if (writeMode != null) {
    command += "-writeMode"
    command += writeMode
  }
  logCommand(command)
  val process = ProcessBuilder(command).apply {
    redirectErrorStream(true)
    if (logFile != null) {
      logFile.parent?.let { Files.createDirectories(it) }
      redirectOutput(logFile.toFile())
    } else {
      inheritIO()
    }
  }.start()
  val exitCode = process.waitFor()
  if (exitCode != 0) {
    logger.severe("Mirror application exited with code $exitCode for ${source}")
  }
  return exitCode
}

private fun testMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, testOptionsRequiringValue)
  val normalizedTestArgs = normalizeTestArgs(normalizedArgs)

  val parser = ArgParser("pde test ${maturityTag("usable")}")
  val configFileOpt by parser.option(ArgType.String, fullName = "config", description = "YAML launch configuration")
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
  val listenHost by parser.option(ArgType.String, fullName = "listen-host", description = "Host to bind").default("127.0.0.1")
  val listenPort by parser.option(ArgType.Int, fullName = "listen-port", description = "Fixed port to bind")
  val portRangeSpec by parser.option(ArgType.String, fullName = "port-range", description = "Inclusive port range start-end")
  val timeoutSeconds by parser.option(ArgType.Int, fullName = "timeout", description = "Seconds to wait for PDE connection").default(180)
  val reportValues by parser.option(ArgType.String, fullName = "report", description = "Reporting sink (teamcity, junit-xml:/path)").multiple()
  val forwardValues by parser.option(ArgType.String, fullName = "forward-log", description = "Prefix and stream an existing log source (label=path)").multiple()
  val quiet by parser.option(ArgType.Boolean, fullName = "quiet", description = "Suppress console test logs").default(false)
  val clean by parser.option(
    ArgType.Boolean,
    fullName = "clean",
    description = "Launch with Eclipse -clean and rebuild OSGi framework state"
  ).default(false)
  parser.parse(normalizedTestArgs.parserArgs)
  configureLogging(resolveLogLevel(logLevelOpt, verbose, debug), shouldUseColor())

  val configFile = inferTestConfigFile(configFileOpt, normalizedTestArgs)
  val requestedTests = normalizedTestArgs.requestedTests

  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  if (discoveredConfig == null) {
    logger.severe("Missing --config and no launch config discovered in current directory")
    return 2
  }
  if (configFile == null) {
    logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
  }

  val loaded = LaunchConfigLoader.load(discoveredConfig).copy(clean = clean)
  val logFile = logFileOpt?.let { Paths.get(it) }

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
  if (requestedTests.isEmpty()) {
    if (loaded.config.tests.isEmpty()) {
      logger.severe("No tests defined in ${loaded.file}. Add a 'tests' entry or pass a legacy launch.yaml.")
      return 2
    }
    var exitCode = 0
    for ((index, selectedTest) in loaded.config.tests.withIndex()) {
      logger.info("Running test ${index + 1}/${loaded.config.tests.size}: '${testLabel(selectedTest)}'.")
      val configContext = applyTestEntry(loaded, selectedTest, null, logSelection = false)
        .let { applyOsgiDebug(it, osgiDebug) }
      val targetArgs = resolveTargetArgs(configContext)
      val testExit = runTestLaunch(
        configContext = configContext,
        targetArgs = targetArgs,
        listenHost = listenHost,
        listenPort = listenPort,
        portRangeSpec = portRangeSpec,
        timeoutSeconds = timeoutSeconds,
        reports = reports,
        forwardSpecs = forwardSpecs,
        quiet = quiet,
        logFile = logFile,
        debug = debug
      )
      if (testExit != 0 && exitCode == 0) {
        exitCode = testExit
      }
    }
    return exitCode
  }

  var exitCode = 0
  for ((index, testName) in requestedTests.withIndex()) {
    val selected = selectTestConfig(loaded, testName)
    if (selected == null) return 2
    if (requestedTests.size > 1) {
      logger.info("Running requested test ${index + 1}/${requestedTests.size}: '$testName'.")
    }
    val configContext = applyOsgiDebug(selected, osgiDebug)
    val targetArgs = resolveTargetArgs(configContext)
    val testExit = runTestLaunch(
      configContext = configContext,
      targetArgs = targetArgs,
      listenHost = listenHost,
      listenPort = listenPort,
      portRangeSpec = portRangeSpec,
      timeoutSeconds = timeoutSeconds,
      reports = reports,
      forwardSpecs = forwardSpecs,
      quiet = quiet,
      logFile = logFile,
      debug = debug
    )
    if (testExit != 0 && exitCode == 0) {
      exitCode = testExit
    }
  }
  return exitCode
}

private fun apiAnalyzeMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, apiAnalyzeOptionsRequiringValue)
  val parser = ArgParser("pde api-analyze ${maturityTag("WIP")}")
  val configFileOpt by parser.option(
    ArgType.String,
    fullName = "config",
    description = "Path to launch config YAML"
  )
  val logLevelOpt by parser.option(
    ArgType.String,
    fullName = "log-level",
    description = "Log level (trace, debug, info, warn, error)"
  )
  val logFileOpt by parser.option(
    ArgType.String,
    fullName = "log",
    description = "Redirect launcher output to file"
  )
  val verboseOpt by parser.option(
    ArgType.Boolean,
    shortName = "v",
    fullName = "verbose",
    description = "Enable verbose logging"
  ).default(false)
  val debugOpt by parser.option(
    ArgType.Boolean,
    fullName = "debug",
    description = "Enable debug logging"
  ).default(false)
  val baselineRootOpt by parser.option(
    ArgType.String,
    fullName = "baseline-root",
    description = "Baseline target root, profile path, or .target file (defaults to target.apiBaselineRoot, target.install, target.p2Path, or target profile)"
  )
  val dependencyListOpt by parser.option(
    ArgType.String,
    fullName = "dependency-list",
    description = "Dependency list output path (defaults to <config-dir>/dependencies-list.txt)"
  )
  val baselineListOpt by parser.option(
    ArgType.String,
    fullName = "baseline-list",
    description = "Baseline list output path (defaults to api-analyzer/baseline-list.txt)"
  )
  val jdtComplianceOpt by parser.option(
    ArgType.String,
    fullName = "jdt-compliance",
    description = "Override JDT compliance (uses temp project copy)"
  )
  val applicationOpt by parser.option(
    ArgType.String,
    fullName = "application",
    description = "API analyzer application id"
  ).default(KNIME_API_ANALYZER_APPLICATION)
  val failOnErrorOpt by parser.option(
    ArgType.Boolean,
    fullName = "fail-on-error",
    description = "Fail when API errors are detected"
  ).default(false)
  val reportOpt by parser.option(
    ArgType.String,
    fullName = "report",
    description = "Write machine-readable API problem report JSON"
  )
  val configPosOpt by parser.argument(
    ArgType.String,
    description = "Launch config YAML"
  ).optional()

  parser.parse(normalizedArgs)
  configureLogging(resolveLogLevel(logLevelOpt, verboseOpt, debugOpt), shouldUseColor())
  val jdtCompliance = jdtComplianceOpt

  val baselineRootValue = baselineRootOpt

  val configFile = configFileOpt ?: configPosOpt?.takeIf { looksLikeYamlFile(it) }
  val resolvedConfigFile = configFile?.let { Paths.get(it) } ?: discoverConfigFile()
  if (resolvedConfigFile == null) {
    logger.severe("Missing --config and no launch config discovered in current directory.")
    return 2
  }
  if (configFile == null) {
    logger.info("Discovered launch config in ${resolvedConfigFile.toAbsolutePath().normalize()} and will use it.")
  }

  val baseContext = LaunchConfigLoader.load(resolvedConfigFile)
  val outputRoot = baseContext.file.parent?.resolve("api-analyzer") ?: Paths.get("api-analyzer")
  val applicationId = applicationOpt
  val apiContext = baseContext.copy(
    runtime = baseContext.runtime.copy(
      application = applicationId,
      product = null,
      splash = null,
      programArgs = emptyList()
    )
  )

  val targetDefinition = resolveTargetDefinition(apiContext)
  if (targetDefinition != null) {
    if (!Files.exists(targetDefinition)) {
      logger.severe("Target definition does not exist: ${targetDefinition.toAbsolutePath().normalize()}")
      return 2
    }
    logger.info("Using target definition at ${targetDefinition.toAbsolutePath().normalize()}")
  }
  val profilePath = resolveProfilePath(apiContext)
  if (profilePath == null) {
    logger.severe("No target profile path resolved from config.")
    return 2
  }
  if (!Files.exists(profilePath)) {
    logger.severe("Target profile path does not exist: ${profilePath.toAbsolutePath().normalize()}")
    return 2
  }

  val targetIndex = TargetPlatformCache.buildWithCache(listOf(profilePath))
  val runtimeApplicationId = if (applicationId == PDE_API_ANALYZER_APPLICATION &&
    targetIndex.get("com.knime.enterprise.devops.eclipse") != null
  ) {
    KNIME_API_ANALYZER_APPLICATION
  } else {
    applicationId
  }
  val workspaceInputs = WorkspaceModuleResolver.resolve(apiContext, allowMissingClasses = true)
  if (workspaceInputs.descriptors.isEmpty()) {
    logger.severe("No workspace bundles resolved from config; add bundles.")
    return 2
  }
  val workspaceDescriptors = workspaceInputs.descriptors
  val descriptorsToAnalyze = workspaceDescriptors.filter { descriptor ->
    val bsn = descriptor.manifest.bundleSymbolicName?.key.orEmpty()
    !bsn.contains(".tests") && !bsn.endsWith(".test")
  }
  val skippedCount = workspaceDescriptors.size - descriptorsToAnalyze.size
  if (skippedCount > 0) {
    logger.info("Skipping $skippedCount test bundles for API analysis.")
  }
  logger.info("Analyzing ${descriptorsToAnalyze.size} workspace bundles.")

  val baselineRootPath = resolveApiAnalyzeBaselineRootPath(apiContext, baselineRootValue, profilePath)
  val baselineTargetDefinition = baselineRootPath.takeIf { it.toString().lowercase(Locale.ROOT).endsWith(".target") }
  if (!Files.exists(baselineRootPath)) {
    logger.severe("Baseline root path does not exist: ${baselineRootPath.toAbsolutePath().normalize()}")
    return 2
  }
  val baselineRootForIndex = if (baselineTargetDefinition != null) {
    val existingBaselineProfilePath = resolveApiAnalyzeProvisionedBaselineProfilePath(apiContext, outputRoot)
    if (existingBaselineProfilePath == null || !Files.exists(existingBaselineProfilePath)) {
      logger.severe(
        "Baseline target is a .target file, but no provisioned api-analyze profile exists. " +
          "Run 'pde api-analyze install' first."
      )
      return 2
    }
    logger.info("Baseline: using existing provisioned profile ${existingBaselineProfilePath.toAbsolutePath().normalize()}")
    existingBaselineProfilePath
  } else {
    baselineRootPath
  }
  val baselineIndex = TargetPlatformCache.buildWithCache(listOf(baselineRootForIndex))

  val dataDirOverride = outputRoot.resolve("workspace").toString()
  val installPath = apiContext.config.target?.install?.let { Paths.get(it) }
  val installerPath = apiContext.config.target?.installer?.let { apiContext.baseDir.resolve(it).normalize() }
  val launcherExecutable = resolveP2DirectorLauncher(installPath, installerPath, targetDefinition)
  if (launcherExecutable == null) {
    logger.severe("Missing p2 director launcher under target.install or target.installer.")
    return 2
  }
  val dependencyListPath = when {
    dependencyListOpt != null -> Paths.get(dependencyListOpt)
    else -> baseContext.file.parent?.resolve("dependencies-list.txt") ?: Paths.get("dependencies-list.txt")
  }
  val baselineListPath = baselineListOpt?.let { Paths.get(it) } ?: outputRoot.resolve("baseline-list.txt")
  if (dependencyListPath.toString().lowercase(Locale.ROOT).endsWith(".target")) {
    logger.info("Dependency list: using target definition ${dependencyListPath.toAbsolutePath().normalize()}")
  } else {
    val dependencyPlan = buildCompilePlanForWarning(apiContext, targetIndex, workspaceInputs)
    val dependencyBundles = dependencyPlan.selectedBundles
    val dependencyEntries = dependencyBundles
      .map { it.path.toAbsolutePath().normalize() }
      .let(::expandBundleClassPathEntries)
      .distinct()
      .sortedBy { it.toString() }
    val workspaceBundleCount = dependencyBundles.count { it.isWorkspace }
    val targetBundleCount = dependencyBundles.size - workspaceBundleCount
    logger.info("Dependency list entries: ${dependencyEntries.size} (workspace $workspaceBundleCount, target $targetBundleCount)")
    writePathList(dependencyListPath, dependencyEntries)
  }
  writePathList(baselineListPath, collectBundlePaths(baselineIndex))
  if (!dependencyListPath.toString().lowercase(Locale.ROOT).endsWith(".target")) {
    logger.info("Dependency list: ${dependencyListPath.toAbsolutePath().normalize()}")
  }
  logger.info("Baseline list: ${baselineListPath.toAbsolutePath().normalize()}")

  val tempProjectsRoot = outputRoot.resolve("analysis-projects")
  val collectedProblems = mutableListOf<ApiAnalyzeProblem>()
  descriptorsToAnalyze.forEach { descriptor ->
    val projectArg = if (jdtCompliance != null) {
      val projectName = descriptor.manifest.bundleSymbolicName?.key ?: descriptor.path.fileName.toString()
      val tempProject = tempProjectsRoot.resolve(projectName)
      copyDirectory(descriptor.path, tempProject)
      updateJdtPrefs(tempProject, jdtCompliance)
      updateClasspathJre(tempProject, jdtCompliance)
      tempProject.toString()
    } else {
      descriptor.path.toString()
    }
    val label = descriptor.manifest.bundleSymbolicName?.key ?: projectArg
    logger.info("Running API analysis for $label")
    val extraProgramArgs = mutableListOf(
      "-project",
      projectArg,
      "-baseline",
      baselineListPath.toString()
    )
    extraProgramArgs += "-dependencyList"
    extraProgramArgs += dependencyListPath.toString()
    if (failOnErrorOpt) {
      extraProgramArgs += "-failOnError"
    }
    val logFile = logFileOpt?.let { base ->
      val basePath = Paths.get(base)
      val fileName = basePath.fileName.toString()
      val dot = fileName.lastIndexOf('.')
      val suffix = label.replace(Regex("[^A-Za-z0-9_.-]"), "_")
      val derived = if (dot > 0) {
        fileName.substring(0, dot) + "-" + suffix + fileName.substring(dot)
      } else {
        fileName + "-" + suffix
      }
      basePath.parent?.resolve(derived) ?: Paths.get(derived)
    } ?: reportOpt?.let {
      val suffix = label.replace(Regex("[^A-Za-z0-9_.-]"), "_")
      outputRoot.resolve("report-logs").resolve("$suffix.log")
    }
    val exitCode = runApiAnalyzer(
      launcherExecutable = launcherExecutable,
      dataDir = dataDirOverride,
      applicationId = runtimeApplicationId,
      args = extraProgramArgs,
      logFile = logFile
    )
    if (exitCode != 0) {
      return exitCode
    }
    if (reportOpt != null && logFile != null) {
      val bsn = descriptor.manifest.bundleSymbolicName?.key ?: label
      val extracted = extractApiAnalyzeProblemsFromLog(logFile, defaultBundleBsn = bsn, defaultBundleDir = descriptor.path)
      collectedProblems += extracted
      logger.info("Extracted ${extracted.size} report problem(s) for $bsn")
    }
  }
  if (reportOpt != null) {
    val reportPath = Paths.get(reportOpt)
    writeApiAnalyzeProblemReport(reportPath, collectedProblems)
    logger.info("Wrote API report to ${reportPath.toAbsolutePath().normalize()} (${collectedProblems.size} problems)")
  }
  return 0
}

fun compileMain(args: Array<String>): Int {
  val normalizedArgs = normalizeArgsWithImplicitConfig(args, compileOptionsRequiringValue)
  val parser = ArgParser("pde compile ${maturityTag("usable")}")
  val configFileOpt by parser.option(
    ArgType.String,
    fullName = "config",
    description = "YAML launch configuration (supports target.extraBundles and target.pinnedVersions)"
  )
  val workspaceRoots by parser.option(ArgType.String, fullName = "workspace", shortName = "w", description = "Workspace bundle directory (repeatable)").multiple()
  val framework by parser.option(ArgType.String, fullName = "framework", description = "Framework BSN").default("org.eclipse.osgi")
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit compile specs as JSON").default(false)
  val fullRebuild by parser.option(
    ArgType.Boolean,
    fullName = "full-rebuild",
    description = "Force full rebuild of all workspace bundles (skip incremental cache)"
  ).default(false)
  val debugInfo by parser.option(ArgType.Boolean, fullName = "debug", description = "Emit debug info (lines/vars/source)").default(false)
  val resultsJson by parser.option(ArgType.String, fullName = "results-json", description = "Write compile results to JSON file")
  val outputRoot by parser.option(ArgType.String, fullName = "output-root", description = "Override workspace bundle output dir (relative to module root, e.g., bin)")
  val bundlesInfoOut by parser.option(ArgType.String, fullName = "bundles-info-out", description = "Write bundles.info reflecting compiled workspace outputs")
  val runtimeOut by parser.option(ArgType.String, fullName = "runtime-out", description = "Write config.ini/dev.properties/bundles.info for compiled outputs under this directory")
  val configPos by parser.argument(
    ArgType.String,
    description = "YAML launch configuration (positional)"
  ).optional()
  parser.parse(normalizedArgs)
  configureLogging(Level.INFO, shouldUseColor())

  val configFile = configFileOpt ?: configPos?.takeIf { looksLikeYamlFile(it) }
  val discoveredConfig = configFile?.let { Paths.get(it) } ?: discoverConfigFile()

  if (discoveredConfig != null) {
    if (configFile == null) {
      logger.info("Discovered launch config in ${discoveredConfig.toAbsolutePath()} and will use it.")
    }
    val configContext = LaunchConfigLoader.load(discoveredConfig)
    val extraBundles = configuredExtraBundles(configContext)
    val pinnedVersions = configuredPinnedVersions(configContext)
    logActiveExtraBundles(extraBundles)
    val profilePath = resolveProfilePath(configContext)
    if (profilePath == null) {
      logger.severe("target profile path missing in YAML config; set target.profileId + target.p2Path.")
      return 0
    }
    if (!Files.exists(profilePath)) {
      logger.severe(
        "target profile registry does not exist: $profilePath (check target.profileId/target.p2Path or run pde target install)"
      )
      return 0
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
        includeHostsForFragments = true,
        pinnedVersions = pinnedVersions,
        extraBundles = extraBundles
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
    logPlanSummary(planResult)
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
      return 0
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
      val classfileWarning = extractClassfileWarning(result.output)
      if (classfileWarning != null) {
        logger.warning("${result.bsn}:")
        logger.warning(classfileWarning)
      }
    }
    val allOk = results.all { it.success }
    val exitCode = if (allOk) 0 else 1
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
        return exitCode
      }
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
    return exitCode
  }

  if (configFile == null) {
    logger.severe("No launch config found (pde.yaml/launch.yaml/pde-launch.yaml). Use --config.")
  } else {
    logger.severe("Launch config not found at ${Paths.get(configFile).toAbsolutePath()}")
  }
  return 0
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

private fun extractClassfileWarning(output: String): String? {
  val marker = "WARNING: Classpath contains class files requiring a newer Java version"
  val lines = output.lineSequence().toList()
  val startIndex = lines.indexOfFirst { it.startsWith(marker) }
  if (startIndex < 0) return null
  val warningLines = mutableListOf<String>()
  for (i in startIndex until lines.size) {
    val line = lines[i]
    if (i != startIndex && line.isBlank()) break
    if (
      i != startIndex &&
      !line.startsWith("Target: ") &&
      !line.startsWith("- ") &&
      !line.startsWith("... and ") &&
      !line.startsWith("Use a target platform ") &&
      !line.startsWith("WARNING:")
    ) {
      break
    }
    warningLines += line
  }
  return warningLines.joinToString("\n").trimEnd().ifEmpty { null }
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

internal fun currentOsgiOs(): String {
  val name = System.getProperty("os.name").lowercase()
  return when {
    name.contains("mac") -> "macosx"
    name.contains("win") -> "win32"
    else -> "linux"
  }
}

internal fun currentOsgiWs(): String {
  val name = System.getProperty("os.name").lowercase()
  return when {
    name.contains("mac") -> "cocoa"
    name.contains("win") -> "win32"
    else -> "gtk"
  }
}

internal fun currentOsgiArch(): String {
  val arch = System.getProperty("os.arch").lowercase()
  return when {
    arch == "aarch64" || arch == "arm64" -> "aarch64"
    else -> "x86_64"
  }
}

private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
