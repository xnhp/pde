package cn.varsa.pde.resolver.cli.config

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

data class LaunchLayout(
  val dataDir: Path,
  val configDir: Path,
  val workDir: Path
) {
  val configIniFile: Path get() = configDir.resolve("config.ini")
  val devPropertiesFile: Path get() = configDir.resolve("dev.properties")
  val bundlesInfoFile: Path get() = configDir.resolve("org.eclipse.equinox.simpleconfigurator").resolve("bundles.info")
}

object LaunchLayoutResolver {
  fun resolve(context: LaunchConfigContext): LaunchLayout {
    val base = context.baseDir
    val data = context.config.dataDir?.let { base.resolve(it) } ?: base.resolve("runtime-data")
    val config = context.config.configDir?.let { base.resolve(it) } ?: data.resolve("configuration")
    val work = context.config.workDir?.let { base.resolve(it) } ?: base.resolve("run-work")
    fun ensure(path: Path) { if (!path.exists()) path.createDirectories() }
    ensure(config)
    ensure(work)
    ensure(data)
    val layout = LaunchLayout(dataDir = data, configDir = config, workDir = work)
    ensure(layout.bundlesInfoFile.parent)
    return layout
  }
  fun cleanIfRequested(layout: LaunchLayout, clean: Boolean) {
    if (!clean) return
    listOf(layout.configDir, layout.dataDir, layout.workDir).forEach { path ->
      val file = path.toFile()
      if (file.exists()) file.deleteRecursively()
      file.mkdirs()
    }
    layout.bundlesInfoFile.parent.toFile().mkdirs()
  }
}
