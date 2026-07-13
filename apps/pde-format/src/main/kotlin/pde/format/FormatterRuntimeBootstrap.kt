package pde.format

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

object FormatterRuntimeBootstrap {
    private const val RUNTIME_RESOURCE = "/formatter-runtime.zip"
    private const val RUNTIME_VERSION = "1"

    fun resolve(): Path {
        val cacheRoot = defaultCacheDir()
        val runtimeRoot = cacheRoot.resolve(RUNTIME_VERSION)
        if (isReady(runtimeRoot)) {
            return runtimeRoot
        }
        val stream = FormatterRuntimeBootstrap::class.java.getResourceAsStream(RUNTIME_RESOURCE)
            ?: error("Missing formatter runtime resource: $RUNTIME_RESOURCE")
        Files.createDirectories(runtimeRoot)
        stream.use { extractZip(it, runtimeRoot) }
        require(isReady(runtimeRoot)) {
            "Invalid formatter runtime: missing plugins directory"
        }
        return runtimeRoot
    }

    private fun defaultCacheDir(): Path {
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".cache")
        return base.resolve("pde-runtime").resolve("formatter")
    }

    private fun extractZip(input: java.io.InputStream, destination: Path) {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destination.resolve(entry.name).normalize()
                require(target.startsWith(destination)) { "Invalid zip entry: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    BufferedOutputStream(Files.newOutputStream(target)).use { out ->
                        zip.copyTo(out)
                    }
                }
            }
        }
    }

    private fun isReady(runtimeRoot: Path): Boolean = Files.isDirectory(runtimeRoot.resolve("plugins"))
}
