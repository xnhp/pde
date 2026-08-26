package cn.varsa.pde.launch

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.Manifest
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Directories that are never worth descending into while looking for fetch-jars helper folders. */
private val fetchJarsScanSkipDirs = setOf(".git", "node_modules", "bin", "target")

/** Matches `fetch_jars`, `fetch-jars`, `fetch_v422_jars`, ... */
private val fetchJarsDirNamePattern = Regex("(?i)^fetch[_-].*jars$")

/**
 * Maven plugins (by artifactId) whose listed goals mark a pom as a jar fetcher, with the
 * directory the plugin writes to when no `outputDirectory` is configured.
 */
private val fetcherPlugins: Map<String, FetcherPlugin> = listOf(
  FetcherPlugin("maven-dependency-plugin", setOf("copy-dependencies", "copy", "unpack"), "target/dependency"),
  FetcherPlugin("maven-shade-plugin", setOf("shade"), "target"),
  FetcherPlugin("maven-assembly-plugin", setOf("single"), "target"),
).associateBy { it.artifactId }

private data class FetcherPlugin(val artifactId: String, val goals: Set<String>, val defaultOutputDir: String)

private val fetchJarsLogger: Logger = Logger.getLogger("cn.varsa.pde.launch.FetchJarsCheck")

/**
 * A Maven helper project that downloads third-party jars for a bundle.
 *
 * @property dir the directory containing the helper's `pom.xml` (e.g. `lib/h2/fetch_v14_jars`)
 * @property outputDirs absolute, normalized directories the `maven-dependency-plugin` writes jars to
 */
internal data class FetchJarsProject(val dir: Path, val outputDirs: List<Path>)

/**
 * Finds fetch-jars helper directories under [bundlePath]: directories whose name matches
 * `fetch[_-]*jars` (case-insensitive) and whose `pom.xml` configures the `maven-dependency-plugin`
 * (`copy-dependencies`/`copy`/`unpack`), `maven-shade-plugin` (`shade`) or `maven-assembly-plugin`
 * (`single`). Candidates whose pom does not confirm
 * are logged at FINE and ignored.
 */
internal fun discoverFetchJarsProjects(bundlePath: Path): List<FetchJarsProject> {
  if (!Files.isDirectory(bundlePath)) return emptyList()

  val projects = mutableListOf<FetchJarsProject>()
  Files.walkFileTree(bundlePath, object : SimpleFileVisitor<Path>() {
    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
      val name = dir.fileName?.toString().orEmpty()
      if (dir != bundlePath && name in fetchJarsScanSkipDirs) return FileVisitResult.SKIP_SUBTREE
      if (fetchJarsDirNamePattern.matches(name)) {
        val pom = dir.resolve("pom.xml")
        if (!Files.isRegularFile(pom)) return FileVisitResult.SKIP_SUBTREE
        val outputDirs = readDependencyPluginOutputDirs(pom)
        if (outputDirs == null) {
          fetchJarsLogger.fine("Ignoring ${dir}: pom.xml has no maven-dependency-plugin copy/unpack, maven-shade-plugin shade or maven-assembly-plugin single execution")
        } else {
          projects.add(FetchJarsProject(dir, outputDirs))
        }
        return FileVisitResult.SKIP_SUBTREE
      }
      return FileVisitResult.CONTINUE
    }
  })

  return projects
}

/**
 * Directories the `pde fetch-jars` helper can run `mvn clean package` against.
 */
internal fun discoverRunnableFetchJarsDirs(bundlePath: Path): List<Path> =
  discoverFetchJarsProjects(bundlePath).map { it.dir }

/**
 * Parses [pom] and returns the output directories of its jar-fetching plugins (resolved against
 * the pom's directory), or `null` if the pom configures none of them with a jar-producing goal. Poms that cannot be parsed are treated as non-confirming.
 */
internal fun readDependencyPluginOutputDirs(pom: Path): List<Path>? {
  val document = try {
    DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(pom.toFile())
  } catch (ex: Exception) {
    fetchJarsLogger.fine("Ignoring ${pom}: cannot parse (${ex.message})")
    return null
  }

  val outputDirs = linkedSetOf<String>()
  var confirmed = false
  for (plugin in document.documentElement.descendants("plugin")) {
    val fetcher = fetcherPlugins[plugin.childText("artifactId")] ?: continue
    val executions = plugin.childElement("executions")?.childElements("execution").orEmpty()
    val matching = executions.filter { execution ->
      execution.childElement("goals")?.childElements("goal").orEmpty()
        .any { it.textContent.trim() in fetcher.goals }
    }
    if (matching.isEmpty()) continue
    confirmed = true
    val pluginOutputDirs = linkedSetOf<String>()
    matching.forEach { execution ->
      execution.childElement("configuration")?.childText("outputDirectory")?.let(pluginOutputDirs::add)
    }
    plugin.childElement("configuration")?.childText("outputDirectory")?.let(pluginOutputDirs::add)
    if (pluginOutputDirs.isEmpty()) pluginOutputDirs.add(fetcher.defaultOutputDir)
    outputDirs.addAll(pluginOutputDirs)
  }
  if (!confirmed) return null

  val fetchDir = pom.toAbsolutePath().parent
  return outputDirs.map { raw -> fetchDir.resolve(expandProjectBasedir(raw)).normalize() }
}

private fun expandProjectBasedir(raw: String): String =
  raw.replace("\${project.basedir}/", "").replace("\${basedir}/", "").replace("\${project.basedir}", ".").replace("\${basedir}", ".")

private fun Element.childElements(localName: String): List<Element> {
  val result = mutableListOf<Element>()
  val nodes = childNodes
  for (i in 0 until nodes.length) {
    val node = nodes.item(i)
    if (node is Element && (node.localName ?: node.nodeName) == localName) result.add(node)
  }
  return result
}

private fun Element.childElement(localName: String): Element? = childElements(localName).firstOrNull()

private fun Element.childText(localName: String): String? = childElement(localName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

private fun Element.descendants(localName: String): List<Element> {
  val nodes = getElementsByTagNameNS("*", localName)
  return (0 until nodes.length).map { nodes.item(it) as Element }
}

/**
 * Output directories of fetch-jars helpers under [bundlePath] that are missing or contain no
 * `*.jar`, meaning the helper has not been run yet. Each pair is (output dir, fetch dir).
 */
internal fun findEmptyFetchJarsOutputDirs(bundlePath: Path): List<Pair<Path, Path>> =
  discoverFetchJarsProjects(bundlePath).flatMap { project ->
    project.outputDirs.filter { !containsJar(it) }.map { it to project.dir }
  }

private fun containsJar(dir: Path): Boolean {
  if (!Files.isDirectory(dir)) return false
  return Files.list(dir).use { children -> children.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") } }
}

internal fun warnOnEmptyFetchJarsLibs(bundlePath: Path, logger: Logger) {
  val projects = discoverFetchJarsProjects(bundlePath)
  projects.forEach { project ->
    project.outputDirs.filter { !containsJar(it) }.forEach { outputDir ->
      logger.warning("No jars present in ${outputDir}; run 'mvn clean package' in ${project.dir} (or 'pde fetch-jars') before compiling/analyzing")
    }
  }
  warnOnUnpackagedFetchJarsOutput(bundlePath, projects, logger)
}

/**
 * Warns when none of a helper's output directories is covered by `build.properties`
 * `bin.includes` or by the manifest's `Bundle-ClassPath`, i.e. the fetched jars would probably
 * not end up in the built bundle. Purely advisory.
 */
internal fun warnOnUnpackagedFetchJarsOutput(bundlePath: Path, projects: List<FetchJarsProject>, logger: Logger) {
  if (projects.isEmpty()) return
  val binIncludes = readBinIncludes(bundlePath) ?: return
  val classPath = readBundleClassPath(bundlePath)
  val covering = (binIncludes + classPath).filter { it != "." }
  val root = bundlePath.toAbsolutePath().normalize()

  projects.forEach { project ->
    val relativeOutputs = project.outputDirs.mapNotNull { out ->
      if (out.startsWith(root)) root.relativize(out).toString().replace('\\', '/') else null
    }
    if (relativeOutputs.isEmpty()) return@forEach
    val covered = relativeOutputs.any { out -> covering.any { entry -> entryCovers(entry, out) } }
    if (!covered) {
      logger.warning(
        "Jars fetched by ${project.dir} into ${relativeOutputs.joinToString(", ")} are covered by neither " +
          "bin.includes in build.properties nor Bundle-ClassPath; they may not be packaged into the bundle"
      )
    }
  }
}

/** True if [entry] (a `bin.includes`/`Bundle-ClassPath` entry) covers directory [outputDir], both bundle-relative. */
internal fun entryCovers(entry: String, outputDir: String): Boolean {
  val normalizedEntry = entry.trim().removePrefix("./").replace('\\', '/')
  val out = outputDir.trimEnd('/')
  if (normalizedEntry.isEmpty() || normalizedEntry == ".") return false
  if (normalizedEntry.endsWith("/")) {
    val dir = normalizedEntry.trimEnd('/')
    return out == dir || out.startsWith("$dir/")
  }
  // File (or directory without trailing slash): covers the output dir if it is the dir itself,
  // lives inside it, or names a subtree containing it.
  return normalizedEntry == out || normalizedEntry.startsWith("$out/") || out.startsWith("$normalizedEntry/")
}

private fun readBinIncludes(bundlePath: Path): List<String>? {
  val file = bundlePath.resolve("build.properties")
  if (!Files.isRegularFile(file)) return null
  val props = java.util.Properties()
  Files.newBufferedReader(file).use { props.load(it) }
  val raw = props.getProperty("bin.includes") ?: return null
  return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

private fun readBundleClassPath(bundlePath: Path): List<String> {
  val file = bundlePath.resolve("META-INF/MANIFEST.MF")
  if (!Files.isRegularFile(file)) return emptyList()
  val raw = runCatching { Files.newInputStream(file).use { Manifest(it).mainAttributes.getValue("Bundle-ClassPath") } }.getOrNull()
    ?: return emptyList()
  return raw.split(',').map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }
}
