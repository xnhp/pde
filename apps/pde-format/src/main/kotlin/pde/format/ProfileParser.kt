package pde.format

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

object ProfileParser {
    fun loadOptions(profilePath: Path): MutableMap<String, String> {
        if (!Files.exists(profilePath)) {
            error("Profile not found: ${profilePath.toAbsolutePath()}")
        }

        return if (profilePath.toString().endsWith(".properties") || profilePath.toString().endsWith(".prefs")) {
            parseProperties(profilePath)
        } else {
            parseXml(profilePath)
        }
    }

    private fun parseProperties(profilePath: Path): MutableMap<String, String> {
        val properties = Properties()
        Files.newInputStream(profilePath).use { input ->
            properties.load(input)
        }
        val map = mutableMapOf<String, String>()
        for (entry in properties.entries) {
            map[entry.key.toString()] = entry.value.toString()
        }
        return map
    }

    private fun parseXml(profilePath: Path): MutableMap<String, String> {
        Files.newInputStream(profilePath).use { input ->
            val doc = document(input)
            val settings = doc.getElementsByTagName("setting")
            val map = mutableMapOf<String, String>()
            for (i in 0 until settings.length) {
                val node = settings.item(i)
                val attributes = node.attributes
                val id = attributes?.getNamedItem("id")?.nodeValue
                val value = attributes?.getNamedItem("value")?.nodeValue
                if (!id.isNullOrBlank() && value != null) {
                    map[id] = value
                }
            }
            return map
        }
    }

    private fun document(input: InputStream) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
}
