package cn.varsa.pde.resolver.index

import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.manifest.requiredBundleAndVersion
import cn.varsa.pde.resolver.support.parseVersion
import org.osgi.framework.Version
import org.osgi.framework.VersionRange
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.jar.JarFile

class TargetPlatformIndex(
  private val byBsn: Map<String, NavigableMap<Version, ResolvedBundle>>,
  val source: Source = Source.SCANNED,
  precomputedExportsByPackage: Map<String, List<ResolvedBundle>>? = null
) {
  enum class Source { SCANNED, CACHED }
  fun bundlesByBsn(): Map<String, NavigableMap<Version, ResolvedBundle>> = byBsn

  // Lazily built index: package name -> list of bundles exporting it (unordered)
  @Volatile
  private var exportsByPackage: Map<String, List<ResolvedBundle>>? = precomputedExportsByPackage
  @Volatile
  private var exportsByPackageNav: Map<String, NavigableMap<Version, ResolvedBundle>>? = null
  @Volatile
  private var requiresByBsn: Map<String, NavigableMap<Version, Map<String, VersionRange>>>? = null

  fun exportedBundlesByPackage(): Map<String, List<ResolvedBundle>> {
    val cached = exportsByPackage
    if (cached != null) return cached

    val map = HashMap<String, MutableList<ResolvedBundle>>()
    byBsn.values.forEach { nav ->
      nav.values.forEach { rb ->
        val man = rb.manifest
        // Keys of exportPackage are package names (may include .*) – normalize like exportedPackageAndVersion()
        val pkgs = man.exportPackage?.keys?.map { it.substringBefore(".*") } ?: emptyList()
        pkgs.forEach { pkg -> map.computeIfAbsent(pkg) { mutableListOf() }.add(rb) }
      }
    }

    // Optional: order per package by bundle version descending (cheap heuristic)
    map.replaceAll { _, list -> list.sortedByDescending { it.manifest.bundleVersion } as MutableList<ResolvedBundle> }

    exportsByPackage = map
    exportsByPackageNav = null
    return map
  }

  fun exportedBundlesByPackageNav(): Map<String, NavigableMap<Version, ResolvedBundle>> {
    val cached = exportsByPackageNav
    if (cached != null) return cached
    val byList = exportedBundlesByPackage()
    val map = HashMap<String, NavigableMap<Version, ResolvedBundle>>()
    byList.forEach { (pkg, list) ->
      val nav = TreeMap<Version, ResolvedBundle>()
      list.forEach { rb -> nav[rb.manifest.bundleVersion] = rb }
      if (nav.isNotEmpty()) map[pkg] = nav
    }
    exportsByPackageNav = map
    return map
  }

  fun requiresByBundle(): Map<String, NavigableMap<Version, Map<String, VersionRange>>> {
    val cached = requiresByBsn
    if (cached != null) return cached
    val map = HashMap<String, NavigableMap<Version, Map<String, VersionRange>>>()
    byBsn.forEach { (bsn, nav) ->
      val reqNav = TreeMap<Version, Map<String, VersionRange>>()
      nav.forEach { (ver, rb) -> reqNav[ver] = rb.manifest.requiredBundleAndVersion() }
      if (reqNav.isNotEmpty()) map[bsn] = reqNav
    }
    requiresByBsn = map
    return map
  }

  fun get(bsn: String, range: VersionRange? = null): ResolvedBundle? {
    val versions = byBsn[bsn] ?: return null
    if (range == null) return versions.lastEntry()?.value
    return versions.descendingMap().entries.firstOrNull { range.includes(it.key) }?.value
  }

  companion object {
    fun build(roots: List<Path>): TargetPlatformIndex {
      val map = HashMap<String, NavigableMap<Version, ResolvedBundle>>()

      fun addBundle(file: File) {
        val manifest = readBundleManifest(file) ?: return
        val bsn = manifest.bundleSymbolicName?.key ?: return
        val ver = manifest.bundleVersion
        val rb = ResolvedBundle(file.toPath(), manifest, file.isDirectory)
        map.computeIfAbsent(bsn) { TreeMap() }[ver] = rb
      }

      roots.forEach { root ->
        val f = root.toFile()
        when {
          // Eclipse SDK-like directory: has plugins dir
          File(f, "plugins").isDirectory -> scanEclipseSdkRoot(f, ::addBundle)

          // Target definition profile directory
          f.isDirectory && f.name.endsWith(".profile", true) -> scanTargetDefinitionProfile(f, ::addBundle)

          // Single directory of bundles
          f.isDirectory -> f.listFiles()?.filterNot { it.isHidden }?.forEach(::addBundle)

          // Single jar/dir bundle
          f.isFile -> addBundle(f)
        }
      }

      return TargetPlatformIndex(map, Source.SCANNED)
    }

    private fun scanEclipseSdkRoot(root: File, addBundle: (File) -> Unit) {
      val plugins = File(root, "plugins")
      plugins.listFiles()?.filterNot { it.isHidden }?.forEach(addBundle)
      // Dropins may host bundles too
      val dropins = File(root, "dropins")
      dropins.listFiles()?.filterNot { it.isHidden }?.forEach { d ->
        if (d.isDirectory) File(d, "plugins").listFiles()?.forEach(addBundle)
      }
    }

    private fun scanTargetDefinitionProfile(profileDir: File, addBundle: (File) -> Unit) {
      val profileFile = findProfileFile(profileDir) ?: return
      val bundlePoolPath = getBundlePoolPath(profileFile) ?: return
      val bundlePool = File(bundlePoolPath.removePrefix("file:"))
      if (!bundlePool.exists()) return
      val pluginsDirectory = File(bundlePool, "plugins")
      val featuresDirectory = File(bundlePool, "features")
      mapProfileFile(profileFile, pluginsDirectory, featuresDirectory, addBundle) { _ -> }
    }

    private fun readBundleManifest(file: File): BundleManifest? {
      return try {
        when {
          file.isDirectory -> {
            val mf = File(file, "META-INF/MANIFEST.MF")
            if (!mf.exists()) null
            else mf.inputStream().use { BundleManifest.parse(java.util.jar.Manifest(it)) }
          }
          file.isFile && file.extension.equals("jar", true) ->
            JarFile(file).use { jar ->
              val mf = jar.manifest ?: return null
              BundleManifest.parse(mf)
            }
          else -> null
        }
      } catch (_: Exception) {
        null
      }
    }
  }
}
