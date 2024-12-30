package cn.varsa.idea.pde.partial.plugin.provider

import java.io.*
import java.util.zip.*
import javax.xml.stream.*


fun findProfileFile(dir: File) = dir.takeIf { it.exists() }
  // find suitable <numbers>.profile.gz in there and read it
  ?.listFiles { file -> file.isFile && file.name.let { it.endsWith(".profile.gz") || it.endsWith(".profile") } }
  ?.maxByOrNull { it.name.substringBefore('.').toLongOrNull() ?: Long.MIN_VALUE }

fun mapProfileFile(
  profileFile: File,
  pluginsDirectory: File,
  featureDirectory: File,
  processBundle: (File) -> Unit,
  processFeature: (File) -> Unit
) {
  val mapProfileXml: (InputStream) -> Unit = {
    mapProfileXml(it, pluginsDirectory, featureDirectory, processBundle, processFeature)
  }

  profileFile.inputStream().use { fileInputStream ->
    if (profileFile.name.endsWith(".profile.gz")) GZIPInputStream(fileInputStream).use(mapProfileXml)
    else mapProfileXml(fileInputStream)
  }
}

fun getBundlePoolPath(
  profileFile: File
) : String? {
  profileFile.inputStream().use { fileInputStream ->
    val inputStream: InputStream = if (profileFile.name.endsWith(".profile.gz")) {
      GZIPInputStream(fileInputStream)
    } else {
      fileInputStream
    }
    return getP2CacheValueFromStream(inputStream)
  }
}

private fun getP2CacheValueFromStream(inputStream: InputStream): String? {
  val reader = XMLInputFactory.newInstance().createXMLStreamReader(inputStream)
  try {
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "property") {
        val name = reader.getAttributeValue("", "name")
        if (name == "org.eclipse.equinox.p2.cache") {
          return reader.getAttributeValue("", "value")
        }
      }
    }
  } finally {
    reader.close()
  }
  return null
}

private fun mapProfileXml(
  it: InputStream,
  pluginsDirectory: File,
  featureDirectory: File,
  processBundle: (File) -> Unit,
  processFeature: (File) -> Unit
) {
  val reader = XMLInputFactory.newInstance().createXMLStreamReader(it)
  try {
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "artifact") {
        when (reader.getAttributeValue("", "classifier")) {
          "osgi.bundle" -> {
            val id = reader.getAttributeValue("", "id") ?: continue
            val version = reader.getAttributeValue("", "version") ?: continue

            processBundle(File(pluginsDirectory, "${id}_$version.jar"))
            processBundle(File(pluginsDirectory, "${id}_$version"))
          }

          "org.eclipse.update.feature" -> {
            val id = reader.getAttributeValue("", "id") ?: continue
            val version = reader.getAttributeValue("", "version") ?: continue

            processFeature(File(featureDirectory, "${id}_$version.jar"))
            processFeature(File(featureDirectory, "${id}_$version"))
          }
        }
      }
    }
  } finally {
    reader.close()
  }
}
