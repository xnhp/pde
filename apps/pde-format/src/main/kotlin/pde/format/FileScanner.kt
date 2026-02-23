package pde.format

import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path

object FileScanner {
    fun scan(root: Path, includes: List<String>): List<Path> {
        val matchers = includes.map { glob -> matcher(root.fileSystem, glob) }
        val files = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { path ->
                val relative = root.relativize(path)
                if (matchers.any { it.matches(relative) }) {
                    files.add(path)
                }
            }
        }
        return files
    }

    private fun matcher(fileSystem: FileSystem, glob: String) =
        fileSystem.getPathMatcher("glob:$glob")
}
