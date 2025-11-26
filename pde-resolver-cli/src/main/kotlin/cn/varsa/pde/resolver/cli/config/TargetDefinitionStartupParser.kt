package cn.varsa.pde.resolver.cli.config

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream

object TargetDefinitionStartupParser {
  private const val COMPONENT_NAME = "TcRacTargetDefinitions"

  fun parse(path: Path): Map<String, Int>? {
    if (!Files.exists(path)) return null
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
