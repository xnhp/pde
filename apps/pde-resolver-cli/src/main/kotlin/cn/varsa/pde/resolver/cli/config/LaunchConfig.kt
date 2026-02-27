package cn.varsa.pde.resolver.cli.config

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.inputStream

@JsonIgnoreProperties(ignoreUnknown = true)
data class LaunchConfig(
  val issueId: String? = null,
  val branch: String? = null,
  val baseReposPath: String? = null,
  val product: String? = null,
  val application: String? = null,
  val productFiles: List<String> = emptyList(),
  val startupLevelsFile: String? = null,
  val target: TargetConfig? = null,
  val targetFile: String? = null,
  val inheritTargetArgs: Boolean = true,
  val whitelistFile: String? = null,
  val splash: String? = null,
  val env: Map<String, String> = emptyMap(),
  val dataDir: String? = null,
  val configDir: String? = null,
  val workDir: String? = null,
  val cleanRuntime: Boolean = false,
  val targetModules: List<String> = emptyList(),
  val bundlesPerRepo: List<RepoBundles> = emptyList(),
  val nonPdeBundles: List<String> = emptyList(),
  val launches: List<LaunchEntry> = emptyList(),
  val tests: List<TestEntry> = emptyList(),
  @JsonAlias("vmArgs")
  val additionalVmArgs: List<String> = emptyList(),
  val programArgs: List<String> = emptyList(),
  val profilePath: String? = null,
  @JsonAlias("formatter-config-path")
  val formatterConfigPath: String? = null,
  val startupLevels: Map<String, Int> = emptyMap(),
  val whitelist: List<String> = listOf(
    "org.eclipse.jdt.annotation",
    "org.eclipse.io",
    "org.eclipse.swt"
  )
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TargetConfig(
  val definition: String? = null,
  @JsonAlias("profile-id")
  val profileId: String? = null,
  @JsonAlias("p2-path")
  val p2Path: String? = null,
  val install: String? = null,
  @JsonAlias("bundle-pool")
  val bundlePool: String? = null,
  val installer: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RepoBundles(
  val repo: String,
  @JsonDeserialize(contentUsing = BundleRefDeserializer::class)
  val bundles: List<BundleRef> = emptyList(),
  val nonPdeBundles: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BundleRef(
  val name: String,
  val classes: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LaunchEntry(
  val name: String,
  val product: String? = null,
  val application: String? = null,
  val splash: String? = null,
  val debug: Boolean = false,
  val env: Map<String, String> = emptyMap(),
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
  val runner: String? = null,
  val debug: Boolean = false,
  val env: Map<String, String> = emptyMap(),
  val programArgs: List<String> = emptyList(),
  val vmArgs: List<String> = emptyList()
)

data class LaunchConfigContext(
  val file: Path,
  val baseDir: Path,
  val config: LaunchConfig,
  val workingDir: Path,
  val jvmDebug: Boolean = false,
  val jvmDebugRequiresPdeTestApp: Boolean = false
)

object LaunchConfigLoader {
  private val mapper = ObjectMapper(YAMLFactory())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .registerModule(KotlinModule.Builder().build())

  fun load(path: Path, workingDir: Path = Paths.get("").toAbsolutePath()): LaunchConfigContext {
    val normalized = path.toAbsolutePath().normalize()
    val mergedNode = loadMergedNode(normalized, mutableSetOf())
    val config: LaunchConfig = mapper.treeToValue(mergedNode, LaunchConfig::class.java)
    val base = normalized.parent ?: normalized
    val resolvedWorkingDir = workingDir.toAbsolutePath().normalize()
    return LaunchConfigContext(file = normalized, baseDir = base, config = config, workingDir = resolvedWorkingDir)
  }

  private fun loadMergedNode(path: Path, visited: MutableSet<Path>): ObjectNode {
    val normalized = path.toAbsolutePath().normalize()
    require(visited.add(normalized)) { "Include cycle detected at ${normalized.toAbsolutePath()}" }
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
        key == "bundlesPerRepo" -> mergeArrayConcat(baseValue as? ArrayNode, overrideValue as? ArrayNode)
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

private class BundleRefDeserializer : JsonDeserializer<BundleRef>() {
  override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): BundleRef {
    return when (parser.currentToken) {
      JsonToken.VALUE_STRING -> BundleRef(name = parser.valueAsString)
      JsonToken.START_OBJECT -> parser.codec.readValue(parser, BundleRef::class.java)
      else -> throw ctxt.reportInputMismatch(BundleRef::class.java, "Expected string or object for bundle entry")
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
