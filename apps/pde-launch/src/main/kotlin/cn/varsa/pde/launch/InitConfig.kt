package cn.varsa.pde.launch

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.optional
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Logger

object InitConfig {
  private val logger = Logger.getLogger(InitConfig::class.java.name)

  /**
   * Injectable git diff runner for testing. Defaults to the real implementation.
   * Tests can replace this with a fake that returns predetermined file lists.
   */
  internal var gitDiffRunner: (worktree: Path, baseRef: String) -> List<Path>? = { worktree, baseRef ->
    runGitDiff(worktree, baseRef)
  }

  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde init-config")
    val issueDirOpt by parser.option(
      ArgType.String,
      fullName = "issue-dir",
      description = "Issue directory containing git worktrees (defaults to current directory)"
    )
    val baseRefOpt by parser.option(
      ArgType.String,
      fullName = "base",
      description = "Git base ref for diff to detect modified bundles (default: origin/master)"
    ).default("origin/master")
    val dryRunOpt by parser.option(
      ArgType.Boolean,
      fullName = "dry-run",
      description = "Print the generated pde.yaml without writing it"
    ).default(false)
    val issueDirPos by parser.argument(
      ArgType.String,
      description = "Issue directory (positional)"
    ).optional()
    parser.parse(args)

    val issueDir = (issueDirOpt ?: issueDirPos)
      ?.let { Paths.get(it).toAbsolutePath().normalize() }
      ?: Paths.get("").toAbsolutePath()

    if (!Files.isDirectory(issueDir)) {
      logger.severe("Issue directory does not exist: $issueDir")
      return 2
    }

    val outputFile = issueDir.resolve("pde.yaml")
    if (Files.exists(outputFile) && !dryRunOpt) {
      logger.severe("pde.yaml already exists at ${outputFile.toAbsolutePath()}; remove it first or use --dry-run to preview")
      return 3
    }

    // Find git worktrees (dirs with .git file or directory)
    val worktrees = findWorktrees(issueDir)
    if (worktrees.isEmpty()) {
      logger.severe("No git worktrees found in $issueDir; run 'issue pickup <id>' first")
      return 2
    }
    logger.info("Found ${worktrees.size} worktree(s): ${worktrees.map { it.fileName }.joinToString(", ")}")

    // For each worktree, diff against base ref and find touched OSGi bundles
    val bundlePaths = LinkedHashSet<String>()
    for (worktree in worktrees) {
      val changedFiles = gitDiffRunner(worktree, baseRefOpt)
      if (changedFiles == null) {
        logger.warning("Could not diff ${worktree.fileName} against $baseRefOpt; skipping")
        continue
      }
      if (changedFiles.isEmpty()) {
        logger.info("${worktree.fileName}: no changes vs $baseRefOpt")
        continue
      }
      logger.info("${worktree.fileName}: ${changedFiles.size} changed file(s)")
      val bundles = findTouchedBundles(worktree, changedFiles)
      for (bundle in bundles) {
        val relPath = issueDir.relativize(bundle).toString().replace('\\', '/')
        bundlePaths.add(relPath)
        logger.info("  bundle: $relPath")
      }
    }

    if (bundlePaths.isEmpty()) {
      logger.warning("No OSGi bundles (META-INF/MANIFEST.MF) found among changed files")
    }

    // Auto-discover shared includes in parent directories
    val includes = discoverIncludes(issueDir)
    if (includes.isNotEmpty()) {
      logger.info("Discovered ${includes.size} shared include(s): ${includes.joinToString(", ")}")
    }

    val yaml = buildPdeYaml(includes, bundlePaths.toList())

    if (dryRunOpt) {
      println("# pde.yaml (dry-run — not written)")
      println(yaml)
      return 0
    }

    Files.writeString(outputFile, yaml)
    logger.info("Wrote pde.yaml with ${bundlePaths.size} bundle(s) to ${outputFile.toAbsolutePath()}")
    return 0
  }

  /** Find immediate child directories of [issueDir] that are git worktrees (have .git file or dir). */
  private fun findWorktrees(issueDir: Path): List<Path> {
    if (!Files.isDirectory(issueDir)) return emptyList()
    return Files.list(issueDir).use { stream ->
      stream
        .filter { Files.isDirectory(it) }
        .filter { dir ->
          val git = dir.resolve(".git")
          Files.isDirectory(git) || Files.isRegularFile(git)
        }
        .sorted()
        .toList()
    }
  }

  /**
   * Run `git diff --name-only <baseRef>...HEAD` in [worktree].
   * Returns the list of changed relative file paths, or null if the command fails
   * (e.g. base ref doesn't exist).
   */
  private fun runGitDiff(worktree: Path, baseRef: String): List<Path>? {
    return try {
      val process = ProcessBuilder(
        "git", "-C", worktree.toString(),
        "diff", "--name-only", "--diff-filter=d",
        "${baseRef}...HEAD"
      )
        .redirectErrorStream(false)
        .start()
      val stdout = process.inputStream.bufferedReader().readLines()
      val stderr = process.errorStream.bufferedReader().readText()
      val exit = process.waitFor()
      if (exit != 0) {
        logger.fine("git diff failed for ${worktree.fileName} (exit $exit): $stderr")
        return null
      }
      stdout.filter { it.isNotBlank() }.map { Paths.get(it.trim()) }
    } catch (ex: Exception) {
      logger.fine("git diff exception for ${worktree.fileName}: ${ex.message}")
      null
    }
  }

  /**
   * Given a list of relative paths changed in [worktree], find the distinct OSGi bundle
   * roots (directories containing META-INF/MANIFEST.MF) that contain at least one changed file.
   */
  private fun findTouchedBundles(worktree: Path, changedFiles: List<Path>): List<Path> {
    val bundles = LinkedHashSet<Path>()
    for (relativePath in changedFiles) {
      val absoluteFile = worktree.resolve(relativePath).normalize()
      val bundle = findBundleRoot(worktree, absoluteFile)
      if (bundle != null) {
        bundles.add(bundle)
      }
    }
    return bundles.toList()
  }

  /**
   * Walk up from [file] (staying within [worktreeRoot]) to find the nearest directory
   * that contains META-INF/MANIFEST.MF. Returns that directory, or null if none found.
   */
  internal fun findBundleRoot(worktreeRoot: Path, file: Path): Path? {
    var current = if (Files.isDirectory(file)) file else file.parent ?: return null
    val root = worktreeRoot.toAbsolutePath().normalize()
    while (true) {
      val normalized = current.toAbsolutePath().normalize()
      if (!normalized.startsWith(root)) return null
      val manifest = current.resolve("META-INF").resolve("MANIFEST.MF")
      if (Files.isRegularFile(manifest)) return current.toAbsolutePath().normalize()
      val parent = current.parent ?: return null
      if (parent.toAbsolutePath().normalize() == normalized) return null
      current = parent
    }
  }

  /**
   * Look in the parent of [issueDir] for known shared pde config files
   * (target.yaml, launches.yaml) and return them as relative include paths.
   */
  internal fun discoverIncludes(issueDir: Path): List<String> {
    val parent = issueDir.parent ?: return emptyList()
    val candidates = listOf("target.yaml", "launches.yaml")
    return candidates
      .filter { name -> Files.isRegularFile(parent.resolve(name)) }
      .map { name ->
        val rel = issueDir.relativize(parent.resolve(name)).toString().replace('\\', '/')
        rel
      }
  }

  private fun buildPdeYaml(includes: List<String>, bundles: List<String>): String {
    val sb = StringBuilder()
    sb.appendLine("# Generated by pde init-config")
    if (includes.isNotEmpty()) {
      sb.appendLine("includes:")
      for (inc in includes) {
        sb.appendLine("  - $inc")
      }
    }
    if (bundles.isNotEmpty()) {
      sb.appendLine("bundles:")
      for (bundle in bundles) {
        sb.appendLine("  - path: $bundle")
      }
    } else {
      sb.appendLine("bundles: []")
    }
    return sb.toString().trimEnd() + "\n"
  }
}
