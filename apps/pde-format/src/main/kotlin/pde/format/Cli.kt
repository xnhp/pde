package pde.format

import cn.varsa.pde.remoterunner.ConsoleTags
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

enum class Mode {
    FORMAT,
    CHECK
}

data class Range(val start: Int, val end: Int)

data class Options(
    val mode: Mode,
    val eclipseHome: Path,
    val profile: Path,
    val sourceLevel: String,
    val lineSeparator: String,
    val inputFile: Path?,
    val repoDir: Path?,
    val stdinPaths: List<Path>,
    val outPath: Path?,
    val inPlace: Boolean,
    val range: Range?,
    val includes: List<String>,
    val ignore: List<String>,
    val verbose: Boolean
)

data class ParseResult(val options: Options, val showHelp: Boolean)

class CliParser(
    private val args: Array<String>,
    private val stdinLinesProvider: () -> List<String> = { readStdinLines() }
) {
    fun parse(): ParseResult {
        if (args.isEmpty()) {
            return ParseResult(defaultOptions(), showHelp = true)
        }

        if (args[0] == "--help" || args[0] == "-h") {
            return ParseResult(defaultOptions(), showHelp = true)
        }

        val mode = when (args[0]) {
            "fix" -> Mode.FORMAT
            "check" -> Mode.CHECK
            else -> error("Unknown command: ${args[0]}")
        }

        var eclipseHome: Path? = null
        var profile: Path? = null
        var sourceLevel = "17"
        var lineSeparator = "\n"
        var configPath: Path? = null
        var issueDir: Path? = null
        var inputFile: Path? = null
        var repoDir: Path? = null
        var outPath: Path? = null
        var inPlace = false
        var range: Range? = null
        var includes = listOf("**/*.java")
        val ignore = mutableListOf<String>()
        var verbose = false
        val positional = mutableListOf<String>()
        val stdinPaths = mutableListOf<Path>()

        var i = 1
        while (i < args.size) {
            val arg = args[i]
            if (!arg.startsWith("-")) {
                positional.add(arg)
                i += 1
                continue
            }

            when (arg) {
                "--eclipse-home" -> eclipseHome = Path.of(nextValue(args, ++i, arg))
                "--profile" -> profile = Path.of(nextValue(args, ++i, arg))
                "--config" -> configPath = Path.of(nextValue(args, ++i, arg))
                "--issue-dir" -> issueDir = Path.of(nextValue(args, ++i, arg))
                "--source" -> sourceLevel = nextValue(args, ++i, arg)
                "--line-sep" -> lineSeparator = parseLineSeparator(nextValue(args, ++i, arg))
                "--in" -> inputFile = Path.of(nextValue(args, ++i, arg))
                "--repo" -> repoDir = Path.of(nextValue(args, ++i, arg))
                "--out" -> outPath = Path.of(nextValue(args, ++i, arg))
                "--in-place" -> inPlace = true
                "--range" -> range = parseRange(nextValue(args, ++i, arg))
                "--include" -> includes = nextValue(args, ++i, arg).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                "--ignore" -> ignore.add(nextValue(args, ++i, arg))
                "--verbose" -> verbose = true
                "--help", "-h" -> return ParseResult(defaultOptions(), showHelp = true)
                else -> error("Unknown argument: $arg")
            }
            i += 1
        }

        if (positional.isNotEmpty()) {
            if (inputFile != null || repoDir != null) {
                error("Positional path is not allowed with --in or --repo")
            }
            if (positional.size > 1) {
                error("Only one positional path is supported")
            }
            val path = Path.of(positional.first())
            if (!Files.exists(path)) {
                error("Path does not exist: $path")
            }
            if (Files.isDirectory(path)) {
                repoDir = path
            } else {
                inputFile = path
            }
        }

        if (inputFile == null && repoDir == null && positional.isEmpty()) {
            val stdinLines = stdinLinesProvider().map { it.trim() }.filter { it.isNotEmpty() }
            stdinLines.forEach { line ->
                val path = Path.of(line)
                if (!Files.exists(path)) {
                    error("Path does not exist: $path")
                }
                stdinPaths.add(path)
            }
        }

        val resolvedConfig = resolveConfigPath(issueDir, configPath)
        if (configPath != null && (resolvedConfig == null || !Files.exists(resolvedConfig))) {
            error("Config file not found: ${resolvedConfig?.toAbsolutePath() ?: configPath.toAbsolutePath()}")
        }
        val formatterConfigPath = resolveFormatterConfigPath(resolvedConfig)
        val resolvedProfile = profile
            ?: formatterConfigPath
            ?: DefaultFormatterProfile.resolve()

        if (formatterConfigPath != null && !Files.exists(formatterConfigPath)) {
            error("Formatter config not found: ${formatterConfigPath.toAbsolutePath()}")
        }
        if (eclipseHome == null) {
            error("--eclipse-home is required")
        }

        if (inputFile == null && repoDir == null && stdinPaths.isEmpty()) {
            if (System.console() == null && positional.isEmpty()) {
                return ParseResult(defaultOptions(), showHelp = false)
            }
            error("--in or --repo is required")
        }

        if (inputFile != null && repoDir != null) {
            error("--in and --repo are mutually exclusive")
        }

        if (range != null) {
            if (repoDir != null) {
                error("--range is only supported for a single file")
            }
            if (inputFile == null) {
                if (stdinPaths.size != 1 || Files.isDirectory(stdinPaths.first())) {
                    error("--range is only supported for a single file")
                }
            }
        }

        if (outPath != null && inputFile == null) {
            error("--out is only supported with --in")
        }

        if (outPath != null && inPlace) {
            error("--out and --in-place are mutually exclusive")
        }

        if (mode == Mode.CHECK && outPath != null) {
            error("--out is not supported for check")
        }

        if (mode == Mode.CHECK && inPlace) {
            error("--in-place is not supported for check")
        }

        if (mode == Mode.FORMAT && outPath == null) {
            if (repoDir != null || inputFile != null || stdinPaths.isNotEmpty()) {
                inPlace = true
            }
        }

        val options = Options(
            mode = mode,
            eclipseHome = eclipseHome,
            profile = resolvedProfile,
            sourceLevel = sourceLevel,
            lineSeparator = lineSeparator,
            inputFile = inputFile,
            repoDir = repoDir,
            stdinPaths = stdinPaths,
            outPath = outPath,
            inPlace = inPlace,
            range = range,
            includes = includes,
            ignore = ignore,
            verbose = verbose
        )

        return ParseResult(options, showHelp = false)
    }

    private fun parseRange(value: String): Range {
        val parts = value.split(':')
        if (parts.size != 2) {
            error("Invalid range: $value (expected start:end)")
        }
        val start = parts[0].toInt()
        val end = parts[1].toInt()
        if (start < 0 || end < 0 || end < start) {
            error("Invalid range: $value")
        }
        return Range(start, end)
    }

    private fun parseLineSeparator(value: String): String {
        return when (value.lowercase()) {
            "lf" -> "\n"
            "crlf" -> "\r\n"
            "native" -> System.lineSeparator()
            else -> error("Invalid --line-sep: $value (use lf, crlf, native)")
        }
    }

    private fun nextValue(args: Array<String>, index: Int, flag: String): String {
        if (index >= args.size) {
            error("Missing value for $flag")
        }
        return args[index]
    }

    private fun defaultOptions(): Options {
        return Options(
            mode = Mode.FORMAT,
            eclipseHome = Path.of("."),
            profile = Path.of("."),
            sourceLevel = "17",
            lineSeparator = "\n",
            inputFile = null,
            repoDir = null,
            stdinPaths = emptyList(),
            outPath = null,
            inPlace = false,
            range = null,
            includes = listOf("**/*.java"),
            ignore = emptyList(),
            verbose = false
        )
    }

    companion object {
        fun usage(): String {
            return """
pde format ${maturityTag("WIP")}

Usage:
  pde-format fix    --eclipse-home <path> [--profile <path>] [--config <yaml>] [--issue-dir <dir>] --in <file> [--range start:end] [--in-place | --out <path>]
  pde-format fix    --eclipse-home <path> [--profile <path>] [--config <yaml>] [--issue-dir <dir>] --repo <dir> [--include <glob,...>] [--in-place]
  pde-format check  --eclipse-home <path> [--profile <path>] [--config <yaml>] [--issue-dir <dir>] --in <file> [--range start:end]
  pde-format check  --eclipse-home <path> [--profile <path>] [--config <yaml>] [--issue-dir <dir>] --repo <dir> [--include <glob,...>]
  pde-format fix    <file-or-dir>
  pde-format check  <file-or-dir>
  pde-format fix    < paths.txt
  pde-format check  < paths.txt

Options:
  --source <level>     Java source level (default: 17)
  --line-sep <value>   lf | crlf | native (default: lf)
  --include <globs>    Comma-separated globs (default: **/*.java)
  --ignore <text>      Ignore files whose full path contains this text
  --verbose            Print progress and details
  --config <yaml>      Launch config path (discovery base only)
  --issue-dir <dir>    Base directory for config discovery

Config:
  If --profile is omitted, a bundled KNIME formatter profile is used.
""".trimIndent()
        }
    }

}

private fun resolveConfigPath(issueDir: Path?, configPath: Path?): Path? {
    val baseDir = issueDir?.toAbsolutePath()?.normalize() ?: Paths.get("").toAbsolutePath().normalize()
    val explicit = configPath?.let { path ->
        if (path.isAbsolute) path else baseDir.resolve(path).normalize()
    }
    if (explicit != null) {
        return explicit
    }
    return findConfigPath(baseDir)
}

private fun findConfigPath(startDir: Path): Path? {
    val candidates = listOf(
        "pde.yaml",
        "launch.yaml",
        "launch.yml",
        "pde-launch.yaml",
        "pde-launch.yml"
    )
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

private fun resolveFormatterConfigPath(configPath: Path?): Path? {
    if (configPath == null || !Files.exists(configPath)) return null
    LaunchConfigLoader.load(configPath, configPath.parent ?: configPath)
    return null
}

private fun maturityTag(label: String): String {
    val useColor = System.console() != null
    return when (label.lowercase()) {
        "usable" -> ConsoleTags.success(label, useColor)
        "wip" -> ConsoleTags.danger(label, useColor)
        else -> "[$label]"
    }
}

private fun readStdinLines(): List<String> {
    if (System.console() != null) {
        return emptyList()
    }
    return System.`in`.bufferedReader().readLines()
}
