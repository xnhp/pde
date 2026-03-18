package cn.varsa.pde.launch

import cn.varsa.cli.core.CliFailure
import cn.varsa.cli.core.CliLogLevel
import cn.varsa.cli.core.CliLogging
import cn.varsa.cli.core.CliStyle
import cn.varsa.cli.core.ColorMode
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.config.RepoBundles
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.optional
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Logger

private class CliException(message: String) : CliFailure(message)
private val logger = Logger.getLogger(WorktreesInitCommand::class.java.name)

object WorktreesInitCommand {
  fun main(args: Array<String>): Int {
    CliLogging.configure(CliLogLevel.INFO, CliStyle.useColor(ColorMode.AUTO))
    val parser = ArgParser("pde worktrees-init ${maturityTag("usable")}")
    val configOpt by parser.option(
      ArgType.String,
      fullName = "config",
      description = "YAML launch configuration path"
    )
    val configPos by parser.argument(
      ArgType.String,
      description = "YAML launch configuration (positional)"
    ).optional()
    parser.parse(args)

    val workingDir = Paths.get("").toAbsolutePath()
    val configPath = resolveConfigPath(workingDir, configOpt, configPos)
    if (configPath == null) {
      System.err.println("No launch config found (pde.yaml/launch.yaml/pde-launch.yaml). Use --config.")
      return 1
    }

    return try {
      val context = LaunchConfigLoader.load(configPath, workingDir)
      worktreesInitFromConfig(context)
      0
    } catch (ex: CliFailure) {
      System.err.println(ex.message)
      1
    }
  }
}

private fun worktreesInitFromConfig(context: LaunchConfigContext) {
  val baseDir = context.baseDir
  val config = context.config
  if (config.bundlesPerRepo.isEmpty()) {
    fail("No bundlesPerRepo entries found in ${context.file.fileName}.")
  }
  val configuredBranch = requireNonBlank(config.branch, "branch is required for worktrees-init.")
  val baseReposPath = requireNonBlank(config.baseReposPath, "baseReposPath is required for worktrees-init.")
  val baseReposDir = resolvePath(baseDir, baseReposPath)
  if (!Files.exists(baseReposDir)) {
    fail("Base repos path does not exist: $baseReposDir")
  }
  if (!Files.isDirectory(baseReposDir)) {
    fail("Base repos path is not a directory: $baseReposDir")
  }
  val nonPdeBundles = config.nonPdeBundles.mapNotNull { it.trim().takeIf { it.isNotBlank() } }

  config.bundlesPerRepo.forEach { entry ->
    val entryNonPdeBundles = entry.nonPdeBundles.mapNotNull { it.trim().takeIf { it.isNotBlank() } }
    worktreeRepo(entry, baseDir, baseReposDir, configuredBranch, nonPdeBundles + entryNonPdeBundles)
  }
}

private fun worktreeRepo(
  entry: RepoBundles,
  baseDir: Path,
  baseReposDir: Path,
  configuredBranch: String,
  nonPdeBundles: List<String>
) {
  val repoPath = Paths.get(entry.repo)
  val repoName = repoPath.fileName?.toString()?.takeIf { it.isNotBlank() }
    ?: requireNonBlank(entry.repo, "Repo name missing for entry.")
  val repoDir = baseReposDir.resolve(repoName).normalize()
  val worktreeDir = baseDir.resolve(repoName).normalize()
  val repoUrl = "git@github.com:knime/$repoName.git"
  if (!Files.exists(repoDir)) {
    fail("Base repo not found: $repoDir")
  }
  if (!Files.isDirectory(repoDir)) {
    fail("Base repo path exists but is not a directory: $repoDir")
  }
  if (Files.exists(worktreeDir)) {
    logger.info("Worktree path already exists: $worktreeDir")
    return
  }

  ensureGitRepo(baseDir, repoDir, repoName)
  val originUrl = runGitCapture(
    baseDir,
    listOf("-C", repoDir.toString(), "remote", "get-url", "origin"),
    "Failed to read origin URL for $repoName"
  )
  if (originUrl != repoUrl) {
    fail("Origin URL mismatch for $repoName: expected $repoUrl, got $originUrl")
  }
  runGitWithOutput(
    baseDir,
    listOf("-C", repoDir.toString(), "fetch", "--prune"),
    "Git fetch failed for $repoName"
  )

  val localBranches = parseBranchList(
    runGitCapture(
      baseDir,
      listOf("-C", repoDir.toString(), "branch", "--list"),
      "Failed to list local branches for $repoName"
    )
  )
  val remoteBranches = parseBranchList(
    runGitCapture(
      baseDir,
      listOf("-C", repoDir.toString(), "branch", "-r", "--list"),
      "Failed to list remote branches for $repoName"
    )
  ).filterNot { it == "origin/HEAD" }

  when (val checkout = selectCheckoutForConfiguredBranch(configuredBranch, localBranches, remoteBranches)) {
    is ConfiguredBranchCheckout.Local -> runGit(
      baseDir,
      listOf("-C", repoDir.toString(), "worktree", "add", worktreeDir.toString(), checkout.branch),
      "Failed to create worktree for $repoName"
    )
    is ConfiguredBranchCheckout.Remote -> runGit(
      baseDir,
      listOf("-C", repoDir.toString(), "worktree", "add", "-b", checkout.localBranch, worktreeDir.toString(), checkout.remoteBranch),
      "Failed to create worktree for $repoName"
    )
    is ConfiguredBranchCheckout.Create -> runGit(
      baseDir,
      listOf("-C", repoDir.toString(), "worktree", "add", "-b", checkout.branch, worktreeDir.toString(), "origin/HEAD"),
      "Failed to create worktree for $repoName"
    )
  }

  runGit(
    baseDir,
    listOf("-C", worktreeDir.toString(), "sparse-checkout", "init", "--cone"),
    "Failed to init sparse-checkout for $repoName"
  )

  val bundleNames = entry.bundles.mapNotNull { it.name.trim().takeIf { name -> name.isNotBlank() } }.distinct()
  var desiredBundles = (bundleNames + selectExistingNonPdeBundles(baseDir, worktreeDir, nonPdeBundles)).distinct()
  if (desiredBundles.isEmpty()) {
    fail("No bundles listed for repo $repoName.")
  }

  updateSparseCheckout(baseDir, worktreeDir, desiredBundles, repoName)
  runGit(baseDir, listOf("-C", worktreeDir.toString(), "checkout"), "Failed to checkout $repoName")

  val refreshedBundles = (bundleNames + selectExistingNonPdeBundles(baseDir, worktreeDir, nonPdeBundles)).distinct()
  if (refreshedBundles.isNotEmpty()) {
    val missing = refreshedBundles.filterNot { desiredBundles.contains(it) }
    if (missing.isNotEmpty()) {
      updateSparseCheckout(baseDir, worktreeDir, refreshedBundles, repoName)
      runGit(baseDir, listOf("-C", worktreeDir.toString(), "checkout"), "Failed to checkout $repoName")
    }
    desiredBundles = refreshedBundles
  }

  val missingBundles = desiredBundles.filterNot { Files.isDirectory(worktreeDir.resolve(it)) }
  if (missingBundles.isNotEmpty()) {
    fail("Missing bundle directories in $repoName: ${missingBundles.joinToString(", ")}")
  }
}

private fun updateSparseCheckout(
  workingDir: Path,
  repoDir: Path,
  desiredBundles: List<String>,
  repoName: String
) {
  val existingSparse = parseSparseCheckoutList(
    runGitCapture(
      workingDir,
      listOf("-C", repoDir.toString(), "sparse-checkout", "list"),
      "Failed to read sparse checkout list for $repoName"
    )
  )
  if (existingSparse.isEmpty()) {
    runGit(
      workingDir,
      listOf("-C", repoDir.toString(), "sparse-checkout", "set", "--") + desiredBundles,
      "Failed to set sparse checkout for $repoName"
    )
    return
  }
  val missing = desiredBundles.filterNot { existingSparse.contains(it) }
  if (missing.isNotEmpty()) {
    runGit(
      workingDir,
      listOf("-C", repoDir.toString(), "sparse-checkout", "add", "--") + missing,
      "Failed to update sparse checkout for $repoName"
    )
  }
}

private fun selectExistingNonPdeBundles(workingDir: Path, repoDir: Path, candidates: List<String>): List<String> {
  if (candidates.isEmpty()) return emptyList()
  val output = runGitCapture(
    workingDir,
    listOf("-C", repoDir.toString(), "ls-tree", "-d", "--name-only", "HEAD"),
    "Failed to list repository tree for ${repoDir.fileName ?: repoDir}"
  )
  if (output.isBlank()) return emptyList()
  val existing = output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
  return candidates.filter { existing.contains(it) }
}

private fun ensureGitRepo(workingDir: Path, repoDir: Path, repoName: String) {
  runGit(
    workingDir,
    listOf("-C", repoDir.toString(), "rev-parse", "--git-dir"),
    "Repo $repoName exists but is not a git repository"
  )
}

private fun runGit(workingDir: Path, args: List<String>, errorMessage: String) {
  runGitCapture(workingDir, args, errorMessage)
}

private fun runGitWithOutput(workingDir: Path, args: List<String>, errorMessage: String) {
  val output = runGitCapture(workingDir, args, errorMessage)
  if (output.isNotBlank()) {
    println(output)
  }
}

private fun runGitCapture(workingDir: Path, args: List<String>, errorMessage: String): String {
  val command = listOf("git") + args
  val process = ProcessBuilder(command)
    .directory(workingDir.toFile())
    .redirectErrorStream(true)
    .start()
  val output = process.inputStream.bufferedReader().readText().trim()
  val exitCode = process.waitFor()
  if (exitCode != 0) {
    val details = if (output.isNotBlank()) "\n$output" else ""
    fail("$errorMessage.$details")
  }
  return output
}

private fun parseSparseCheckoutList(output: String): List<String> = output
  .lineSequence()
  .map { it.trim() }
  .filter { it.isNotBlank() }
  .distinct()
  .toList()

private fun parseBranchList(output: String): List<String> = output
  .lineSequence()
  .map { it.removePrefix("*").trim() }
  .map { it.substringBefore(" -> ") }
  .filter { it.isNotBlank() }
  .distinct()
  .toList()

private fun requireNonBlank(value: String?, errorMessage: String): String {
  val trimmed = value?.trim().orEmpty()
  if (trimmed.isBlank()) {
    fail(errorMessage)
  }
  return trimmed
}

private fun resolveConfigPath(baseDir: Path, configOpt: String?, configPos: String?): Path? {
  val candidate = configOpt ?: configPos?.takeIf { looksLikeYamlFile(it) }
  if (candidate != null) {
    return resolvePath(baseDir, candidate)
  }
  return discoverConfigFile(baseDir)
}

private fun resolvePath(baseDir: Path, raw: String): Path {
  val path = Paths.get(raw)
  return if (path.isAbsolute) path else baseDir.resolve(path).normalize()
}

private fun looksLikeYamlFile(value: String): Boolean =
  value.endsWith(".yaml", ignoreCase = true) || value.endsWith(".yml", ignoreCase = true)

private fun discoverConfigFile(baseDir: Path): Path? {
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

private fun selectCheckoutForConfiguredBranch(
  branch: String,
  localBranches: List<String>,
  remoteBranches: List<String>
): ConfiguredBranchCheckout {
  val normalized = requireNonBlank(branch, "Configured branch must not be blank.")
    .removePrefix("origin/")
  val local = localBranches.firstOrNull { it == normalized }
  if (local != null) return ConfiguredBranchCheckout.Local(local)
  val remoteName = toOriginBranch(normalized)
  val remote = remoteBranches.firstOrNull { it == remoteName }
  if (remote != null) return ConfiguredBranchCheckout.Remote(remote, normalized)
  return ConfiguredBranchCheckout.Create(normalized)
}

private fun toOriginBranch(branch: String): String {
  val trimmed = requireNonBlank(branch, "Configured branch must not be blank.")
  val normalized = trimmed.removePrefix("origin/")
  return "origin/$normalized"
}

private sealed class ConfiguredBranchCheckout {
  data class Local(val branch: String) : ConfiguredBranchCheckout()
  data class Remote(val remoteBranch: String, val localBranch: String) : ConfiguredBranchCheckout()
  data class Create(val branch: String) : ConfiguredBranchCheckout()
}

private fun fail(message: String): Nothing = throw CliException(message)
