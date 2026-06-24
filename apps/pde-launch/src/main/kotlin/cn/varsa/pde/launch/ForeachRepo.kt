package cn.varsa.pde.launch

import cn.varsa.pde.cli.support.discoverConfigFile
import cn.varsa.pde.cli.support.looksLikeYamlFile
import cn.varsa.pde.resolver.cli.maturityTag
import cn.varsa.pde.resolver.cli.config.LaunchConfig
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private class ForeachRepoException(message: String) : RuntimeException(message)

object ForeachRepoCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde foreach-repo ${maturityTag("usable")}")
    val configOpt by parser.option(
      ArgType.String,
      fullName = "config",
      description = "YAML launch configuration path"
    )
    val noRepoHeaders by parser.option(
      ArgType.Boolean,
      fullName = "no-repo-headers",
      description = "Disable printing repo names before command output"
    ).default(false)
    val command by parser.argument(ArgType.String, description = "Shell command to run")
    parser.parse(args)

    val workingDir = Paths.get("").toAbsolutePath()
    val configPath = resolveConfigPath(workingDir, configOpt)
    if (configPath == null) {
      System.err.println("No launch config found (pde.yaml/launch.yaml/pde-launch.yaml). Use --config.")
      return 1
    }

    return try {
      val context = LaunchConfigLoader.load(configPath, workingDir)
      runForeach(context, command, noRepoHeaders)
      0
    } catch (ex: ForeachRepoException) {
      System.err.println(ex.message)
      1
    }
  }
}

private fun runForeach(context: LaunchConfigContext, command: String, noRepoHeaders: Boolean) {
  val config = context.config
  if (config.bundles.isEmpty()) {
    fail("No bundles entries found in ${context.file.fileName}.")
  }

  val normalizedCommand = requireNonBlank(command, "Command must be non-empty")
  val repoDirs = resolveRepoDirs(context.baseDir, config)
  for (repo in repoDirs) {
    if (!noRepoHeaders) {
      println("\u001b[1m${repo.name}\u001b[0m")
    }
    runShellCommand(
      repo.path,
      normalizedCommand,
      "Failed to run command in repo '${repo.name}'"
    )
  }
}

private data class RepoDir(val name: String, val path: Path)

private fun resolveRepoDirs(baseDir: Path, config: LaunchConfig): List<RepoDir> {
  val unique = linkedMapOf<Path, RepoDir>()
  config.bundles.forEach { entry ->
    val bundlePath = resolvePath(baseDir, entry.path)
    if (!Files.isDirectory(bundlePath)) {
      fail("Bundle directory not found for '${entry.path}': ${bundlePath}")
    }
    val repoDir = bundlePath.parent ?: fail("Bundle path has no parent directory: ${entry.path}")
    val repoName = repoDir.fileName?.toString()?.takeIf { it.isNotBlank() }
      ?: requireNonBlank(repoDir.toString(), "Repo name missing for bundle '${entry.path}'.")
    unique.putIfAbsent(repoDir, RepoDir(repoName, repoDir))
  }
  return unique.values.toList()
}

private fun runShellCommand(workingDir: Path, command: String, errorMessage: String) {
  val process = ProcessBuilder(listOf("sh", "-c", command))
    .directory(workingDir.toFile())
    .redirectErrorStream(true)
    .start()

  val output = StringBuilder()
  process.inputStream.bufferedReader().useLines { lines ->
    lines.forEach { line ->
      println(line)
      output.appendLine(line)
    }
  }
  val exitCode = process.waitFor()
  if (exitCode != 0) {
    val details = if (output.isBlank()) "" else "\n${output.toString().trimEnd()}"
    fail("${errorMessage}.${details}")
  }
}

private fun requireNonBlank(value: String?, errorMessage: String): String {
  val trimmed = value?.trim().orEmpty()
  if (trimmed.isBlank()) {
    fail(errorMessage)
  }
  return trimmed
}

private fun resolveConfigPath(baseDir: Path, configOpt: String?): Path? {
  val candidate = configOpt?.takeIf { looksLikeYamlFile(it) }
  if (candidate != null) {
    return resolvePath(baseDir, candidate)
  }
  return discoverConfigFile(baseDir)
}

private fun resolvePath(baseDir: Path, raw: String): Path {
  val path = Paths.get(raw)
  return if (path.isAbsolute) path else baseDir.resolve(path).normalize()
}

private fun fail(message: String): Nothing = throw ForeachRepoException(message)
