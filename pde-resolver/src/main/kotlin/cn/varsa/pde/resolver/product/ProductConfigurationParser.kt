package cn.varsa.pde.resolver.product

import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream

object ProductConfigurationParser {
  fun parseAutoStartPlugins(path: Path, targetProductId: String?): Map<String, Int>? {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = false
      isIgnoringComments = true
      isIgnoringElementContentWhitespace = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeature("http://xml.org/sax/features/external-general-entities", false)
      setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val document = path.inputStream().use { stream ->
      factory.newDocumentBuilder().parse(stream).apply { documentElement.normalize() }
    }
    val root = document.documentElement ?: return null
    val productId = root.getAttribute("id")
    if (!productId.isNullOrBlank() && targetProductId != null && targetProductId != productId) return null

    val configs = root.getElementsByTagName("configurations")
    if (configs.length == 0) return emptyMap()
    val plugins = mutableMapOf<String, Int>()
    for (i in 0 until configs.length) {
      val config = configs.item(i) as? Element ?: continue
      val pluginNodes = config.getElementsByTagName("plugin")
      for (j in 0 until pluginNodes.length) {
        val plugin = pluginNodes.item(j) as? Element ?: continue
        val autoStart = plugin.getAttribute("autoStart")
        val idAttr = plugin.getAttribute("id")
        if (!idAttr.isNullOrBlank() && autoStart.equals("true", ignoreCase = true)) {
          val level = plugin.getAttribute("startLevel").toIntOrNull()?.takeIf { it > 0 } ?: 4
          plugins[idAttr] = level
        }
      }
    }
    return plugins.takeIf { it.isNotEmpty() }
  }
}
