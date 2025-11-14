package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.manifest.BundleManifest
import java.io.File
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Minimal loader that produces [WorkspaceBundleDescriptor] instances from filesystem bundles.
 * Additional metadata (Bundle-ClassPath entries, fragment hosts, etc.) can be layered on later.
 */
object WorkspaceBundleLoader {
  fun load(path: Path): WorkspaceBundleDescriptor {
    val file = path.toFile()
    val manifest = when {
      file.isDirectory -> loadDirectoryManifest(file)
      file.isFile && file.extension.equals("jar", ignoreCase = true) -> loadJarManifest(file)
      else -> error("Unsupported workspace bundle path: $path")
    }
    return WorkspaceBundleDescriptor(path.toAbsolutePath().normalize(), manifest)
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
}
