package cn.varsa.pde.resolver.cli.config

import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream
import java.nio.file.Path

data class TargetLaunchArgs(
  val vmArgs: List<String>,
  val programArgs: List<String>
)

object TargetFileParser {
  fun parse(path: Path): TargetLaunchArgs {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isIgnoringComments = true
    factory.isIgnoringElementContentWhitespace = true
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)

    val document = path.inputStream().use { stream ->
      val builder = factory.newDocumentBuilder()
      builder.parse(stream).apply { documentElement.normalize() }
    }

    val vmArgs = extractArgs(document, "vmArgs")
    val programArgs = extractArgs(document, "programArgs")
    return TargetLaunchArgs(vmArgs = vmArgs, programArgs = programArgs)
  }

  private fun extractArgs(doc: Document, tag: String): List<String> {
    val nodes = doc.getElementsByTagName(tag)
    if (nodes.length == 0) return emptyList()
    val text = nodes.item(0)?.textContent ?: return emptyList()
    return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
  }
}
