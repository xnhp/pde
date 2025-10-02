package cn.varsa.idea.pde.partial.plugin.provider

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.pde.resolver.features.FeatureScanner
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.idea.pde.partial.common.support.*
import java.io.File
import java.net.URI
import java.util.Properties

open class EclipseP2BundleProvider : EclipseSDKBundleProvider() {
  override val type: String = "Eclipse Oomph"

  override fun resolveDirectory(
    rootDirectory: File,
    processFeature: (File) -> Unit,
    processBundle: (File) -> Unit
  ): Boolean {
    val configIni = File(rootDirectory, "configuration/config.ini")
      .takeIf { it.exists() && it.isFile }
      ?.inputStream()?.use { Properties().apply { load(it) } } ?: return false

    val p2Area = configIni.getProperty("eclipse.p2.data.area") ?: return false
    val profileName = configIni.getProperty("eclipse.p2.profile") ?: return false

    val p2Directory = try {
      URI(p2Area).toFile().takeIf { it.exists() } ?: return false
    } catch (_: Exception) {
      return false
    }

    // Discover bundles via TargetPlatformIndex
    val profileDir = File(p2Directory, "org.eclipse.equinox.p2.engine/profileRegistry/$profileName.profile")
    val index = TargetPlatformIndex.build(listOf(profileDir.toPath()))
    index.bundlesByBsn().values.forEach { versions ->
      versions.values.forEach { rb -> processBundle(rb.location.toFile()) }
    }

    // Emit features from profile via FeatureScanner
    FeatureScanner.scanP2Features(p2Directory, profileName, processFeature)

    return true
  }
}
