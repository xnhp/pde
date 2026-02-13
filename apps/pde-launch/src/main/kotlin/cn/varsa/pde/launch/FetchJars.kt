package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.config.LaunchConfig
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.config.RepoBundles
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.optional
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private class FetchJarsException(message: String) : RuntimeException(message)

object FetchJarsCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde fetch_jars")
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
      fetchJars(configPath, workingDir)
      0
    } catch (ex: FetchJarsException) {
      System.err.println(ex.message)
      1
    }
  }
}

private fun fetchJars(configPath: Path, workingDir: Path) {
  val baseDir = configPath.parent?.toAbsolutePath()?.normalize() ?: workingDir
  val context = LaunchConfigLoader.load(configPath, baseDir)
  val config = context.config
  if (config.bundlesPerRepo.isEmpty()) {
    fail("No bundlesPerRepo entries found in ${configPath.fileName}.")
  }

  val fetchDirs = findFetchJarsDirs(baseDir, config)
  fetchDirs.forEach { fetchDir ->
    runCommand(
      fetchDir,
      listOf("mvn", "clean", "package"),
      "Failed to run mvn clean package in ${fetchDir}"
    )
  }
}

private fun findFetchJarsDirs(baseDir: Path, config: LaunchConfig): List<Path> {
  val results = mutableListOf<Path>()
  val nonPdeBundles = config.nonPdeBundles.mapNotNull { it.trim().takeIf { name -> name.isNotBlank() } }

  config.bundlesPerRepo.forEach { entry ->
    val repoName = requireNonBlank(entry.repo, "Found repo entry with empty name")
    val repoPath = Paths.get(repoName)
    val repoDir = if (repoPath.isAbsolute) repoPath else baseDir.resolve(repoPath).normalize()
    if (!Files.isDirectory(repoDir)) {
      warn("Repo directory not found for '${repoName}': ${repoDir}; skipping")
      return@forEach
    }

    val entryNonPdeBundles = entry.nonPdeBundles.mapNotNull { it.trim().takeIf { name -> name.isNotBlank() } }
    val bundles = allBundles(entry, nonPdeBundles + entryNonPdeBundles)
    bundles.forEach { bundle ->
      val bundleDir = repoDir.resolve(bundle)
      if (!Files.isDirectory(bundleDir)) {
        warn("Bundle directory not found for '${bundle}' in '${repoName}': ${bundleDir}; skipping")
        return@forEach
      }
      val fetchDir = bundleDir.resolve("lib/fetch_jars")
      if (Files.isDirectory(fetchDir)) {
        results.add(fetchDir)
      }
    }
  }

  return results
}

private fun allBundles(entry: RepoBundles, nonPdeBundles: List<String>): List<String> {
  val bundles = entry.bundles.mapNotNull { it.name.trim().takeIf { name -> name.isNotBlank() } }
  return (bundles + nonPdeBundles).distinct()
}

private fun runCommand(workingDir: Path, command: List<String>, errorMessage: String) {
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

private fun requireNonBlank(value: String?, errorMessage: String): String {
  val trimmed = value?.trim().orEmpty()
  if (trimmed.isBlank()) {
    fail(errorMessage)
  }
  return trimmed
}

private fun warn(message: String) {
  System.err.println(message)
}

private fun fail(message: String): Nothing = throw FetchJarsException(message)
