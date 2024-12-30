package cn.varsa.idea.pde.partial.plugin.provider

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.idea.pde.partial.common.support.*
import java.io.*
import java.net.*
import java.util.*

open class EclipseP2BundleProvider : EclipseSDKBundleProvider() {
  override val type: String = "Eclipse Oomph"
  override fun resolveDirectory(
    rootDirectory: File, processFeature: (File) -> Unit, processBundle: (File) -> Unit
  ): Boolean {
    val configIni = File(rootDirectory, "configuration/config.ini").takeIf { it.exists() && it.isFile }?.inputStream()
      ?.use { Properties().apply { load(it) } } ?: return false

    val p2Area = configIni.getProperty("eclipse.p2.data.area") ?: return false
    val profileName = configIni.getProperty("eclipse.p2.profile") ?: return false

    val p2Directory = try {
      URI(p2Area).toFile().takeIf { it.exists() } ?: return false
    } catch (e: Exception) {
      return false
    }

    val pluginsDirectory = File(p2Directory, "pool/$Plugins").takeIf { it.exists() } ?: return false
    val featureDirectory = File(p2Directory, "pool/$Features")

    // changes here should have only factored out functionality that is also used by LCBundleProvider.
    // Did not test this class here, though.
    val profileFile = findProfileFile(File(
      p2Directory, "org.eclipse.equinox.p2.engine/profileRegistry/$profileName.profile"
    )) ?: return false

    mapProfileFile(profileFile, pluginsDirectory, featureDirectory, processBundle, processFeature)

    super.resolveDirectory(rootDirectory, processFeature, processBundle)
    return true
  }

}
