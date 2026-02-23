package pde.format

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class RunResult(val changed: Boolean, val changedFiles: List<Path>)

class FormatterRunner(
    private val formatter: EclipseFormatter,
    private val options: Options
) {
    fun run(): RunResult {
        val changedFiles = mutableListOf<Path>()
        val targets = when {
            options.stdinPaths.isNotEmpty() -> expandStdinPaths(options.stdinPaths)
            options.inputFile != null -> listOf(options.inputFile)
            options.repoDir != null -> FileScanner.scan(options.repoDir, options.includes)
            else -> emptyList()
        }

        val filteredTargets = targets.filterNot { shouldIgnore(it) }

        if (filteredTargets.isEmpty()) {
            return RunResult(changed = false, changedFiles = emptyList())
        }

        for (file in filteredTargets) {
            val original = Files.readString(file, StandardCharsets.UTF_8)
            val formatted = formatter.format(original, options.range)
            if (formatted != original) {
                changedFiles.add(file)
                if (options.mode == Mode.FORMAT) {
                    writeFormatted(file, formatted)
                }
            }
            if (options.verbose && formatted != original) {
                println(file.toAbsolutePath())
            }
        }

        if (options.mode == Mode.CHECK && changedFiles.isNotEmpty()) {
            println("Formatting changes needed (${changedFiles.size} files)")
            if (options.verbose) {
                changedFiles.forEach { println(it.toAbsolutePath()) }
            }
        }

        return RunResult(changed = changedFiles.isNotEmpty(), changedFiles = changedFiles)
    }

    private fun shouldIgnore(path: Path): Boolean {
        if (options.ignore.isEmpty()) {
            return false
        }
        val fullPath = path.toAbsolutePath().toString()
        return options.ignore.any { fullPath.contains(it) }
    }

    private fun expandStdinPaths(paths: List<Path>): List<Path> {
        val expanded = mutableListOf<Path>()
        for (path in paths) {
            if (Files.isDirectory(path)) {
                expanded.addAll(FileScanner.scan(path, options.includes))
            } else {
                expanded.add(path)
            }
        }
        return expanded
    }

    private fun writeFormatted(inputFile: Path, formatted: String) {
        val outputPath = when {
            options.outPath != null -> options.outPath
            options.inPlace -> inputFile
            options.repoDir != null -> inputFile
            else -> return
        }
        if (options.outPath != null && options.inputFile != null) {
            val parent = outputPath.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
        }
        Files.writeString(outputPath, formatted, StandardCharsets.UTF_8)
    }
}
