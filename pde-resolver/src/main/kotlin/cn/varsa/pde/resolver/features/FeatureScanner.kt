package cn.varsa.pde.resolver.features

import cn.varsa.pde.resolver.index.findProfileFile
import cn.varsa.pde.resolver.index.mapProfileFile
import java.io.File

object FeatureScanner {
  /**
   * Emit features from an Eclipse SDK root (features/ directory).
   */
  fun scanEclipseSdkFeatures(rootDirectory: File, processFeature: (File) -> Unit): Boolean {
    val featuresDir = File(rootDirectory, "features")
    if (!featuresDir.exists() || !featuresDir.isDirectory) return false
    featuresDir.listFiles()?.filterNot { it.isHidden }?.forEach(processFeature)
    return true
  }

  /**
   * Emit features from a target definition profile directory using the given bundle pool.
   */
  fun scanTargetDefinitionFeatures(
    profileDirectory: File,
    bundlePoolDirectory: File,
    processFeature: (File) -> Unit
  ): Boolean {
    val profileFile = findProfileFile(profileDirectory) ?: return false
    val pluginsDirectory = File(bundlePoolDirectory, "plugins").takeIf { it.exists() } ?: return false
    val featureDirectory = File(bundlePoolDirectory, "features")
    mapProfileFile(profileFile, pluginsDirectory, featureDirectory, { }, processFeature)
    return true
  }

  /**
   * Emit features from a p2 installation directory (Oomph), using the profile name.
   */
  fun scanP2Features(
    p2Directory: File,
    profileName: String,
    processFeature: (File) -> Unit
  ): Boolean {
    val profileDir = File(
      p2Directory,
      "org.eclipse.equinox.p2.engine/profileRegistry/${profileName}.profile"
    )
    val profileFile = findProfileFile(profileDir) ?: return false
    val pluginsDirectory = File(p2Directory, "pool/plugins").takeIf { it.exists() } ?: return false
    val featureDirectory = File(p2Directory, "pool/features")
    mapProfileFile(profileFile, pluginsDirectory, featureDirectory, { }, processFeature)
    return true
  }
}

