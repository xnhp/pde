package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.discoverConfigFile
import cn.varsa.pde.resolver.cli.looksLikeYamlFile
import cn.varsa.pde.resolver.cli.config.LaunchConfig
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private class FetchJarsException(message: String) : RuntimeException(message)

/** Overridable in tests to avoid actually invoking Maven. */
internal var fetchJarsCommandRunner: (Path, List<String>) -> Int = { workingDir, command ->
  runFetchJarsProcess(workingDir, command)
}

object FetchJarsCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde fetch-jars")
    val configOpt by parser.option(
      ArgType.String,
      fullName = "config",
      description = "YAML launch configuration path"
    )
    parser.parse(args)

    val workingDir = Paths.get("").toAbsolutePath()
    val configPath = resolveConfigPath(workingDir, configOpt)
    if (configPath == null) {
      System.err.println("No launch config found (pde.yaml/launch.yaml/pde-launch.yaml). Use --config.")
      return 1
    }

    return try {
      val context = LaunchConfigLoader.load(configPath, workingDir)
      runFetchJars(context)
      0
    } catch (ex: FetchJarsException) {
      System.err.println(ex.message)
      1
    }
  }
}

private fun runFetchJars(context: LaunchConfigContext) {
  val config = context.config
  if (config.bundles.isEmpty()) {
    fail("No bundles entries found in ${context.file.fileName}.")
  }

  val bundleDirs = resolveBundleDirs(context.baseDir, config)
  val fetchJarsDirs = bundleDirs.flatMap { discoverRunnableFetchJarsDirs(it) }.distinct().sortedBy { it.toString() }
  if (fetchJarsDirs.isEmpty()) {
    println("No fetch-jars helper directories (fetch_jars, fetch_v*_jars, ... with a maven-dependency/shade/assembly-plugin pom.xml) found.")
    return
  }

  for (fetchJarsDir in fetchJarsDirs) {
    println("[1m${fetchJarsDir}[0m")
    val exitCode = fetchJarsCommandRunner(fetchJarsDir, listOf("mvn", "clean", "package"))
    if (exitCode != 0) {
      fail("mvn clean package failed in ${fetchJarsDir}")
    }
  }
}

private fun resolveBundleDirs(baseDir: Path, config: LaunchConfig): List<Path> {
  val unique = linkedMapOf<Path, Path>()
  config.bundles.forEach { entry ->
    val bundlePath = resolvePath(baseDir, entry.path)
    if (!Files.isDirectory(bundlePath)) {
      fail("Bundle directory not found for '${entry.path}': ${bundlePath}")
    }
    unique.putIfAbsent(bundlePath, bundlePath)
  }
  return unique.values.toList()
}

private fun runFetchJarsProcess(workingDir: Path, command: List<String>): Int {
  val process = ProcessBuilder(command)
    .directory(workingDir.toFile())
    .redirectErrorStream(true)
    .start()
  process.inputStream.bufferedReader().useLines { lines -> lines.forEach { println(it) } }
  return process.waitFor()
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

private fun fail(message: String): Nothing = throw FetchJarsException(message)
