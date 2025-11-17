package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.plugin.domain.BundleDefinition
import cn.varsa.idea.pde.partial.common.ProjectLibraryNamePrefix
import cn.varsa.pde.resolver.algo.ResolveResult
import com.intellij.openapi.roots.OrderEntry

internal fun dependencyOrderFrom(
  result: ResolveResult,
  map: Map<String, OrderEntry>
): List<OrderEntry> = result.bundles
  .filter { !it.isWorkspace }
  .flatMap { rb ->
    val dash = "${rb.bsn}-${rb.version}"
    val at = "$ProjectLibraryNamePrefix${rb.bsn}${BundleDefinition.canonicalNameSeparator}${rb.version}"
    listOf(dash, "$ProjectLibraryNamePrefix$dash", at)
  }
  .mapNotNull { map[it] }
