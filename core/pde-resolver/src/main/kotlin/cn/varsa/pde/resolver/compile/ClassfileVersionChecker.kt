package cn.varsa.pde.resolver.compile

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class ClassfileVersionMismatch(
  val classpathEntry: String,
  val classFile: String,
  val majorVersion: Int
)

object ClassfileVersionChecker {
  fun classMajorForLevel(level: String): Int? {
    val normalized = level.removePrefix("1.").trim()
    val version = normalized.toIntOrNull() ?: return null
    return if (version >= 5) version + 44 else null
  }

  fun findMismatches(classpath: List<String>, maxMajor: Int, limit: Int = 20): List<ClassfileVersionMismatch> {
    val mismatches = mutableListOf<ClassfileVersionMismatch>()
    for (entry in classpath) {
      if (mismatches.size >= limit) break
      if (entry.isBlank()) continue
      val path = Path.of(entry)
      if (!Files.exists(path)) continue
      val mismatch = when {
        path.isDirectory() -> findMismatchInDirectory(path, maxMajor)
        entry.lowercase().endsWith(".jar") -> findMismatchInJar(path, maxMajor)
        else -> null
      }
      if (mismatch != null) {
        mismatches += mismatch.copy(classpathEntry = entry)
      }
    }
    return mismatches
  }

  private fun findMismatchInJar(path: Path, maxMajor: Int): ClassfileVersionMismatch? {
    return runCatching {
      JarFile(path.toFile()).use { jar ->
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          if (entry.isDirectory || !entry.name.endsWith(".class")) continue
          jar.getInputStream(entry).use { input ->
            val major = readMajorVersion(input) ?: return@use
            if (major > maxMajor) {
              return ClassfileVersionMismatch(path.toString(), entry.name, major)
            }
          }
        }
        null
      }
    }.getOrNull()
  }

  private fun findMismatchInDirectory(path: Path, maxMajor: Int): ClassfileVersionMismatch? {
    return runCatching {
      Files.walk(path).use { stream ->
        val iterator = stream.iterator()
        while (iterator.hasNext()) {
          val file = iterator.next()
          if (Files.isDirectory(file) || !file.name.endsWith(".class")) continue
          Files.newInputStream(file).use { input ->
            val major = readMajorVersion(input) ?: return@use
            if (major > maxMajor) {
              val relative = path.relativize(file).toString()
              return ClassfileVersionMismatch(path.toString(), relative, major)
            }
          }
        }
        null
      }
    }.getOrNull()
  }

  private fun readMajorVersion(input: InputStream): Int? {
    val header = ByteArray(8)
    var read = 0
    while (read < header.size) {
      val count = input.read(header, read, header.size - read)
      if (count <= 0) return null
      read += count
    }
    val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
    val magic = buffer.int
    if (magic != 0xCAFEBABE.toInt()) return null
    buffer.short // minor
    val major = buffer.short.toInt() and 0xFFFF
    return major
  }
}
