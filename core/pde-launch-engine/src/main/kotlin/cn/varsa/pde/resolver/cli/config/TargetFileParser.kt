package cn.varsa.pde.resolver.cli.config

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.net.URI
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream

data class TargetLaunchArgs(
  val vmArgs: List<String>,
  val programArgs: List<String>
)

data class TargetDefinitionContents(
  val repositories: List<URI>,
  val installUnits: List<InstallUnitRef>,
  val includeConfigurePhase: Boolean
)

data class InstallUnitRef(
  val id: String,
  val version: String?
)

object TargetFileParser {
  fun parse(path: Path): TargetLaunchArgs {
    val document = parseDocument(path)
    val vmArgs = extractArgs(document, "vmArgs")
    val programArgs = extractArgs(document, "programArgs")
    return TargetLaunchArgs(vmArgs = vmArgs, programArgs = programArgs)
  }

  fun parseContents(path: Path): TargetDefinitionContents {
    val document = parseDocument(path)
    val repositories = extractRepositoryLocations(document)
    val units = extractInstallUnits(document)
    val includeConfigurePhase = extractIncludeConfigurePhase(document)
    return TargetDefinitionContents(
      repositories = repositories,
      installUnits = units,
      includeConfigurePhase = includeConfigurePhase
    )
  }

  private fun parseDocument(path: Path): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isIgnoringComments = true
    factory.isIgnoringElementContentWhitespace = true
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)

    return path.inputStream().use { stream ->
      val builder = factory.newDocumentBuilder()
      builder.parse(stream).apply { documentElement.normalize() }
    }
  }

  private fun extractArgs(doc: Document, tag: String): List<String> {
    val nodes = doc.getElementsByTagName(tag)
    if (nodes.length == 0) return emptyList()
    val text = nodes.item(0)?.textContent ?: return emptyList()
    return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
  }

  private fun extractRepositoryLocations(doc: Document): List<URI> {
    val repoNodes = doc.getElementsByTagName("repository")
    if (repoNodes.length == 0) return emptyList()
    val results = mutableListOf<URI>()
    for (index in 0 until repoNodes.length) {
      val node = repoNodes.item(index) as? Element ?: continue
      val location = node.getAttribute("location")?.trim().orEmpty()
      if (location.isBlank()) continue
      runCatching { URI(location) }
        .onSuccess { results += it }
    }
    return results
  }

  private fun extractInstallUnits(doc: Document): List<InstallUnitRef> {
    val unitNodes = doc.getElementsByTagName("unit")
    if (unitNodes.length == 0) return emptyList()
    val results = mutableListOf<InstallUnitRef>()
    for (index in 0 until unitNodes.length) {
      val node = unitNodes.item(index) as? Element ?: continue
      val id = node.getAttribute("id")?.trim().orEmpty()
      if (id.isBlank()) continue
      val version = node.getAttribute("version")?.trim()?.takeIf { it.isNotBlank() }
      results += InstallUnitRef(id = id, version = version)
    }
    return results
  }

  private fun extractIncludeConfigurePhase(doc: Document): Boolean {
    val locationNodes = doc.getElementsByTagName("location")
    var includeConfigurePhase: Boolean? = null
    for (index in 0 until locationNodes.length) {
      val node = locationNodes.item(index) as? Element ?: continue
      if (!node.getAttribute("type").equals("InstallableUnit", ignoreCase = true)) continue
      val includeAttr = node.getAttribute("includeConfigurePhase")?.trim().orEmpty()
      if (includeAttr.isNotBlank()) {
        includeConfigurePhase = includeAttr.equals("true", ignoreCase = true)
      }
    }
    return includeConfigurePhase ?: true
  }
}
