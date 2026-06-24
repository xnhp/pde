package cn.varsa.pde.cli.support

import cn.varsa.pde.remoterunner.ConsoleTags
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("pde-cli-support")

fun createFormatter(useColor: Boolean) = object : Formatter() {
  override fun format(record: LogRecord): String {
    val message = formatMessage(record)
    val builder = StringBuilder()
    builder.append(logPrefix(record.level, useColor)).append(' ').append(message).append('\n')
    record.thrown?.let { thrown ->
      val writer = java.io.StringWriter()
      val printer = java.io.PrintWriter(writer)
      thrown.printStackTrace(printer)
      printer.flush()
      builder.append(writer.toString())
    }
    return builder.toString()
  }
}

fun logPrefix(level: Level, useColor: Boolean): String {
  val value = level.intValue()
  return when {
    value >= Level.SEVERE.intValue() -> ConsoleTags.error(useColor)
    value >= Level.WARNING.intValue() -> ConsoleTags.warn(useColor)
    value >= Level.INFO.intValue() -> ConsoleTags.info(useColor)
    value >= Level.FINE.intValue() -> ConsoleTags.debug(useColor)
    else -> ConsoleTags.trace(useColor)
  }
}

fun configureLogging(level: Level, useColor: Boolean) {
  logger.level = level
  val root = Logger.getLogger("")
  root.level = level
  val formatter = createFormatter(useColor)
  root.handlers?.forEach { handler ->
    handler.level = level
    handler.formatter = formatter
  }
}

fun parseLogLevel(value: String?): Level = when (value?.lowercase()) {
  "error", "severe" -> Level.SEVERE
  "warn", "warning" -> Level.WARNING
  "info" -> Level.INFO
  "debug", "fine" -> Level.FINE
  "trace", "finest" -> Level.FINEST
  else -> Level.WARNING
}

fun resolveLogLevel(logLevel: String?, verbose: Boolean, debug: Boolean): Level = when {
  logLevel != null -> parseLogLevel(logLevel)
  debug -> Level.FINE
  verbose -> Level.INFO
  else -> Level.WARNING
}

fun shouldUseColor(noColor: Boolean = false): Boolean = !noColor && System.console() != null

fun logCommand(command: List<String>) {
  if (logger.isLoggable(Level.INFO)) {
    logger.log(Level.INFO, "Executing: {0}", command.joinToString(" "))
  }
}

fun looksLikeYamlFile(arg: String): Boolean {
  val lower = arg.lowercase()
  return lower.endsWith(".yaml") || lower.endsWith(".yml")
}

fun normalizeArgsWithImplicitConfig(
  rawArgs: Array<String>,
  optionsRequiringValue: Set<String>
): Array<String> {
  if (rawArgs.any { it == "--config" || it.startsWith("--config=") }) return rawArgs

  var skipNext = false
  rawArgs.forEachIndexed { index, arg ->
    if (skipNext) {
      skipNext = false
      return@forEachIndexed
    }

    if (arg.startsWith("-")) {
      val optionName = arg.substringBefore('=')
      if (optionsRequiringValue.contains(optionName) && !arg.contains('=')) {
        skipNext = true
      }
      return@forEachIndexed
    }

    if (looksLikeYamlFile(arg)) {
      val normalized = rawArgs.toMutableList()
      normalized.add(index, "--config")
      return normalized.toTypedArray()
    }
  }

  return rawArgs
}

fun discoverConfigFile(baseDir: Path = Paths.get("").toAbsolutePath()): Path? {
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

fun discoverTargetDefinition(baseDir: Path): Path? {
  if (!Files.isDirectory(baseDir)) return null
  val matches = Files.newDirectoryStream(baseDir, "*.target").use { stream ->
    stream.toList()
  }
  return when {
    matches.isEmpty() -> null
    matches.size == 1 -> matches.first().toAbsolutePath().normalize()
    else -> error("Multiple .target files found in $baseDir; set target.definition explicitly.")
  }
}

/** Best-effort recursive delete (children before parents via reverse-sorted walk); never throws. */
fun deleteRecursivelyQuietly(dir: Path) {
  if (!Files.exists(dir)) return
  runCatching {
    Files.walk(dir).use { paths ->
      paths.sorted(compareByDescending<Path> { it }).forEach { runCatching { Files.delete(it) } }
    }
  }
}

/** Finds the first jar in [dir] whose file name starts with [namePrefix]. */
fun findJarInDir(dir: Path, namePrefix: String): Path? {
  if (!Files.isDirectory(dir)) return null
  return Files.newDirectoryStream(dir, "$namePrefix*.jar").use { it.firstOrNull() }
}

private object BundledLibAnchor

/** The directory holding pde's bundled jars (its distribution `lib/`), derived from this class's location. */
fun bundledLibDir(): Path? {
  val location = BundledLibAnchor::class.java.protectionDomain?.codeSource?.location ?: return null
  val path = runCatching { Paths.get(location.toURI()) }.getOrNull() ?: return null
  return if (Files.isRegularFile(path)) path.parent else null
}
