package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.config.WorkspaceModuleResolver
import cn.varsa.pde.resolver.cli.discoverConfigFile
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.optional
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Level
import java.util.logging.Logger

/**
 * `pde lsp ignore` / `pde lsp unignore`.
 *
 * `.project`, `.classpath`, and the `*.prefs` files under `.settings` are typically already committed to each
 * bundle's own git repo (WorkspaceSetupService.createProject rewrites them in place on every
 * `pde lsp init` / `pde jdt-workspace init` run). A plain `.gitignore` entry can't hide local
 * modifications to already-tracked files, so this uses git's skip-worktree bit instead: `git
 * update-index --skip-worktree <file>` tells git to stop reporting local changes to that file
 * (until `--no-skip-worktree` undoes it).
 */
object LspIgnoreCommand {
  private val logger = Logger.getLogger(LspIgnoreCommand::class.java.name)

  /**
   * Injectable git runner for testing (mirrors InitConfig.gitDiffRunner's DI seam): tests can
   * swap this for a fake that records invocations without needing a real git binary/repo. Runs
   * `git update-index <flag> <files...>` in [moduleDir] (git resolves cwd-relative pathspecs
   * correctly even from a subdirectory of the repo) and returns true on success (exit 0).
   */
  internal var gitUpdateIndexRunner: (moduleDir: Path, flag: String, files: List<String>) -> Boolean =
    { moduleDir, flag, files -> runGitUpdateIndex(moduleDir, flag, files) }

  fun main(args: Array<String>): Int = run(args, skipWorktree = true)

  fun mainUndo(args: Array<String>): Int = run(args, skipWorktree = false)

  private fun run(args: Array<String>, skipWorktree: Boolean): Int {
    val title = if (skipWorktree) "pde lsp ignore" else "pde lsp unignore"
    val parser = ArgParser(title)
    val configOpt by parser.option(
      ArgType.String,
      fullName = "config",
      description = "YAML launch configuration path"
    )
    val issueDirOpt by parser.option(
      ArgType.String,
      fullName = "issue-dir",
      description = "Issue directory containing pde.yaml and repos"
    )
    val configPos by parser.argument(
      ArgType.String,
      description = "YAML launch configuration (positional)"
    ).optional()
    parser.parse(args)

    val issueDir = (issueDirOpt?.let { Paths.get(it) } ?: Paths.get("").toAbsolutePath())
      .toAbsolutePath().normalize()

    val explicitConfig = resolveConfigPath(issueDir, configOpt, configPos)
    val configPath = explicitConfig ?: discoverConfigFile(issueDir)
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
      val moduleDefinitions = WorkspaceModuleResolver.resolveDefinitions(context)

      val flag = if (skipWorktree) "--skip-worktree" else "--no-skip-worktree"
      val verb = if (skipWorktree) "ignored" else "unignored"
      var bundlesProcessed = 0
      var totalFiles = 0

      for (definition in moduleDefinitions) {
        val moduleDir = definition.moduleDir.toAbsolutePath().normalize()
        val files = candidateFiles(moduleDir)
        if (files.isEmpty()) continue

        bundlesProcessed++
        val relPaths = files.map { moduleDir.relativize(it).toString() }
        val ok = gitUpdateIndexRunner(moduleDir, flag, relPaths)
        if (ok) {
          totalFiles += relPaths.size
          logger.info("${moduleDir.fileName}: $verb ${relPaths.joinToString(", ")}")
        } else {
          logger.warning(
            "${moduleDir.fileName}: git update-index $flag failed for ${relPaths.joinToString(", ")} " +
              "(files may be untracked in this repo)"
          )
        }
      }

      logger.info("Processed $bundlesProcessed bundle(s), $verb $totalFiles file(s) total")
      0
    } catch (ex: Exception) {
      logger.log(Level.SEVERE, ex.message ?: "$title failed", ex)
      1
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

  /** `.project`, `.classpath` (if present) plus any `*.prefs` files under [moduleDir]'s `.settings` dir. */
  internal fun candidateFiles(moduleDir: Path): List<Path> {
    val result = mutableListOf<Path>()
    val project = moduleDir.resolve(".project")
    if (Files.exists(project)) result.add(project)
    val classpath = moduleDir.resolve(".classpath")
    if (Files.exists(classpath)) result.add(classpath)
    val settingsDir = moduleDir.resolve(".settings")
    if (Files.isDirectory(settingsDir)) {
      Files.newDirectoryStream(settingsDir, "*.prefs").use { stream ->
        stream.forEach { result.add(it) }
      }
    }
    return result
  }

  private fun runGitUpdateIndex(moduleDir: Path, flag: String, files: List<String>): Boolean {
    return try {
      val process = ProcessBuilder(listOf("git", "update-index", flag) + files)
        .directory(moduleDir.toFile())
        .redirectErrorStream(false)
        .start()
      val stderr = process.errorStream.bufferedReader().readText()
      val exit = process.waitFor()
      if (exit != 0) {
        logger.fine("git update-index $flag failed in $moduleDir (exit $exit): $stderr")
      }
      exit == 0
    } catch (ex: Exception) {
      logger.fine("git update-index exception in $moduleDir: ${ex.message}")
      false
    }
  }
}
