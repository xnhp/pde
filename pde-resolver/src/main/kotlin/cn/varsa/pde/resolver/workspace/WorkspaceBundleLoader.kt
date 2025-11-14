package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.support.parseVersionRange
import org.osgi.framework.Constants.BUNDLE_VERSION_ATTRIBUTE
import java.io.File
import java.nio.file.Path
import java.util.jar.JarFile

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
      fragmentHost = fragmentHost
    )
  }

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
}
