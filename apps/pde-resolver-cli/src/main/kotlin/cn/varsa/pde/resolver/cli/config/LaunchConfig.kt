package cn.varsa.pde.resolver.cli.config

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.inputStream

@JsonIgnoreProperties(ignoreUnknown = true)
data class LaunchConfig(
  val issueId: String? = null,
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
  val bundlesPerRepo: List<RepoBundles> = emptyList(),
  @JsonDeserialize(contentUsing = WorkspaceModuleDeserializer::class)
  val extraWorkspaceModules: List<WorkspaceModule> = emptyList(),
  val nonPdeBundles: List<String> = emptyList(),
  val launches: List<LaunchEntry> = emptyList(),
  val tests: List<TestEntry> = emptyList(),
  @JsonAlias("vmArgs")
  val additionalVmArgs: List<String> = emptyList(),
  val programArgs: List<String> = emptyList(),
  val debugTests: Boolean = false,
  val profilePath: String? = null,
  val startupLevels: Map<String, Int> = emptyMap(),
  val whitelist: List<String> = listOf(
    "org.eclipse.jdt.annotation",
    "org.eclipse.io",
    "org.eclipse.swt"
  )
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WorkspaceModule(
  val path: String,
  val classes: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RepoBundles(
  val repo: String,
  val bundles: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LaunchEntry(
  val name: String,
  val product: String? = null,
  val application: String? = null,
  val splash: String? = null,
  val programArgs: List<String> = emptyList(),
  val vmArgs: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TestEntry(
  val name: String? = null,
  @JsonAlias("testpluginname")
  val testPluginName: String? = null,
  @JsonAlias("classname")
  val className: String? = null,
  val programArgs: List<String> = emptyList(),
  val vmArgs: List<String> = emptyList()
)

data class LaunchConfigContext(
  val file: Path,
  val baseDir: Path,
  val config: LaunchConfig
)

object LaunchConfigLoader {
  private val mapper = ObjectMapper(YAMLFactory())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .registerModule(KotlinModule.Builder().build())

  fun load(path: Path, workingDir: Path = Paths.get("").toAbsolutePath()): LaunchConfigContext {
    val normalized = path.toAbsolutePath().normalize()
    val config: LaunchConfig = normalized.inputStream().use { mapper.readValue(it) }
    val resolvedConfig = resolveWorkspaceModules(config, workingDir)
    val base = normalized.parent ?: normalized
    return LaunchConfigContext(file = normalized, baseDir = base, config = resolvedConfig)
  }

  private fun resolveWorkspaceModules(config: LaunchConfig, workingDir: Path): LaunchConfig {
    val baseModules = when {
      config.workspaceModules.isNotEmpty() -> config.workspaceModules
      config.bundlesPerRepo.isNotEmpty() -> buildWorkspaceModulesFromBundles(config, workingDir)
      else -> emptyList()
    }

    val extras = buildExtraWorkspaceModules(config, workingDir)
    val merged = (baseModules + extras)
      .distinctBy { Paths.get(it.path).normalize().toString() }

    return if (merged == config.workspaceModules) config else config.copy(workspaceModules = merged)
  }

  private fun buildWorkspaceModulesFromBundles(config: LaunchConfig, workingDir: Path): List<WorkspaceModule> {
    val skipBundles = config.nonPdeBundles.toSet()
    val seen = linkedSetOf<String>()
    return config.bundlesPerRepo.flatMap { repoEntry ->
      val repoPath = Paths.get(repoEntry.repo)
      val repoBase = if (repoPath.isAbsolute) repoPath else workingDir.resolve(repoEntry.repo)
      repoEntry.bundles.mapNotNull { bundle ->
        if (skipBundles.contains(bundle)) return@mapNotNull null
        val modulePath = repoBase.resolve(bundle).normalize().toString()
        if (seen.add(modulePath)) WorkspaceModule(path = modulePath) else null
      }
    }
  }

  private fun buildExtraWorkspaceModules(config: LaunchConfig, workingDir: Path): List<WorkspaceModule> {
    if (config.extraWorkspaceModules.isEmpty()) return emptyList()
    return config.extraWorkspaceModules.map { module ->
      val path = Paths.get(module.path)
      val resolved = if (path.isAbsolute) path else workingDir.resolve(module.path)
      module.copy(path = resolved.normalize().toString())
    }
  }
}

private class WorkspaceModuleDeserializer : JsonDeserializer<WorkspaceModule>() {
  override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): WorkspaceModule {
    return when (parser.currentToken) {
      JsonToken.VALUE_STRING -> WorkspaceModule(path = parser.valueAsString)
      JsonToken.START_OBJECT -> parser.codec.readValue(parser, WorkspaceModule::class.java)
      else -> throw ctxt.reportInputMismatch(WorkspaceModule::class.java, "Expected string or object for workspace module")
    }
  }
}

internal val DEFAULT_STARTUP_LEVELS = mapOf(
  "org.eclipse.osgi" to 1,
  "org.eclipse.equinox.simpleconfigurator" to 1,
  "org.eclipse.equinox.ds" to 1,
  "org.eclipse.m2e.logback.configuration" to 4,
  "org.apache.felix.gogo.runtime" to 4,
  "org.eclipse.equinox.event" to 2,
  "org.eclipse.core.runtime" to 4,
  "org.apache.felix.scr" to 2,
  "org.apache.felix.gogo.command" to 4,
  "org.apache.felix.gogo.shell" to 4,
  "org.eclipse.equinox.p2.reconciler.dropins" to 4,
  "org.eclipse.equinox.console" to 4,
  "org.eclipse.equinox.common" to 2
)
