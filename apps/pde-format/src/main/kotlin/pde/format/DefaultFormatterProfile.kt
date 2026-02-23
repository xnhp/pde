package pde.format

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

object DefaultFormatterProfile {
    private const val RESOURCE_PATH = "/pde/format/defaults/org.eclipse.jdt.core.prefs"
    private val cachedPath = AtomicReference<Path?>()

    fun resolve(): Path {
        val existing = cachedPath.get()
        if (existing != null && Files.exists(existing)) {
            return existing
        }
        val resource = DefaultFormatterProfile::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: error("Missing default formatter profile resource: $RESOURCE_PATH")
        val tempFile = Files.createTempFile("pde-format-profile-", ".prefs")
        writeTo(tempFile, resource)
        tempFile.toFile().deleteOnExit()
        cachedPath.set(tempFile)
        return tempFile
    }

    private fun writeTo(path: Path, input: InputStream) {
        input.use { source ->
            Files.newOutputStream(path).use { output ->
                source.copyTo(output)
            }
        }
    }
}
