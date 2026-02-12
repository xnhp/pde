package cn.varsa.idea.pde.partial.plugin.action

import cn.varsa.idea.pde.partial.common.ProjectLibraryNamePrefix
import cn.varsa.idea.pde.partial.plugin.config.PluginTargetIndexService
import cn.varsa.idea.pde.partial.plugin.domain.BundleDefinition
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import org.osgi.framework.Version

class CleanPartialLibrariesAction : AnAction(), DumbAware {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val tp = PluginTargetIndexService.getInstance(project).getIndex()
    val valid = tp.bundlesByBsn().entries.flatMap { (bsn, nav) -> nav.keys.map { bsn to it } }.toHashSet()

    val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
    val model = table.modifiableModel
    val local = LocalFileSystem.getInstance()
    val jarfs = JarFileSystem.getInstance()
    var removed = 0

    table.libraries.forEach { lib ->
      val name = lib.name ?: return@forEach
      if (!name.startsWith(ProjectLibraryNamePrefix)) return@forEach

      val tail = name.substringAfterLast(ProjectLibraryNamePrefix)
      val bsn = tail.substringBeforeLast(BundleDefinition.canonicalNameSeparator)
      val verText = tail.substringAfterLast(BundleDefinition.canonicalNameSeparator)
      val ver = runCatching { Version.parseVersion(verText) }.getOrNull()
      if (ver == null || (bsn to ver) !in valid) {
        model.removeLibrary(lib)
        removed++
        return@forEach
      }

      // Verify roots exist
      val roots = lib.getUrls(com.intellij.openapi.roots.OrderRootType.CLASSES)
      val exists = roots.any { url ->
        val vf = local.findFileByPath(url.removePrefix("file://"))
          ?: jarfs.findFileByPath(url.removePrefix("jar://").removeSuffix("!/"))
        vf != null && vf.exists()
      }
      if (!exists) {
        model.removeLibrary(lib)
        removed++
      }
    }

    model.commit()

    val group = NotificationGroupManager.getInstance().getNotificationGroup("PDE.Tools.Information")
    val msg = if (removed > 0) "Removed $removed stale PDE libraries" else "No stale PDE libraries found"
    group.createNotification(EclipsePDEPartialBundles.message("config.displayName"), msg, NotificationType.INFORMATION)
      .notify(project)
  }
}
