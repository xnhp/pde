package cn.varsa.idea.pde.partial.plugin.provider

import cn.varsa.idea.pde.partial.plugin.openapi.provider.TargetBundleProvider
import cn.varsa.pde.resolver.index.TargetPlatformCache
import java.io.File

open class DirectoryBundleProvider : TargetBundleProvider {
  override val type: String = "Directory"

  override fun resolveDirectory(
    rootDirectory: File,
    processFeature: (File) -> Unit,
    processBundle: (File) -> Unit
  ): Boolean {
    if (!rootDirectory.exists()) return false

    val index = TargetPlatformCache.buildWithCache(listOf(rootDirectory.toPath()))
    index.bundlesByBsn().values.forEach { versions ->
      versions.values.forEach { rb -> processBundle(rb.location.toFile()) }
    }
    return true
  }
}
