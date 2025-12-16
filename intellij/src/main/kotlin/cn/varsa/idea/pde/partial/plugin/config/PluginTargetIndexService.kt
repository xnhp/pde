package cn.varsa.idea.pde.partial.plugin.config

import cn.varsa.pde.resolver.index.TargetPlatformCache
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.idea.pde.partial.plugin.helper.PdeNotifier
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.osgi.framework.Version
import org.osgi.framework.VersionRange
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class PluginTargetIndexService(private val project: Project) {
  companion object {
    @JvmStatic
    fun getInstance(project: Project): PluginTargetIndexService =
      project.getService(PluginTargetIndexService::class.java)
  }

  @Volatile
  private var index: TargetPlatformIndex? = null

  fun getIndex(): TargetPlatformIndex {
    val current = index
    if (current != null) return current
    val roots = TargetDefinitionService.getInstance(project).locations
      .mapNotNull { it.location.takeIf(String::isNotBlank) }
      .map { Paths.get(it) }
    val built = TargetPlatformCache.buildWithCache(roots, null)
    index = built
    return built
  }

  fun invalidate() {
    index = null
    val roots = TargetDefinitionService.getInstance(project).locations.size
    PdeNotifier.notification(
      "Target Platform",
      "Target platform index cache invalidated. Roots: $roots (will rebuild on next resolve)"
    ).notify(project)
  }

  fun getBundlesByBSN(bsn: String) = getIndex().bundlesByBsn()[bsn]
  fun getBundlesByBSN(bsn: String, version: Version) = getBundlesByBSN(bsn)?.get(version)
  fun getBundlesByBSN(bsn: String, range: VersionRange) =
    getBundlesByBSN(bsn)?.descendingMap()?.entries?.firstOrNull { range.includes(it.key) }?.value

  fun getBundleByBCN(bcn: String) =
    bcn.substringBeforeLast('@').let { bsn ->
      val verText = bcn.substringAfterLast('@')
      val ver = try { Version.parseVersion(verText) } catch (_: Exception) { null }
      if (ver != null) getBundlesByBSN(bsn, ver) else null
    }

  fun findBundleByPath(presentableUrl: String) = getIndex().bundlesByBsn().values
    .asSequence()
    .flatMap { it.values.asSequence() }
    .firstOrNull { rb ->
      val path = rb.location.toAbsolutePath().toString()
      // JAR-shaped bundle: exact jar match
      if (!rb.isDirectory && (presentableUrl.contains(path))) return@firstOrNull true
      // Dir-shaped bundle: file under directory
      rb.isDirectory && presentableUrl.startsWith(path)
    }
}
