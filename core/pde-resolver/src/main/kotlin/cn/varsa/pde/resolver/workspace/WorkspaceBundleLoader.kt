package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.support.parseVersionRange
import org.osgi.framework.Constants.BUNDLE_VERSION_ATTRIBUTE
import java.io.File
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.Properties

/**
 * Build [WorkspaceBundleDescriptor] instances from filesystem bundles.
 */
object WorkspaceBundleLoader {
  fun load(path: Path): WorkspaceBundleDescriptor {
    val file = path.toFile()
    val manifest = when {
      file.isDirectory -> loadDirectoryManifest(file)
      file.isFile && file.extension.equals("jar", ignoreCase = true) -> loadJarManifest(file)
      else -> error("Unsupported workspace bundle path: $path")
    }

    val absolute = path.toAbsolutePath().normalize()
    val classPathEntries = computeClassPathEntries(file, absolute, manifest)
    val buildProps = if (file.isDirectory) loadBuildProperties(file) else null
    val sourceRoots = computeSourceRoots(absolute, buildProps)
    val resources = computeResourceRules(buildProps)
    val compilerPrefs = if (file.isDirectory) loadCompilerPrefs(file) else emptyMap()
    val moduleAccess = if (file.isDirectory) loadModuleAccess(file) else ModuleAccess()
    val executionEnvironment = manifest[org.osgi.framework.Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT]
    val outputInfo = if (file.isDirectory) computeOutputDir(absolute, buildProps) else null
    val fragmentHost = manifest.fragmentHost?.let { entry ->
      WorkspaceBundleDescriptor.FragmentHost(
        symbolicName = entry.key,
        versionRange = entry.value.attribute[BUNDLE_VERSION_ATTRIBUTE]?.parseVersionRange()
      )
    }

    return WorkspaceBundleDescriptor(
      path = absolute,
      manifest = manifest,
      classPathEntries = classPathEntries,
      fragmentHost = fragmentHost,
      sourceRoots = sourceRoots,
      resourceIncludes = resources.first,
      resourceExcludes = resources.second,
      compilerPrefs = compilerPrefs,
      executionEnvironment = executionEnvironment,
      outputDirectory = outputInfo?.directory,
      outputDirectoryFromBuildProperties = outputInfo?.fromBuildProperties ?: false,
      addExports = moduleAccess.addExports,
      addOpens = moduleAccess.addOpens
    )
  }

  private data class OutputDirInfo(
    val directory: Path,
    val fromBuildProperties: Boolean
  )

  private data class ModuleAccess(
    val addExports: List<String> = emptyList(),
    val addOpens: List<String> = emptyList()
  )

  private fun loadDirectoryManifest(dir: File): BundleManifest {
    val manifestFile = File(dir, "META-INF/MANIFEST.MF")
    if (!manifestFile.isFile) error("Missing MANIFEST.MF in ${dir.absolutePath}")
    manifestFile.inputStream().use { stream ->
      return BundleManifest.parse(java.util.jar.Manifest(stream))
    }
  }

  private fun loadJarManifest(jarFile: File): BundleManifest {
    JarFile(jarFile).use { jar ->
      val mf = jar.manifest ?: error("Missing MANIFEST.MF in ${jarFile.absolutePath}")
      return BundleManifest.parse(mf)
    }
  }

  private fun computeClassPathEntries(bundleFile: File, base: Path, manifest: BundleManifest): List<Path> {
    val entries = mutableListOf(base)
    manifest.bundleClassPath?.keys
      ?.filter { it != "." }
      ?.forEach { entry ->
        if (bundleFile.isDirectory) {
          val resolved = base.resolve(entry).normalize()
          if (resolved.toFile().exists()) entries.add(resolved)
        }
        // For jar bundles we currently expose only the jar itself.
      }
    return entries.distinct()
  }

  private fun loadBuildProperties(dir: File): java.util.Properties? {
    val file = File(dir, "build.properties")
    if (!file.isFile) return null
    return java.util.Properties().apply { file.inputStream().use { load(it) } }
  }

  private fun computeSourceRoots(base: Path, props: java.util.Properties?): List<Path> {
    if (props == null) return listOf(base.resolve("src")).filter { it.toFile().exists() }
    val sourceKeys = props.stringPropertyNames().filter { it.startsWith("source.") }
    if (sourceKeys.isEmpty()) {
      val candidate = base.resolve("src")
      return listOf(candidate).filter { it.toFile().exists() }
    }
    return sourceKeys.flatMap { key ->
      props.getProperty(key)?.split(',')?.map { it.trim() } ?: emptyList()
    }.filter { it.isNotEmpty() }
      .map { base.resolve(it).normalize() }
  }

  private fun computeResourceRules(props: java.util.Properties?): Pair<List<String>, List<String>> {
    if (props == null) return listOf(".") to emptyList()
    val includes = props.getProperty("bin.includes")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf(".")
    val excludes = props.getProperty("bin.excludes")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    return includes to excludes
  }

  private fun loadCompilerPrefs(dir: File): Map<String, String> {
    val prefsFile = File(dir, ".settings/org.eclipse.jdt.core.prefs")
    if (!prefsFile.isFile) return emptyMap()
    return java.util.Properties().apply { prefsFile.inputStream().use { load(it) } }
      .entries.associate { it.key.toString() to it.value.toString() }
  }

  /**
   * Read `--add-exports`/`--add-opens` tokens declared on the JDT JRE_CONTAINER entry in
   * `<dir>/.classpath`. Each attribute value is a `:`-separated list of `module/pkg=TARGET` tokens.
   */
  private fun loadModuleAccess(dir: File): ModuleAccess {
    val classpathFile = File(dir, ".classpath")
    if (!classpathFile.isFile) return ModuleAccess()
    val document = runCatching {
      val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isNamespaceAware = false
      }
      classpathFile.inputStream().use { factory.newDocumentBuilder().parse(it) }
    }.getOrNull() ?: return ModuleAccess()

    val entries = document.getElementsByTagName("classpathentry")
    for (i in 0 until entries.length) {
      val entry = entries.item(i) as? org.w3c.dom.Element ?: continue
      if (entry.getAttribute("kind") != "con") continue
      if (!entry.getAttribute("path").contains("org.eclipse.jdt.launching.JRE_CONTAINER")) continue
      val attributes = attributeValues(entry)
      return ModuleAccess(
        addExports = splitTokens(attributes["add-exports"]),
        addOpens = splitTokens(attributes["add-opens"])
      )
    }
    return ModuleAccess()
  }

  private fun attributeValues(entry: org.w3c.dom.Element): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val attributeNodes = entry.getElementsByTagName("attribute")
    for (i in 0 until attributeNodes.length) {
      val attribute = attributeNodes.item(i) as? org.w3c.dom.Element ?: continue
      val name = attribute.getAttribute("name")
      if (name.isNotEmpty()) result[name] = attribute.getAttribute("value")
    }
    return result
  }

  private fun splitTokens(value: String?): List<String> =
    value?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

  private fun computeOutputDir(base: Path, props: Properties?): OutputDirInfo {
    val output = props?.getProperty("output..")
      ?.split(',')
      ?.map { it.trim() }
      ?.firstOrNull { it.isNotEmpty() }
    return if (output != null) {
      OutputDirInfo(base.resolve(output).normalize(), true)
    } else {
      OutputDirInfo(base.resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR), false)
    }
  }
}
