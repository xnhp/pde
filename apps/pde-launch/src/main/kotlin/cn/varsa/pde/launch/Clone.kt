package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.config.RepoBundles
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.optional
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private class CliException(message: String) : RuntimeException(message)

object CloneCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde clone ${maturityTag("usable")}")
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
      System.err.println("No launch config found (config.yaml/launch.yaml/pde.yaml). Use --config.")
      return 1
    }

    return try {
      val context = LaunchConfigLoader.load(configPath, workingDir)
      cloneFromConfig(context)
      0
    } catch (ex: CliException) {
      System.err.println(ex.message)
      1
    }
  }
}

private fun cloneFromConfig(context: LaunchConfigContext) {
  val baseDir = context.baseDir
  val config = context.config
  if (config.bundlesPerRepo.isEmpty()) {
    fail("No bundlesPerRepo entries found in ${context.file.fileName}.")
  }
  val configuredBranch = config.branch?.trim()?.takeIf { it.isNotBlank() }
  val nonPdeBundles = config.nonPdeBundles.mapNotNull { it.trim().takeIf { it.isNotBlank() } }

  config.bundlesPerRepo.forEach { entry ->
    val entryNonPdeBundles = entry.nonPdeBundles.mapNotNull { it.trim().takeIf { it.isNotBlank() } }
    cloneRepo(entry, baseDir, configuredBranch, nonPdeBundles + entryNonPdeBundles)
  }
}

private fun cloneRepo(
  entry: RepoBundles,
  baseDir: Path,
  configuredBranch: String?,
  nonPdeBundles: List<String>
) {
  val repoPath = Paths.get(entry.repo)
  val repoDir = if (repoPath.isAbsolute) repoPath else baseDir.resolve(repoPath).normalize()
  val repoName = repoDir.fileName?.toString()?.takeIf { it.isNotBlank() }
    ?: requireNonBlank(entry.repo, "Repo name missing for entry.")
  val repoUrl = "git@github.com:knime/$repoName.git"
  val repoExisted = Files.exists(repoDir)

  if (repoExisted) {
    if (!Files.isDirectory(repoDir)) {
      fail("Repo path exists but is not a directory: $repoDir")
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
  } else {
    runGitWithOutput(
      baseDir,
      listOf("clone", "--filter=blob:none", "--no-checkout", repoUrl, repoDir.toString()),
      "Git clone failed for $repoName"
    )
  }

  runGit(
    baseDir,
    listOf("-C", repoDir.toString(), "sparse-checkout", "init", "--cone"),
    "Failed to init sparse-checkout for $repoName"
  )

  val bundleNames = entry.bundles.mapNotNull { it.name.trim().takeIf { name -> name.isNotBlank() } }.distinct()
  var desiredBundles = (bundleNames + selectExistingNonPdeBundles(baseDir, repoDir, nonPdeBundles)).distinct()
  if (desiredBundles.isEmpty()) {
    fail("No bundles listed for repo $repoName.")
  }

  updateSparseCheckout(baseDir, repoDir, desiredBundles, repoName)
  runGit(baseDir, listOf("-C", repoDir.toString(), "checkout"), "Failed to checkout $repoName")

  if (configuredBranch != null) {
    checkoutBranch(baseDir, repoDir, repoName, configuredBranch)
    val refreshedBundles = (bundleNames + selectExistingNonPdeBundles(baseDir, repoDir, nonPdeBundles)).distinct()
    if (refreshedBundles.isNotEmpty()) {
      val missing = refreshedBundles.filterNot { desiredBundles.contains(it) }
      if (missing.isNotEmpty()) {
        updateSparseCheckout(baseDir, repoDir, refreshedBundles, repoName)
        runGit(baseDir, listOf("-C", repoDir.toString(), "checkout"), "Failed to checkout $repoName")
      }
      desiredBundles = refreshedBundles
    }
  }

  if (repoExisted) {
    if (hasUpstream(baseDir, repoDir)) {
      runGitWithOutput(
        baseDir,
        listOf("-C", repoDir.toString(), "pull", "--ff-only"),
        "Git pull failed for $repoName"
      )
    } else {
      System.err.println("Skipping git pull for $repoName: current branch has no upstream.")
    }
  }

  val missingBundles = desiredBundles.filterNot { Files.isDirectory(repoDir.resolve(it)) }
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

private fun checkoutBranch(workingDir: Path, repoDir: Path, repoName: String, branch: String) {
  val localBranches = parseBranchList(
    runGitCapture(
      workingDir,
      listOf("-C", repoDir.toString(), "branch", "--list"),
      "Failed to list local branches for $repoName"
    )
  )
  val remoteBranches = parseBranchList(
    runGitCapture(
      workingDir,
      listOf("-C", repoDir.toString(), "branch", "-r", "--list"),
      "Failed to list remote branches for $repoName"
    )
  ).filterNot { it == "origin/HEAD" }

  when (val checkout = selectCheckoutForConfiguredBranch(branch, localBranches, remoteBranches)) {
    is ConfiguredBranchCheckout.Local -> runGit(
      workingDir,
      listOf("-C", repoDir.toString(), "checkout", checkout.branch),
      "Failed to checkout local branch ${checkout.branch} for $repoName"
    )
    is ConfiguredBranchCheckout.Remote -> runGit(
      workingDir,
      listOf("-C", repoDir.toString(), "checkout", "-t", checkout.branch),
      "Failed to checkout remote branch ${checkout.branch} for $repoName"
    )
    is ConfiguredBranchCheckout.Create -> runGit(
      workingDir,
      listOf("-C", repoDir.toString(), "checkout", "--no-track", "-b", checkout.branch, "origin/HEAD"),
      "Failed to create branch ${checkout.branch} for $repoName"
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

private fun hasUpstream(workingDir: Path, repoDir: Path): Boolean {
  val command = listOf("git", "-C", repoDir.toString(), "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
  val process = ProcessBuilder(command)
    .directory(workingDir.toFile())
    .redirectErrorStream(true)
    .start()
  process.inputStream.bufferedReader().readText()
  return process.waitFor() == 0
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
    "config.yaml",
    "config.yml",
    "launch.yaml",
    "launch.yml",
    "pde.yaml",
    "pde.yml",
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
  if (remote != null) return ConfiguredBranchCheckout.Remote(remote)
  return ConfiguredBranchCheckout.Create(normalized)
}

private fun toOriginBranch(branch: String): String {
  val trimmed = requireNonBlank(branch, "Configured branch must not be blank.")
  val normalized = trimmed.removePrefix("origin/")
  return "origin/$normalized"
}

private sealed class ConfiguredBranchCheckout {
  data class Local(val branch: String) : ConfiguredBranchCheckout()
  data class Remote(val branch: String) : ConfiguredBranchCheckout()
  data class Create(val branch: String) : ConfiguredBranchCheckout()
}

private fun fail(message: String): Nothing = throw CliException(message)
