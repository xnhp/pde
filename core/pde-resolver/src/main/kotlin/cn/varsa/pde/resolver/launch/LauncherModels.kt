package cn.varsa.pde.resolver.launch

import org.osgi.framework.Version
import java.nio.file.Path

data class LauncherOptions(
  val product: String? = null,
  val application: String? = null,
  val splashBSN: String? = null,
  val frameworkBSN: String = "org.eclipse.osgi",
  val defaultStartLevel: Int = 4,
  val autoStartDefault: Boolean = true,
  val frameworkExtensions: List<String> = emptyList()
)

data class BundleStartSpec(
  val bsn: String,
  val version: Version,
  val location: Path,
  val startLevel: Int,
  val autoStart: Boolean,
  val isWorkspace: Boolean
)

data class LauncherPlan(
  val bundles: List<BundleStartSpec>,
  val framework: BundleStartSpec?,
  val properties: Map<String, String> = emptyMap()
)

data class LaunchContext(
  val startupLevels: Map<String, Int> = emptyMap(),
  // dev.properties entries: BSN -> list of relative source roots
  val devProperties: Map<String, List<String>> = emptyMap()
)

