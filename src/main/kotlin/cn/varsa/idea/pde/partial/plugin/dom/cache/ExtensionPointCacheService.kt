package cn.varsa.idea.pde.partial.plugin.dom.cache

import cn.varsa.idea.pde.partial.common.support.*
import cn.varsa.idea.pde.partial.plugin.cache.*
import cn.varsa.idea.pde.partial.plugin.config.*
import cn.varsa.idea.pde.partial.plugin.dom.domain.*
import cn.varsa.idea.pde.partial.plugin.dom.indexes.*
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.*
import com.intellij.openapi.project.*
import com.intellij.openapi.vfs.*
import com.intellij.psi.util.*
import com.jetbrains.rd.util.*
import java.io.*

@Service(Service.Level.PROJECT)
class ExtensionPointCacheService(private val project: Project) {
  private val cacheService by lazy { BundleManifestCacheService.getInstance(project) }
  private val tpService by lazy { PluginTargetIndexService.getInstance(project) }
  private val cachedValuesManager by lazy { CachedValuesManager.getManager(project) }

  private val caches = ConcurrentHashMap<String, CachedValue<ExtensionPointDefinition?>>()
  private val lastIndexed = ConcurrentHashMap<String, ExtensionPointDefinition>()

  companion object {
    fun getInstance(project: Project): ExtensionPointCacheService =
      project.getService(ExtensionPointCacheService::class.java)

    fun resolveExtensionPoint(
      schemaFile: VirtualFile, stream: InputStream = schemaFile.inputStream
    ): ExtensionPointDefinition? = try {
      ExtensionPointDefinition(schemaFile, stream)
    } catch (e: Exception) {
      thisLogger().warn("EXSD file not valid: $schemaFile : $e", e)
      null
    }
  }

  fun clearCache() {
    caches.clear()
    lastIndexed.clear()
  }

  fun loadExtensionPoint(schemaLocation: String): ExtensionPointDefinition? {
    val urlFragments = schemaLocation.substringAfter(ExtensionPointDefinition.schemaProtocol).split('/')

    val entry = urlFragments.subList(1, urlFragments.size).joinToString("/")
    val rb = tpService.getBundlesByBSN(urlFragments[0])?.values?.firstOrNull()
    if (rb != null) {
      val root = rootOf(rb)
      val loaded = if (root != null) loadExtensionPoint(root, entry) else null
      if (loaded != null) return loaded
    }
    return project.allPDEModules()
      .firstOrNull { cacheService.getManifest(it)?.bundleSymbolicName?.key == urlFragments[0] }?.moduleRootManager?.contentRoots?.firstNotNullOfOrNull {
        loadExtensionPoint(it, entry)
      }
  }

  private fun loadExtensionPoint(root: VirtualFile, schema: String): ExtensionPointDefinition? =
    root.validFileOrRequestResolve()?.findFileByRelativePath(schema)?.let(this::getExtensionPoint)

  private fun rootOf(rb: cn.varsa.pde.resolver.index.ResolvedBundle): VirtualFile? {
    val lfs = LocalFileSystem.getInstance()
    val jarfs = JarFileSystem.getInstance()
    return if (rb.isDirectory) lfs.findFileByNioFile(rb.location)
    else lfs.findFileByNioFile(rb.location)?.let { jarfs.getJarRootForLocalFile(it) }
  }

  fun getExtensionPoint(schemaFile: VirtualFile): ExtensionPointDefinition? = readCompute {
    schemaFile.validFileOrRequestResolve()?.let { file ->
      DumbService.isDumb(project).runFalse { ExtensionPointIndex.readEPDefinition(project, file) }
        ?.also { lastIndexed[file.presentableUrl] = it } ?: lastIndexed[file.presentableUrl] ?: caches.computeIfAbsent(
        file.presentableUrl
      ) {
        cachedValuesManager.createCachedValue {
          CachedValueProvider.Result.create(resolveExtensionPoint(file), file)
        }
      }.value
    }
  }

  private fun VirtualFile.validFileOrRequestResolve() =
    validFileOrRequestResolve(project) { "${it.presentableUrl} file not valid when build extension point cache, maybe it was delete after load, please check, restart application or re-resolve workspace" }
}
