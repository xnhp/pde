package cn.varsa.pde.launch

import cn.varsa.cli.core.CliFailure
import cn.varsa.cli.core.CliLogLevel
import cn.varsa.cli.core.CliLogging
import cn.varsa.cli.core.CliStyle
import cn.varsa.cli.core.ColorMode
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.config.WorkspaceModuleResolver
import cn.varsa.pde.resolver.cli.workspaceSetupMain
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
    val projectConfigurationsOut by parser.option(
      ArgType.String,
      fullName = "project-configurations-out",
      description = "Write JDT LS projectConfigurations JSON to file"
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

    val cwd = Paths.get("").toAbsolutePath().normalize()
    val hasCwdConfig = Files.exists(cwd.resolve("pde.yaml")) && Files.isRegularFile(cwd.resolve("pde.yaml"))

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

      // Materialize .project/.classpath at each bundle's real directory via the same
      // `pde workspace setup` Equinox app used by api-analyze/jdt-build, so VS Code's bundled
      // JDT LS (via EclipseProjectImporter) can pick them up directly. VS Code always runs its
      // own JDT LS with no way to point it at an external server, so this has to be filesystem
      // artifacts, not a spawned process.
      val setupExit = workspaceSetupMain(
        arrayOf("--config", configPath.toString(), "--output-root", issueRoot.resolve(".lsp").toString())
      )
      if (setupExit != 0) return setupExit

      touchProjectile(issueRoot)
      writeVscodeSettings(issueRoot)

      val moduleDefinitions = WorkspaceModuleResolver.resolveDefinitions(context)
      val projectFiles = moduleDefinitions
        .map { it.moduleDir.toAbsolutePath().normalize().resolve(".project") }
        .filter { Files.exists(it) }
      logger.info("Workspace setup wrote .project/.classpath for ${projectFiles.size} of ${moduleDefinitions.size} workspace bundles")

      val projectConfigurationsOutValue = projectConfigurationsOut
      val projectConfigurationsPath = when {
        projectConfigurationsOutValue != null -> resolvePath(context.baseDir, projectConfigurationsOutValue)
        issueDirOpt == null && hasCwdConfig -> issueDir.resolve(".lsp").resolve("projectConfigurations.json")
        else -> null
      }
      if (projectConfigurationsPath != null) {
        val workspaceRoot = resolveWorkspaceRoot(context)
        val workspaceFolders = listOf(
          WorkspaceFolder(
            workspaceRoot.toUri().toString(),
            workspaceRoot.fileName?.toString() ?: "workspace"
          )
        )
        writeProjectConfigurationsOutput(
          projectConfigurationsPath,
          projectFiles,
          listOf(workspaceRoot),
          workspaceFolders
        )
        logger.info("Wrote projectConfigurations to ${projectConfigurationsPath.toAbsolutePath().normalize()}")
      }
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
      val root = findVcsRoot(bundlePath) ?: bundlePath
      folders.add(root.toAbsolutePath().normalize())
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

  private fun findVcsRoot(path: Path): Path? {
    var current = path.toAbsolutePath().normalize()
    while (true) {
      if (Files.exists(current.resolve(".git"))) return current
      current = current.parent ?: return null
    }
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

  private fun touchProjectile(issueDir: Path) {
    val projectile = issueDir.resolve(".projectile")
    if (Files.notExists(projectile)) {
      Files.createFile(projectile)
    }
  }

  /**
   * VS Code's redhat.java extension always runs its own bundled JDT LS and defaults to
   * Maven/Gradle auto-import, which would hijack import for bundles that also carry a Tycho
   * pom.xml. Disabling those importers falls the extension back to Eclipse project import,
   * which picks up the .project/.classpath workspace setup just wrote. Only written if absent,
   * so we never clobber a user's existing settings.json.
   */
  private fun writeVscodeSettings(issueDir: Path) {
    val vscodeDir = issueDir.resolve(".vscode")
    val settingsFile = vscodeDir.resolve("settings.json")
    if (Files.exists(settingsFile)) return
    Files.createDirectories(vscodeDir)
    val json = """
      {
        "java.import.maven.enabled": false,
        "java.import.gradle.enabled": false
      }
    """.trimIndent() + "\n"
    Files.writeString(settingsFile, json, StandardCharsets.UTF_8)
  }

  private fun resolveWorkspaceRoot(context: LaunchConfigContext): Path {
    return context.workingDir.toAbsolutePath().normalize()
  }

  private data class WorkspaceFolder(val uri: String, val name: String)

  private fun writeProjectConfigurationsOutput(
    outputPath: Path,
    projectConfigurations: List<Path>,
    rootPaths: List<Path>,
    workspaceFolders: List<WorkspaceFolder>
  ) {
    if (outputPath.parent != null) {
      Files.createDirectories(outputPath.parent)
    }
    val rootPathStrings = rootPaths.map { it.toAbsolutePath().normalize().toString() }.distinct()
    val folderEntries = workspaceFolders.distinctBy { it.uri }
    val uris = projectConfigurations.map { it.toAbsolutePath().normalize().toUri().toString() }.distinct()
    val builder = StringBuilder()
    builder.appendLine("{")
    builder.appendLine("  \"rootPaths\": [")
    rootPathStrings.forEachIndexed { index, path ->
      val suffix = if (index == rootPathStrings.size - 1) "" else ","
      builder.append("    \"").append(jsonEscape(path)).append("\"").append(suffix).appendLine()
    }
    builder.appendLine("  ],")
    builder.appendLine("  \"workspaceFolders\": [")
    folderEntries.forEachIndexed { index, folder ->
      val suffix = if (index == folderEntries.size - 1) "" else ","
      builder.appendLine("    {")
      builder.append("      \"uri\": \"").append(jsonEscape(folder.uri)).append("\",").appendLine()
      builder.append("      \"name\": \"").append(jsonEscape(folder.name)).append("\"").appendLine()
      builder.append("    }").append(suffix).appendLine()
    }
    builder.appendLine("  ],")
    builder.appendLine("  \"projectConfigurations\": [")
    uris.forEachIndexed { index, uri ->
      val suffix = if (index == uris.size - 1) "" else ","
      builder.append("    \"").append(jsonEscape(uri)).append("\"").append(suffix).appendLine()
    }
    builder.appendLine("  ]")
    builder.appendLine("}")
    Files.writeString(outputPath, builder.toString(), StandardCharsets.UTF_8)
  }

  private fun fail(message: String): Nothing = throw CliFailure(message)
}
