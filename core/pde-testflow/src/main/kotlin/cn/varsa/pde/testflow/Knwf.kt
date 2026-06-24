package cn.varsa.pde.testflow

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Extracts a workflow (or workflow-group) ZIP stream (e.g. a `--knwf` archive) into [destDir],
 * preserving the entry layout. Guards against zip-slip: an entry that would resolve outside [destDir]
 * aborts the extraction.
 */
internal fun unzipInto(source: InputStream, destDir: Path) {
  val destRoot = destDir.toAbsolutePath().normalize()
  ZipInputStream(source).use { zip ->
    var entry = zip.nextEntry
    while (entry != null) {
      val target = destRoot.resolve(entry.name).normalize()
      check(target.startsWith(destRoot)) {
        "Refusing to extract entry outside destination: ${entry.name}"
      }
      if (entry.isDirectory) {
        Files.createDirectories(target)
      } else {
        target.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(target).use { out -> zip.copyTo(out) }
      }
      zip.closeEntry()
      entry = zip.nextEntry
    }
  }
}
