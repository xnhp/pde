package cn.varsa.idea.pde.partial.plugin.provider

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.pde.resolver.features.FeatureScanner
import cn.varsa.pde.resolver.index.TargetPlatformCache
import cn.varsa.pde.resolver.index.findProfileFile
import cn.varsa.pde.resolver.index.getBundlePoolPath
import cn.varsa.pde.resolver.index.mapProfileFile
import cn.varsa.idea.pde.partial.common.support.*
import com.jetbrains.rd.util.printlnError
import java.io.File
import java.net.URL

/**
 * Read a "Target Definition" p2 profile, accessing the actual bundles from a bundle pool at another location.
 */
class TargetDefinitionBundleProvider : EclipseSDKBundleProvider() {

  override val type: String = "Target Definition Profile"

  override fun resolveDirectory(
    rootDirectory: File, processFeature: (File) -> Unit, processBundle: (File) -> Unit
  ): Boolean {

    // rootDirectory being e.g.
    // /home/ben/eclipse-workspace/.metadata/.plugins/org.eclipse.pde.core/.p2/org.eclipse.equinox.p2.engine/
    //  \ profileRegistry/TARGET_DEFINITION%58;resource%58;%47;org.knime.sdk.setup%47;KNIME-AP.target.profile
    // (that leaf is a directory)
    if (!rootDirectory.name.endsWith(".profile", ignoreCase = true)) {
      return false;
    }

    val profileFile = findProfileFile(rootDirectory) ?: return false

    val bundlePoolPath = getBundlePoolPath(profileFile)

    // for example
    //    "file:/home/ben/git-repositories/target-installer/resources/bundlepool/"
    //    "file:/home/ben/eclipse-workspace/.metadata/.plugins/org.eclipse.pde.core/.bundle_pool/"
    val bundlePool = URL("file:$bundlePoolPath").toURI().toFile().takeIf { it.exists() }
    if (bundlePool == null) {
      printlnError("bundle pool location does not exist: $bundlePoolPath")
      return false;
    }

    // Bundles via TargetPlatformIndex from profile dir
    val index = TargetPlatformCache.buildWithCache(listOf(rootDirectory.toPath()))
    index.bundlesByBsn().values.forEach { versions ->
      versions.values.forEach { rb -> processBundle(rb.location.toFile()) }
    }

    // Features via FeatureScanner
    FeatureScanner.scanTargetDefinitionFeatures(rootDirectory, bundlePool, processFeature)

    return true
  }


}
