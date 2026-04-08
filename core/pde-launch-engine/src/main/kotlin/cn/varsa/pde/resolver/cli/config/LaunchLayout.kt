package cn.varsa.pde.resolver.cli.config

import cn.varsa.pde.resolver.launch.RuntimeLayoutWriter
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import java.util.logging.Logger

data class LaunchLayout(
  val dataDir: Path,
  val configDir: Path,
  val workDir: Path
) {
  val configIniFile: Path get() = configDir.resolve("config.ini")
  val devPropertiesFile: Path get() = configDir.resolve("dev.properties")
  val bundlesInfoFile: Path get() = configDir.resolve("org.eclipse.equinox.simpleconfigurator").resolve("bundles.info")

  fun asRuntimeLayout(): RuntimeLayoutWriter.LayoutPaths =
    RuntimeLayoutWriter.LayoutPaths(configDir, configIniFile, devPropertiesFile, bundlesInfoFile)
}

data class LaunchLayoutResolution(
  val layout: LaunchLayout,
  val cleanup: () -> Unit = {}
)

object LaunchLayoutResolver {
  private val logger: Logger = Logger.getLogger(LaunchLayoutResolver::class.java.name)

  fun resolve(context: LaunchConfigContext): LaunchLayoutResolution {
    val base = context.baseDir
    val tempPaths = mutableListOf<Path>()
    val tempNotices = mutableListOf<String>()

    val data = context.runtime.dataDir?.let { base.resolve(it) }
      ?: createTempDirectory("pde-data-").also {
        tempPaths.add(it)
        tempNotices += "dataDir=$it"
      }
    val config = context.runtime.configDir?.let { base.resolve(it) }
      ?: createTempDirectory("pde-config-").also {
        tempPaths.add(it)
        tempNotices += "configDir=$it"
      }
    val work = context.runtime.workDir?.let { base.resolve(it) }
      ?: createTempDirectory("pde-work-").also {
        tempPaths.add(it)
        tempNotices += "workDir=$it"
      }

    fun ensure(path: Path) { if (!path.exists()) path.createDirectories() }
    ensure(config)
    ensure(work)
    ensure(data)
    val layout = LaunchLayout(dataDir = data, configDir = config, workDir = work)
    ensure(layout.bundlesInfoFile.parent)

    var cleaned = false
    val cleanup = {
      if (!cleaned) {
        cleaned = true
        tempPaths.forEach { path -> runCatching { path.toFile().deleteRecursively() }.onFailure { /* ignore */ } }
      }
    }

    if (tempPaths.isNotEmpty()) {
      Runtime.getRuntime().addShutdownHook(Thread { cleanup.invoke() })
      logger.info("Launch config omitted paths; using temporary ${tempNotices.joinToString(", ")}.")
    }

    return LaunchLayoutResolution(layout = layout, cleanup = cleanup)
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
