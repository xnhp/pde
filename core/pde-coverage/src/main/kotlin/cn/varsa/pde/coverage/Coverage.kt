package cn.varsa.pde.coverage

import cn.varsa.pde.cli.support.bundledLibDir
import cn.varsa.pde.cli.support.findJarInDir
import java.nio.file.Files
import java.nio.file.Path

/** pde's bundled JaCoCo CLI jar (`org.jacoco.cli:nodeps`), or null when not running from the dist. */
internal fun findBundledJacocoCli(): Path? = bundledLibDir()?.let { findJarInDir(it, "org.jacoco.cli") }

/** All `*.exec` files anywhere under [dir] (the per-run files written by `--coverage`), sorted. */
internal fun findExecFiles(dir: Path): List<Path> {
  if (!Files.isDirectory(dir)) return emptyList()
  return Files.walk(dir).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".exec") }.sorted().toList()
  }
}

/**
 * jacococli `--classfiles` inputs for a resolved class-output [dir]. jacococli aborts the whole report if
 * **any** `.jar` is reachable under a `--classfiles` directory (a second, differently-compiled copy of the
 * same classes — e.g. a packaged bundle `<name>.jar`, or a Maven/Tycho jar under `bin/target/`). So: if tree
 * is jar-free, hand over the whole [dir]; otherwise hand over only its immediate subdirectories that contain
 * no `.jar` anywhere beneath — keeping the loose `.class` package roots (`org/…`, `META-INF/…`) that pde's
 * dev-mode launch actually loads, while dropping the packaged jar and any `target/`.
 */
internal fun coverageClassfileInputs(dir: Path): List<Path> {
  if (!Files.isDirectory(dir)) return emptyList()
  if (!containsJarAnywhere(dir)) return listOf(dir)
  return Files.list(dir).use { stream ->
    stream.filter { Files.isDirectory(it) && !containsJarAnywhere(it) }.sorted().toList()
  }
}

/** True if any `.jar` file exists anywhere in the tree rooted at [dir]. */
private fun containsJarAnywhere(dir: Path): Boolean =
  Files.walk(dir).use { paths -> paths.anyMatch { it.fileName?.toString()?.endsWith(".jar") == true } }

/** Assembles the jacococli `report` argument list (exec inputs + class/source dirs + outputs). */
internal fun jacocoReportArgs(
  execFiles: List<Path>,
  classDirs: List<Path>,
  sourceDirs: List<Path>,
  xmlOut: Path,
  htmlOut: Path?,
): List<String> = buildList {
  add("report")
  execFiles.forEach { add(it.toString()) }
  classDirs.forEach { add("--classfiles"); add(it.toString()) }
  sourceDirs.forEach { add("--sourcefiles"); add(it.toString()) }
  add("--xml"); add(xmlOut.toString())
  if (htmlOut != null) {
    add("--html"); add(htmlOut.toString())
  }
}

/** Runs the bundled jacococli `report` ([reportArgs] from [jacocoReportArgs]) as `java -jar <cli>`; returns the exit code. */
internal fun runJacocoReport(cliJar: Path, javaBin: String, reportArgs: List<String>): Int {
  val command = listOf(javaBin, "-jar", cliJar.toString()) + reportArgs
  return ProcessBuilder(command).redirectErrorStream(true).inheritIO().start().waitFor()
}
