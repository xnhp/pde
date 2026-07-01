package cn.varsa.pde.resolver.cli.config

import cn.varsa.cli.core.config.YamlConfig
import cn.varsa.cli.core.config.YamlSchema
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Logger
import kotlin.io.path.inputStream

data class LaunchConfig(
  val target: TargetConfig? = null,
  val bundles: List<WorkspaceBundleConfig> = emptyList(),
  val launches: List<LaunchEntry> = emptyList(),
  val tests: List<TestEntry> = emptyList()
)

data class TargetConfig(
  val definition: String? = null,
  val profileId: String? = null,
  val p2Path: String? = null,
  val install: String? = null,
  val apiBaselineRoot: String? = null,
  val bundlePool: String? = null,
  val installer: String? = null,
  val eclipseRuntimeCache: String? = null,
  val p2Repositories: List<String>? = null,
  val extraBundles: List<String>? = null,
  val pinnedVersions: Map<String, String>? = null,
  val mirror: TargetMirrorConfig? = null
)

data class TargetMirrorConfig(
  val destination: String? = null,
  val writeMode: String? = null,
  val includeMetadata: Boolean? = null,
  val includeArtifacts: Boolean? = null
)

data class WorkspaceBundleConfig(
  val path: String,
  val classRoots: List<String>? = null,
  val compilerArgs: List<String> = emptyList()
)

data class LaunchEntry(
  val name: String,
  val product: String? = null,
  val application: String? = null,
  val splash: String? = null,
  val programArgs: List<String> = emptyList(),
  val vmArgs: List<String> = emptyList(),
  val dataDir: String? = null,
  val configDir: String? = null,
  val workDir: String? = null,
  val env: Map<String, String> = emptyMap(),
  val envFile: String? = null
)

data class TestEntry(
  val name: String? = null,
  val testPluginName: String? = null,
  val className: String? = null,
  val runner: String? = null,
  val debug: Boolean = false,
  val programArgs: List<String> = emptyList(),
  val vmArgs: List<String> = emptyList(),
  val dataDir: String? = null,
  val configDir: String? = null,
  val workDir: String? = null
)

data class LaunchRuntime(
  val product: String? = null,
  val application: String? = null,
  val splash: String? = null,
  val programArgs: List<String> = emptyList(),
  val vmArgs: List<String> = emptyList(),
  val dataDir: String? = null,
  val configDir: String? = null,
  val workDir: String? = null,
  val env: Map<String, String> = emptyMap()
)

data class LaunchConfigContext(
  val file: Path,
  val baseDir: Path,
  val config: LaunchConfig,
  val workingDir: Path,
  val runtime: LaunchRuntime = LaunchRuntime(),
  val jvmDebug: Boolean = false,
  val jvmDebugRequiresPdeTestApp: Boolean = false,
  val clean: Boolean = false
)

object LaunchConfigLoader {
  private val logger: Logger = Logger.getLogger(LaunchConfigLoader::class.java.name)
  private val mapper = ObjectMapper(YAMLFactory())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .registerModule(KotlinModule.Builder().build())
  private val schema: YamlSchema = LaunchConfigLoader::class.java.classLoader
    .getResourceAsStream("schema/pde.schema.yaml")
    ?.use { YamlConfig.loadSchema(it) }
    ?: error("Missing schema resource: schema/pde.schema.yaml")

  fun load(path: Path, workingDir: Path = Paths.get("").toAbsolutePath()): LaunchConfigContext {
    val normalized = path.toAbsolutePath().normalize()
    val mergedNode = loadMergedNode(normalized, mutableSetOf())
    val mergedContent = mapper.writeValueAsString(mergedNode)
    val config: LaunchConfig = try {
      YamlConfig.decodeValidated(mergedContent, schema, LaunchConfig::class.java)
    } catch (ex: IllegalArgumentException) {
      error("Invalid config ${normalized}:\n${ex.message}")
    }
    val base = normalized.parent ?: normalized
    val resolvedWorkingDir = workingDir.toAbsolutePath().normalize()
    return LaunchConfigContext(file = normalized, baseDir = base, config = config, workingDir = resolvedWorkingDir)
  }

  private fun loadMergedNode(path: Path, visited: MutableSet<Path>): ObjectNode {
    val normalized = path.toAbsolutePath().normalize()
    require(visited.add(normalized)) { "Include cycle detected at ${normalized.toAbsolutePath()}" }
    warnDuplicateKeys(normalized)
    val node = normalized.inputStream().use { mapper.readTree(it) }
    val objectNode = node as? ObjectNode ?: mapper.nodeFactory.objectNode()
    val baseDir = normalized.parent ?: normalized
    val includes = parseIncludes(objectNode, baseDir)

    var merged = mapper.nodeFactory.objectNode()
    for (include in includes) {
      merged = mergeNodes(merged, loadMergedNode(include, visited))
    }
    merged = mergeNodes(merged, objectNode)
    merged.remove("includes")
    visited.remove(normalized)
    return merged
  }

  private fun warnDuplicateKeys(path: Path) {
    val stack = ArrayDeque<MutableSet<String>>()
    path.inputStream().use { input ->
      mapper.factory.createParser(input).use { parser ->
        while (parser.nextToken() != null) {
          when (parser.currentToken()) {
            com.fasterxml.jackson.core.JsonToken.START_OBJECT -> stack.addLast(mutableSetOf())
            com.fasterxml.jackson.core.JsonToken.END_OBJECT -> if (stack.isNotEmpty()) stack.removeLast()
            com.fasterxml.jackson.core.JsonToken.FIELD_NAME -> {
              val key = parser.currentName()
              val keys = stack.lastOrNull()
              if (key != null && keys != null && !keys.add(key)) {
                logger.warning("Duplicate YAML key '$key' in ${path.toAbsolutePath().normalize()}; the later value will override the earlier one.")
              }
            }
            else -> Unit
          }
        }
      }
    }
  }

  private fun parseIncludes(node: ObjectNode, baseDir: Path): List<Path> {
    val includesNode = node.get("includes") ?: return emptyList()
    val raw = when {
      includesNode.isArray -> includesNode.mapNotNull { it.asText(null) }
      includesNode.isTextual -> listOf(includesNode.asText())
      else -> emptyList()
    }
    return raw.map { include ->
      val path = Paths.get(include)
      (if (path.isAbsolute) path else baseDir.resolve(path)).toAbsolutePath().normalize()
    }
  }

  private fun mergeNodes(base: ObjectNode, override: ObjectNode): ObjectNode {
    val merged = base.deepCopy() as ObjectNode
    val fields = override.fields()
    while (fields.hasNext()) {
      val (key, overrideValue) = fields.next()
      val baseValue = merged.get(key)
      val nextValue: JsonNode = when {
        key == "launches" || key == "tests" -> mergeListByName(baseValue as? ArrayNode, overrideValue as? ArrayNode)
        key == "bundles" -> mergeArrayConcat(baseValue as? ArrayNode, overrideValue as? ArrayNode)
        baseValue is ObjectNode && overrideValue is ObjectNode -> mergeObjectNodes(baseValue, overrideValue)
        overrideValue is ArrayNode -> overrideValue.deepCopy() as ArrayNode
        else -> overrideValue.deepCopy()
      }
      merged.set<JsonNode>(key, nextValue)
    }
    return merged
  }

  private fun mergeObjectNodes(base: ObjectNode, override: ObjectNode): ObjectNode {
    val merged = base.deepCopy() as ObjectNode
    val fields = override.fields()
    while (fields.hasNext()) {
      val (key, value) = fields.next()
      merged.set<JsonNode>(key, value.deepCopy())
    }
    return merged
  }

  private fun mergeListByName(base: ArrayNode?, override: ArrayNode?): ArrayNode {
    if (override == null) return (base?.deepCopy() as? ArrayNode) ?: mapper.nodeFactory.arrayNode()
    if (base == null) return override.deepCopy() as ArrayNode

    val merged = mapper.nodeFactory.arrayNode()
    val indexByName = linkedMapOf<String, Int>()

    for (node in base) {
      val name = node.get("name")?.asText(null)
      if (name != null) {
        indexByName[name] = merged.size()
      }
      merged.add(node.deepCopy())
    }

    for (node in override) {
      val name = node.get("name")?.asText(null)
      if (name != null) {
        val index = indexByName[name]
        if (index != null) {
          merged.set(index, node.deepCopy())
          continue
        }
        indexByName[name] = merged.size()
      }
      merged.add(node.deepCopy())
    }

    return merged
  }

  private fun mergeArrayConcat(base: ArrayNode?, override: ArrayNode?): ArrayNode {
    if (override == null) return (base?.deepCopy() as? ArrayNode) ?: mapper.nodeFactory.arrayNode()
    if (base == null) return override.deepCopy() as ArrayNode

    val merged = mapper.nodeFactory.arrayNode()
    for (node in base) {
      merged.add(node.deepCopy())
    }
    for (node in override) {
      merged.add(node.deepCopy())
    }
    return merged
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
