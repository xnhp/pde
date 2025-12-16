package cn.varsa.idea.pde.partial.plugin.config

import cn.varsa.idea.pde.partial.common.ProjectLibraryNamePrefix
import cn.varsa.idea.pde.partial.plugin.domain.BundleDefinition
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import org.osgi.framework.Version
import java.util.NavigableMap
import java.util.TreeMap

@Service(Service.Level.PROJECT)
class ProjectLibraryIndexService {
  companion object {
    fun getInstance(project: Project): ProjectLibraryIndexService =
      project.getService(ProjectLibraryIndexService::class.java)
  }

  @Volatile
  private var libsByBsn:
    Map<String, NavigableMap<Version, Library>> = emptyMap()

  fun getIndex(): Map<String, NavigableMap<Version, Library>> = libsByBsn

  fun clear() {
    libsByBsn = emptyMap()
  }

  fun rebuild(project: Project) {
    val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
    val map = HashMap<String, NavigableMap<Version, Library>>()

    table.libraries.forEach { lib ->
      val name = lib.name ?: return@forEach
      if (!name.startsWith(ProjectLibraryNamePrefix)) return@forEach

      val tail = name.substringAfterLast(ProjectLibraryNamePrefix)
      val bsn = tail.substringBeforeLast(BundleDefinition.canonicalNameSeparator)
      val versionText = tail.substringAfterLast(
        BundleDefinition.canonicalNameSeparator
      )

      val ver = try {
        Version.parseVersion(versionText)
      } catch (_: Exception) {
        null
      }
      if (ver != null) {
        map.computeIfAbsent(bsn) { TreeMap() }[ver] = lib
      }
    }

    libsByBsn = map
  }
}

