package cn.varsa.pde.resolver.cli.config

import java.nio.file.Files
import java.nio.file.Path

object WhitelistFileLoader {
  fun load(path: Path): Set<String>? {
    if (!Files.exists(path)) return null
    val lines = Files.readAllLines(path)
    val result = lines.mapNotNull { raw ->
      val trimmed = raw.substringBefore('#').trim()
      trimmed.takeIf { it.isNotEmpty() }
    }.toSet()
    return if (result.isEmpty()) emptySet() else result
  }
}
