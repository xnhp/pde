package cn.varsa.pde.resolver.launch

import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

object RuntimeLayoutWriter {
  data class LayoutPaths(
    val configDir: Path,
    val configIniFile: Path,
    val devPropertiesFile: Path,
    val bundlesInfoFile: Path
  ) {
    companion object {
      fun fromConfigDir(configDir: Path): LayoutPaths = LayoutPaths(
        configDir = configDir,
        configIniFile = configDir.resolve("config.ini"),
        devPropertiesFile = configDir.resolve("dev.properties"),
        bundlesInfoFile = configDir.resolve("org.eclipse.equinox.simpleconfigurator").resolve("bundles.info")
      )
    }
  }

  data class Defaults(
    val installArea: Path,
    val instanceArea: Path,
    val p2DataArea: String = "@config.dir/.p2",
    val cascaded: Boolean = false,
    val simpleConfiguratorEntry: String = "org.eclipse.equinox.simpleconfigurator@1:start",
    val extraProperties: Map<String, String> = emptyMap(),
    val fallbackConfig: Properties = Properties(),
    val configComment: String = "Configuration File",
    val devComment: String = "Development File"
  )

  fun write(
    layout: LayoutPaths,
    plan: LauncherPlan,
    context: LaunchContext,
    options: LauncherOptions,
    defaults: Defaults
  ) {
    layout.configDir.createDirectories()
    layout.bundlesInfoFile.parent.createDirectories()

    val configProps = ConfigIniRenderer.toProperties(plan, options).apply {
      putIfAbsent("osgi.install.area", defaults.installArea.toUri().toString())
      putIfAbsent("osgi.instance.area.default", defaults.instanceArea.toUri().toString())
      putIfAbsent("org.eclipse.equinox.simpleconfigurator.configUrl", layout.bundlesInfoFile.toUri().toString())
      putIfAbsent("eclipse.p2.data.area", defaults.p2DataArea)
      putIfAbsent("osgi.configuration.cascaded", defaults.cascaded.toString())
      putIfAbsent("org.eclipse.update.reconcile", "false")
      putIfAbsent("osgi.bundles", defaults.simpleConfiguratorEntry)
      defaults.extraProperties.forEach { (k, v) -> putIfAbsent(k, v) }
      defaults.fallbackConfig.stringPropertyNames().forEach { key ->
        val value = defaults.fallbackConfig.getProperty(key)
        if (value != null && getProperty(key) == null) setProperty(key, value)
      }
    }

    layout.configIniFile.outputStream().use { configProps.store(it, defaults.configComment) }
    val devProps = DevPropertiesRenderer.toProperties(context)
    layout.devPropertiesFile.outputStream().use { devProps.store(it, defaults.devComment) }
    val bundlesInfo = BundlesInfoRenderer.toText(plan)
    layout.bundlesInfoFile.writeText(bundlesInfo)
  }
}
