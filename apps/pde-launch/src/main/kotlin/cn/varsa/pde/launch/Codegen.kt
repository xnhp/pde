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
import java.util.Comparator

private class CodegenException(message: String) : RuntimeException(message)

object CodegenCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde codegen ${maturityTag("WIP")}")
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
      runCodegen(configPath, workingDir)
      0
    } catch (ex: CodegenException) {
      System.err.println(ex.message)
      1
    }
  }
}

private data class RepoEntryResolved(val entry: RepoBundles, val dir: Path)

private fun runCodegen(configPath: Path, workingDir: Path) {
  val baseDir = configPath.parent?.toAbsolutePath()?.normalize() ?: workingDir
  val context = LaunchConfigLoader.load(configPath, baseDir)
  val config = context.config
  if (config.bundlesPerRepo.isEmpty()) {
    fail("No bundlesPerRepo entries found in ${configPath.fileName}.")
  }

  val comSharedEntry = findRepoEntry(config, baseDir, "knime-com-shared")
    ?: fail("Repo 'knime-com-shared' not configured in ${configPath.fileName}.")
  if (!hasBundle(comSharedEntry.entry, "com.knime.gateway.codegen")) {
    fail("Bundle 'com.knime.gateway.codegen' is not configured under repo 'knime-com-shared'.")
  }

  val gatewayEntry = findRepoEntry(config, baseDir, "knime-gateway")
    ?: fail("Repo 'knime-gateway' not configured in ${configPath.fileName}.")

  val codegenDir = comSharedEntry.dir.resolve("com.knime.gateway.codegen")
  if (!Files.isDirectory(codegenDir)) {
    fail("Codegen bundle directory not found: ${codegenDir}")
  }

  val gatewayDir = gatewayEntry.dir
  if (!Files.isDirectory(gatewayDir)) {
    fail("Repo directory not found for '${gatewayEntry.entry.repo}': ${gatewayDir}")
  }

  val generatedPaths = listOf(
    "org.knime.gateway.api/src/generated",
    "org.knime.gateway.json/src/generated",
    "org.knime.gateway.impl/src/generated"
  )
  generatedPaths
    .map { gatewayDir.resolve(it) }
    .forEach { deleteRecursively(it) }

  runCommand(
    codegenDir,
    listOf("mvn", "compile", "exec:java", "-Dexec.mainClass=com.knime.gateway.codegen.Generate"),
    "Failed to run gateway codegen in ${codegenDir}"
  )

  val stagedPaths = generatedPaths.filter { path ->
    val absPath = gatewayDir.resolve(path)
    Files.exists(absPath) || runGitCapture(
      gatewayDir,
      listOf("ls-files", "--", path),
      "Failed to check tracked generated files in ${gatewayDir}"
    ).isNotBlank()
  }
  if (stagedPaths.isNotEmpty()) {
    runGit(
      gatewayDir,
      listOf("add", "-A", "--") + stagedPaths,
      "Failed to stage generated code in ${gatewayDir}"
    )
  }
}

private fun findRepoEntry(config: LaunchConfig, baseDir: Path, repoName: String): RepoEntryResolved? {
  return config.bundlesPerRepo.firstNotNullOfOrNull { entry ->
    val raw = entry.repo.trim()
    if (raw.isBlank()) return@firstNotNullOfOrNull null
    val repoPath = Paths.get(raw)
    val repoDir = if (repoPath.isAbsolute) repoPath else baseDir.resolve(repoPath).normalize()
    val resolvedName = repoDir.fileName?.toString().orEmpty()
    if (raw == repoName || resolvedName == repoName) RepoEntryResolved(entry, repoDir) else null
  }
}

private fun hasBundle(entry: RepoBundles, bundleName: String): Boolean {
  if (entry.bundles.any { it.name.trim() == bundleName }) return true
  return entry.nonPdeBundles.any { it.trim() == bundleName }
}

private fun deleteRecursively(path: Path) {
  if (!Files.exists(path)) return
  Files.walk(path)
    .sorted(Comparator.reverseOrder())
    .forEach { Files.deleteIfExists(it) }
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

private fun runGit(workingDir: Path, args: List<String>, errorMessage: String) {
  runGitCapture(workingDir, args, errorMessage)
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

private fun fail(message: String): Nothing = throw CodegenException(message)
