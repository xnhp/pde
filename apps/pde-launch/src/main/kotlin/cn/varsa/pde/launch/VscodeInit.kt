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

  private fun fail(message: String): Nothing = throw CliFailure(message)
}
