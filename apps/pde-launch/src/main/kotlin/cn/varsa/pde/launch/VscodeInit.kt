package cn.varsa.pde.launch

import cn.varsa.cli.core.CliFailure
import cn.varsa.cli.core.CliLogLevel
import cn.varsa.cli.core.CliLogging
import cn.varsa.cli.core.CliStyle
import cn.varsa.cli.core.ColorMode
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.optional
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Level
import java.util.logging.Logger

object VscodeInit {
  private val logger = Logger.getLogger(VscodeInit::class.java.name)

  fun main(args: Array<String>): Int {
    CliLogging.configure(CliLogLevel.INFO, CliStyle.useColor(ColorMode.AUTO))
    val parser = ArgParser("pde ide-init vscode")
    val issueDirOpt by parser.option(
      ArgType.String,
      fullName = "issue-dir",
      description = "Issue directory containing pde.yaml and repos"
    )
    val configOpt by parser.option(
      ArgType.String,
      fullName = "config",
      description = "YAML launch configuration path"
    )
    val outOpt by parser.option(
      ArgType.String,
      fullName = "out",
      description = "Destination .code-workspace file (defaults to <issue-dir>/pde.code-workspace)"
    )
    val configPos by parser.argument(
      ArgType.String,
      description = "YAML launch configuration (positional)"
    ).optional()
    parser.parse(args)

    val issueDir = (issueDirOpt?.let { Paths.get(it) } ?: Paths.get("").toAbsolutePath())
      .toAbsolutePath().normalize()
    val explicitConfig = resolveConfigPath(issueDir, configOpt, configPos)
    val configPath = explicitConfig ?: findConfigPath(issueDir)
    if (explicitConfig != null && (configPath == null || !Files.exists(configPath))) {
      logger.severe("Config file not found: ${explicitConfig.toAbsolutePath().normalize()}")
      return 1
    }
    if (configPath == null) {
      logger.severe("No launch config found (pde.yaml/launch.yaml/pde-launch.yaml). Use --config.")
      return 1
    }

    return try {
      val issueRoot = if (issueDirOpt != null) {
        issueDir
      } else {
        configPath.parent?.toAbsolutePath()?.normalize() ?: issueDir
      }
      val context = LaunchConfigLoader.load(configPath, issueRoot)
      if (context.config.bundles.isEmpty()) {
        fail("No bundle entries found in config; add bundles to generate a workspace.")
      }
      val workspaceFile = outOpt?.let { resolvePath(issueRoot, it) } ?: issueRoot.resolve("pde.code-workspace")
      val folderCount = writeCodeWorkspace(context, issueRoot, workspaceFile)
      logger.info("VS Code workspace with ${folderCount} folder(s) written to ${workspaceFile}.")
      val wroteTasksAndLaunch = writeVscodeTasksAndLaunch(context, issueRoot, configPath)
      if (wroteTasksAndLaunch) {
        logger.info("VS Code tasks.json/launch.json written to ${issueRoot.resolve(".vscode")}.")
      }
      logger.info("Run 'pde lsp init' to generate JDT LS project files (.project/.classpath) for this workspace.")
      0
    } catch (ex: Exception) {
      logger.log(Level.SEVERE, ex.message ?: "vscode-init failed", ex)
      1
    }
  }

  private fun writeCodeWorkspace(context: LaunchConfigContext, issueRoot: Path, workspaceFile: Path): Int {
    if (Files.exists(workspaceFile)) {
      fail("${workspaceFile} already exists; remove it or pass --out to write elsewhere.")
    }
    val baseDir = context.baseDir
    val folders = LinkedHashSet<Path>()
    context.config.bundles.forEach { bundleEntry ->
      val bundlePath = resolvePath(baseDir, bundleEntry.path)
      if (!Files.exists(bundlePath) || !Files.isDirectory(bundlePath)) {
        fail("Bundle directory does not exist: ${bundlePath}")
      }
      warnOnEmptyFetchJarsLibs(bundlePath, logger)
      folders.add(bundlePath.toAbsolutePath().normalize())
    }
    val relativeFolders = folders.sorted().map { root ->
      val relative = issueRoot.relativize(root).toString().replace('\\', '/')
      relative.ifEmpty { "." }
    }

    val builder = StringBuilder()
    builder.appendLine("{")
    builder.appendLine("  \"folders\": [")
    relativeFolders.forEachIndexed { index, path ->
      val name = Paths.get(path).fileName?.toString() ?: path
      val comma = if (index < relativeFolders.size - 1) "," else ""
      builder.appendLine("    { \"name\": \"${jsonEscape(name)}\", \"path\": \"${jsonEscape(path)}\" }${comma}")
    }
    builder.appendLine("  ],")
    builder.appendLine("  \"settings\": {")
    builder.appendLine("    \"java.import.maven.enabled\": false,")
    builder.appendLine("    \"java.import.gradle.enabled\": false")
    builder.appendLine("  }")
    builder.appendLine("}")

    Files.createDirectories(workspaceFile.parent ?: issueRoot)
    Files.writeString(workspaceFile, builder.toString(), StandardCharsets.UTF_8)
    return relativeFolders.size
  }

  private fun resolveConfigPath(baseDir: Path, configOpt: String?, configPos: String?): Path? {
    val candidate = configOpt ?: configPos?.takeIf { looksLikeYamlFile(it) }
    return candidate?.let { resolvePath(baseDir, it) }
  }

  private fun resolvePath(baseDir: Path, raw: String): Path {
    val path = Paths.get(raw)
    return if (path.isAbsolute) path else baseDir.resolve(path).normalize()
  }

  private fun looksLikeYamlFile(value: String): Boolean =
    value.endsWith(".yaml", ignoreCase = true) || value.endsWith(".yml", ignoreCase = true)

  private fun findConfigPath(startDir: Path): Path? {
    val candidates = listOf("pde.yaml", "launch.yaml", "launch.yml", "pde-launch.yaml", "pde-launch.yml")
    var current = startDir.toAbsolutePath().normalize()
    while (true) {
      candidates.forEach { name ->
        val path = current.resolve(name)
        if (Files.exists(path) && Files.isRegularFile(path)) return path
      }
      val parent = current.parent ?: return null
      if (parent == current) return null
      current = parent
    }
  }

  private fun jsonEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  private data class VscodeTask(
    val label: String,
    val args: List<String>,
    val background: Boolean
  )

  private data class VscodeDebugConfig(val name: String, val preLaunchTask: String)

  /**
   * Generates `.vscode/tasks.json` (one task per launches/tests entry, plus a
   * `(debug)` background-task variant for JDWP-debuggable entries) and
   * `.vscode/launch.json` (one `attach` config per debuggable entry, wired to its
   * background task via preLaunchTask). Only written if absent, matching the
   * settings.json non-clobber convention above. `--debug` on `pde run` opens real
   * JDWP; `--debug` on `pde test` is just log verbosity, so a test entry is only
   * debuggable when its own `debug: true` is set in the YAML, which the launch
   * engine reads to decide whether to wait for a debugger to attach.
   */
  internal fun writeVscodeTasksAndLaunch(context: LaunchConfigContext, issueDir: Path, configPath: Path): Boolean {
    val vscodeDir = issueDir.resolve(".vscode")
    val tasksFile = vscodeDir.resolve("tasks.json")
    val launchFile = vscodeDir.resolve("launch.json")
    val configPathString = configPath.toAbsolutePath().normalize().toString()

    val tasks = mutableListOf<VscodeTask>()
    val debugConfigs = mutableListOf<VscodeDebugConfig>()

    context.config.launches.forEach { entry ->
      tasks += VscodeTask(entry.name, listOf("run", configPathString, entry.name), background = false)
      val debugLabel = "${entry.name} (debug)"
      tasks += VscodeTask(debugLabel, listOf("run", configPathString, entry.name, "--debug"), background = true)
      debugConfigs += VscodeDebugConfig(debugLabel, debugLabel)
    }

    context.config.tests.forEachIndexed { index, entry ->
      val display = entry.name ?: (index + 1).toString()
      val label = "test: $display"
      tasks += VscodeTask(label, listOf("test", configPathString, display), background = false)
      if (entry.debug) {
        val debugLabel = "test: $display (debug)"
        tasks += VscodeTask(debugLabel, listOf("test", configPathString, display), background = true)
        debugConfigs += VscodeDebugConfig(debugLabel, debugLabel)
      }
    }

    var wrote = false
    if (Files.notExists(tasksFile)) {
      Files.createDirectories(vscodeDir)
      Files.writeString(tasksFile, renderTasksJson(tasks), StandardCharsets.UTF_8)
      wrote = true
    }
    if (Files.notExists(launchFile)) {
      Files.createDirectories(vscodeDir)
      Files.writeString(launchFile, renderLaunchJson(debugConfigs), StandardCharsets.UTF_8)
      wrote = true
    }
    return wrote
  }

  private fun renderTasksJson(tasks: List<VscodeTask>): String {
    val builder = StringBuilder()
    builder.appendLine("{")
    builder.appendLine("  \"version\": \"2.0.0\",")
    builder.appendLine("  \"tasks\": [")
    tasks.forEachIndexed { index, task ->
      val comma = if (index < tasks.size - 1) "," else ""
      builder.appendLine("    {")
      builder.appendLine("      \"label\": \"${jsonEscape(task.label)}\",")
      builder.appendLine("      \"type\": \"shell\",")
      builder.appendLine("      \"command\": \"pde\",")
      builder.append("      \"args\": [")
      builder.append(task.args.joinToString(", ") { "\"${jsonEscape(it)}\"" })
      builder.appendLine("],")
      if (task.background) {
        builder.appendLine("      \"isBackground\": true,")
        builder.appendLine("      \"problemMatcher\": {")
        builder.appendLine("        \"pattern\": {")
        builder.appendLine("          \"regexp\": \"^(x)(x)(x)$\",")
        builder.appendLine("          \"file\": 1,")
        builder.appendLine("          \"location\": 2,")
        builder.appendLine("          \"message\": 3")
        builder.appendLine("        },")
        builder.appendLine("        \"background\": {")
        builder.appendLine("          \"activeOnStart\": true,")
        builder.appendLine("          \"beginsPattern\": \"^(x)(x)(x)$\",")
        builder.appendLine(
          "          \"endsPattern\": \"Waiting for debugger to attach on port 5005\\\\.\\\\.\\\\.\""
        )
        builder.appendLine("        }")
        builder.appendLine("      }")
      } else {
        builder.appendLine("      \"problemMatcher\": []")
      }
      builder.appendLine("    }${comma}")
    }
    builder.appendLine("  ]")
    builder.appendLine("}")
    return builder.toString()
  }

  private fun renderLaunchJson(debugConfigs: List<VscodeDebugConfig>): String {
    val builder = StringBuilder()
    builder.appendLine("{")
    builder.appendLine("  \"version\": \"0.2.0\",")
    builder.appendLine("  \"configurations\": [")
    debugConfigs.forEachIndexed { index, config ->
      val comma = if (index < debugConfigs.size - 1) "," else ""
      builder.appendLine("    {")
      builder.appendLine("      \"type\": \"java\",")
      builder.appendLine("      \"request\": \"attach\",")
      builder.appendLine("      \"name\": \"${jsonEscape(config.name)}\",")
      builder.appendLine("      \"hostName\": \"localhost\",")
      builder.appendLine("      \"port\": 5005,")
      builder.appendLine("      \"preLaunchTask\": \"${jsonEscape(config.preLaunchTask)}\"")
      builder.appendLine("    }${comma}")
    }
    builder.appendLine("  ]")
    builder.appendLine("}")
    return builder.toString()
  }

  private fun fail(message: String): Nothing = throw CliFailure(message)
}
