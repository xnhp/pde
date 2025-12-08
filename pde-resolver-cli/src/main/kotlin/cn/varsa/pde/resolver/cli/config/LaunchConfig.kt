package cn.varsa.pde.resolver.cli.config

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path
import kotlin.io.path.inputStream

data class LaunchConfig(
  val product: String? = null,
  val application: String? = null,
  val productFiles: List<String> = emptyList(),
  val startupLevelsFile: String? = null,
  val targetFile: String? = null,
  val inheritTargetArgs: Boolean = true,
  val whitelistFile: String? = null,
  val splash: String? = null,
  val dataDir: String? = null,
  val configDir: String? = null,
  val workDir: String? = null,
  val cleanRuntime: Boolean = false,
  val targetModules: List<String> = emptyList(),
  val workspaceModules: List<WorkspaceModule> = emptyList(),
  @JsonAlias("vmArgs")
  val additionalVmArgs: List<String> = emptyList(),
  val programArgs: List<String> = emptyList(),
  val profilePath: String? = null,
  val startupLevels: Map<String, Int> = emptyMap(),
  val whitelist: List<String> = listOf(
    "org.eclipse.jdt.annotation",
    "org.eclipse.io",
    "org.eclipse.swt"
  )
)

data class WorkspaceModule(
  val path: String,
  val classes: List<String>? = null
)

data class LaunchConfigContext(
  val file: Path,
  val baseDir: Path,
  val config: LaunchConfig
)

object LaunchConfigLoader {
  private val mapper = ObjectMapper(YAMLFactory())
    .registerModule(KotlinModule.Builder().build())

  fun load(path: Path): LaunchConfigContext {
    val normalized = path.toAbsolutePath().normalize()
    val config: LaunchConfig = normalized.inputStream().use { mapper.readValue(it) }
    val base = normalized.parent ?: normalized
    return LaunchConfigContext(file = normalized, baseDir = base, config = config)
  }
}
