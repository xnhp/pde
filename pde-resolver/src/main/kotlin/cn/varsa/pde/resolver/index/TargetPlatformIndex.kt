package cn.varsa.pde.resolver.index

import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.support.parseVersion
import org.osgi.framework.Version
import org.osgi.framework.VersionRange
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.jar.JarFile

class TargetPlatformIndex(
  private val byBsn: Map<String, NavigableMap<Version, ResolvedBundle>>,
  val source: Source = Source.SCANNED
) {
  enum class Source { SCANNED, CACHED }
  fun bundlesByBsn(): Map<String, NavigableMap<Version, ResolvedBundle>> = byBsn

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
