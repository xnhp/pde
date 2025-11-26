package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.index.TargetPlatformCache
import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.*
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.product.ProductConfigurationParser
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

fun launchMain(args: Array<String>) {
  val parser = ArgParser("pde-resolver launch")
  val configFile by parser.option(
    ArgType.String,
    fullName = "config",
    description = "YAML launch configuration"
  )
  val targetFile by parser.option(
    ArgType.String,
    fullName = "target-file",
    description = "Eclipse .target file (required with --config)"
  )
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

  if (configFile != null) {
    val configContext = LaunchConfigLoader.load(Paths.get(configFile!!))
    val targetPath = targetFile?.let { Paths.get(it) }
      ?: error("--target-file is required when --config is provided")
    val targetArgs = runCatching { TargetFileParser.parse(targetPath) }
      .onFailure { println("Warning: failed to parse target file args: ${it.message}") }
      .getOrNull()
    describeConfig(configContext, targetPath, targetArgs)
    if (dryRun) {
      println("Dry run: validation only. Exiting.")
      return
    }
    executeLaunch(configContext, targetArgs)
    return
  }

  if (targetRoots.isEmpty()) {
    System.err.println("No --target-root specified")
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
    println("Wrote config.ini, bundles.info, dev.properties under $outDir")
  } else {
    println("Framework: ${planResult.plan.framework?.bsn ?: "<none>"}; bundles=${planResult.plan.bundles.size}")
  }
}

private fun describeConfig(config: LaunchConfigContext, targetFile: Path, targetArgs: TargetLaunchArgs?) {
  println("Loaded launch config from ${config.file}")
  println("  product: ${config.config.product ?: "<unspecified>"}")
  println("  application: ${config.config.application ?: "<unspecified>"}")
  println("  workspace modules: ${config.config.workspaceModules.size}")
  println("  target file: $targetFile")
  println("  target roots: ${config.config.targetRoots.size}")
  println("  target vm args: ${targetArgs?.vmArgs?.size ?: 0}")
  println("  target program args: ${targetArgs?.programArgs?.size ?: 0}")
}

private fun executeLaunch(context: LaunchConfigContext, targetArgs: TargetLaunchArgs?) {
  val workspaceInputs = WorkspaceModuleResolver.resolve(context)
  val targetRoots = context.config.targetRoots.takeIf { it.isNotEmpty() }
    ?: error("No target roots defined in YAML config")
  val targetPaths = targetRoots.map { context.baseDir.resolve(it).normalize() }
  val targetIndex = TargetPlatformCache.buildWithCache(targetPaths)
  val layout = LaunchLayoutResolver.resolve(context)
  LaunchLayoutResolver.cleanIfRequested(layout, context.config.cleanRuntime)
  val targetDefinitionStartupLevels = loadTargetDefinitionStartupLevels(context)
  val productStartupLevels = loadProductStartupLevels(context)
  val combinedStartup = targetDefinitionStartupLevels + productStartupLevels + context.config.startupLevels
  val resolverWhitelist = resolveWhitelist(context)
  val workspaceEntries = if (workspaceInputs.descriptors.isNotEmpty()) {
    workspaceInputs.descriptors
  } else {
    listOfNotNull(syntheticEntry(context, targetIndex))
  }
  val hasWorkspaceModules = workspaceInputs.descriptors.isNotEmpty()
  val supplementalBundles = if (!hasWorkspaceModules) {
    targetIndex.bundlesByBsn().flatMap { (bsn, nav) ->
      nav.entries.map { entry ->
        LaunchEnvironment.SupplementalBundle(bsn, entry.key, entry.value.location, isWorkspace = false)
      }
    }
  } else emptyList()
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
  val options = LauncherOptions(
    product = context.config.product,
    application = context.config.application,
    splashBSN = context.config.splash,
    autoStartDefault = false
  )
  val planResult = LaunchPlanner.build(env, options)
  val fallbackConfig = loadExistingConfig(targetPaths)
  writeLaunchArtifacts(context, layout, planResult, options, targetPaths.first(), fallbackConfig)
  println("Launch plan built:")
  println("  bundles: ${planResult.plan.bundles.size}")
  println("  workspace bundles: ${planResult.plan.bundles.count { it.isWorkspace }}")
  println("  problems: ${planResult.problemsByScope.values.sumOf { it.size }}")
  if (planResult.problemsByScope.isNotEmpty()) {
    planResult.problemsByScope.forEach { (scope, probs) ->
      println("    $scope -> ${probs.size} issues")
    }
  }
  val launcherJar = resolveLauncherJar(targetIndex)
  val command = assembleCommand(context, layout, targetArgs, planResult, launcherJar)
  println("Executing: ${command.joinToString(" ")}")
  val process = ProcessBuilder(command)
    .directory(layout.workDir.toFile())
    .inheritIO()
    .start()
  val exit = process.waitFor()
  if (exit != 0) error("Launcher exited with code $exit")
}

private fun assembleCommand(
  context: LaunchConfigContext,
  layout: LaunchLayout,
  targetArgs: TargetLaunchArgs?,
  planResult: LaunchPlanner.PlanResult,
  launcherJar: Path
): List<String> {
  val javaBin = System.getenv("JAVA_HOME")?.let { Path.of(it, "bin", "java").toString() } ?: "java"
  val vmArgs = mutableListOf<String>().apply {
    addAll(targetArgs?.vmArgs ?: emptyList())
    addAll(context.config.vmArgs)
  }
  val programArgs = mutableListOf<String>().apply {
    addAll(targetArgs?.programArgs ?: emptyList())
    addAll(context.config.programArgs)
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
    add("-product")
    add(context.config.product ?: error("product missing"))
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
  val defaultFile = context.baseDir.resolve("startupLevels.xml")
  val candidates = listOfNotNull(configured, defaultFile)
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

private fun loadExistingConfig(targetRoots: List<Path>): Properties {
  val props = Properties()
  val configPath = targetRoots.asSequence()
    .map { it.resolve("configuration").resolve("config.ini") }
    .firstOrNull { Files.exists(it) }
  configPath?.let { path ->
    path.toFile().inputStream().use { props.load(it) }
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

private fun writeOutputs(dir: Path, plan: LauncherPlan, ctx: LaunchContext, opts: LauncherOptions) {
  val layout = RuntimeLayoutWriter.LayoutPaths.fromConfigDir(dir)
  val defaults = RuntimeLayoutWriter.Defaults(
    installArea = dir,
    instanceArea = dir
  )
  RuntimeLayoutWriter.write(layout, plan, ctx, opts, defaults)
}

fun main(args: Array<String>) {
  if (args.isNotEmpty() && args[0] == "launch") {
    launchMain(args.drop(1).toTypedArray())
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
    System.err.println("No roots provided. Pass one or more paths with --root/-r.")
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
    println("Source: ${index.source}; bundles: ${flat.size}")
    flat.forEach { (bsn, ver, path) -> println("$bsn@$ver -> $path") }
  }
}

private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
