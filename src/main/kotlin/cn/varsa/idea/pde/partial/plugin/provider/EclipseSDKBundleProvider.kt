package cn.varsa.idea.pde.partial.plugin.provider

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.pde.resolver.features.FeatureScanner
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import java.io.File

open class EclipseSDKBundleProvider : DirectoryBundleProvider() {
  override val type: String = "Eclipse SDK"

  override fun resolveDirectory(
    rootDirectory: File,
    processFeature: (File) -> Unit,
    processBundle: (File) -> Unit
  ): Boolean {
    // features via FeatureScanner
    FeatureScanner.scanEclipseSdkFeatures(rootDirectory, processFeature)

    // bundles (plugins + dropins) via TargetPlatformIndex
    val index = TargetPlatformIndex.build(listOf(rootDirectory.toPath()))
    var found = false
    index.bundlesByBsn().values.forEach { versions ->
      versions.values.forEach { rb ->
        processBundle(rb.location.toFile())
        found = true
      }
    }
    return found
  }
}
