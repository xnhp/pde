package cn.varsa.pde.resolver.cli.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream

object TargetDefinitionStartupParser {
  private const val COMPONENT_NAME = "TcRacTargetDefinitions"
  private val yamlMapper = ObjectMapper(YAMLFactory())
    .registerModule(KotlinModule.Builder().build())

  fun parse(path: Path): Map<String, Int>? {
    if (!Files.exists(path)) return null
    val fileName = path.fileName?.toString().orEmpty().lowercase()
    return if (fileName.endsWith(".xml")) parseLegacyXml(path) else parseYaml(path)
  }

  private fun parseYaml(path: Path): Map<String, Int>? {
    val root = path.inputStream().use { yamlMapper.readTree(it) } ?: return null
    val mapNode = when {
      root.isObject && root.has("startupLevels") -> root.get("startupLevels")
      root.isObject && root.hasOnlyValueNodes() -> root
      else -> null
    } ?: return null
    if (!mapNode.isObject) return null
    val result = linkedMapOf<String, Int>()
    mapNode.fields().forEach { (bsnRaw, valueNode) ->
      val bsn = bsnRaw.trim()
      if (bsn.isEmpty()) return@forEach
      val level = extractLevel(valueNode) ?: return@forEach
      result[bsn] = level
    }
    return result
  }

  private fun extractLevel(node: JsonNode): Int? = when {
    node.isInt || node.isLong || node.isShort -> node.intValue()
    node.isTextual -> node.asText().toIntOrNull()
    else -> null
  }

  private fun JsonNode.hasOnlyValueNodes(): Boolean {
    val iterator = fields()
    while (iterator.hasNext()) {
      val value = iterator.next().value
      if (!value.isValueNode) return false
    }
    return true
  }

  private fun parseLegacyXml(path: Path): Map<String, Int>? {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = false
      isIgnoringComments = true
      isIgnoringElementContentWhitespace = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeature("http://xml.org/sax/features/external-general-entities", false)
      setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val document = path.inputStream().use { stream ->
      factory.newDocumentBuilder().parse(stream).apply { documentElement?.normalize() }
    }
    val components = document.getElementsByTagName("component")
    for (i in 0 until components.length) {
      val component = components.item(i) as? Element ?: continue
      val nameAttr = component.getAttribute("name")
      if (nameAttr != COMPONENT_NAME) continue
      val bundleLevels = component.getElementsByTagName("bundleLevel")
      if (bundleLevels.length == 0) return emptyMap()
      val result = mutableMapOf<String, Int>()
      for (j in 0 until bundleLevels.length) {
        val entry = bundleLevels.item(j) as? Element ?: continue
        val bsn = entry.getAttribute("bundleSymbolicName")
        val level = entry.getAttribute("startupLevel").toIntOrNull()
        if (bsn.isNullOrBlank() || level == null) continue
        result[bsn] = level
      }
      return result
    }
    return null
  }
}
