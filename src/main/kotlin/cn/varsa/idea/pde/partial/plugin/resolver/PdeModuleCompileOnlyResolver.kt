package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.idea.pde.partial.common.support.*
import cn.varsa.idea.pde.partial.plugin.cache.*
import cn.varsa.idea.pde.partial.plugin.config.*
import cn.varsa.idea.pde.partial.plugin.config.PluginTargetIndexService
import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles.message
import cn.varsa.idea.pde.partial.plugin.openapi.resolver.*
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.openapi.module.*
import com.intellij.openapi.roots.*
import java.util.*

class PdeModuleCompileOnlyResolver : BuildLibraryResolver {
  override val displayName: String = message("resolver.pde.buildCompileOnly")

  override fun resolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val buildPropertiesFile = area.moduleRootManager.contentRoots.firstNotNullOfOrNull {
      it.refresh(false, false)
      it.findChild(BuildProperties)
    }?.also { it.refresh(false, false) } ?: return

    val buildProperties = Properties().apply { buildPropertiesFile.inputStream.use { load(it) } }
    val classPaths = buildProperties.getProperty("jars.extra.classpath")?.splitToSequence(',') ?: return

    val cacheService = BundleManifestCacheService.getInstance(area.project)
    val tpService = PluginTargetIndexService.getInstance(area.project)

    val symbolicName2Module =
      area.project.allPDEModules(area).map { cacheService.getManifest(it)?.bundleSymbolicName?.key to it }
        .filterNot { it.first == null }.associate { it.first!! to it.second }

    val moduleDependency = hashSetOf<Module>()
    val classesRoot = classPaths.mapNotNull { url ->
      val urlFragments = url.split('/')
      if (urlFragments[0] != "platform:") {
        area.moduleRootManager.contentRoots.firstNotNullOfOrNull { it.findFileByRelativePath(url) }
      } else if (urlFragments.size > 2 && urlFragments[1].equalAny("plugin", "fragment", ignoreCase = true)) {
        val bsn = urlFragments[2]
        val rb = tpService.getBundlesByBSN(bsn)?.lastEntry()?.value
        val local = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        val jarfs = com.intellij.openapi.vfs.JarFileSystem.getInstance()
        val root = rb?.let {
          if (it.isDirectory) local.findFileByNioFile(it.location)
          else local.findFileByNioFile(it.location)?.let { jf -> jarfs.getJarRootForLocalFile(jf) }
        }
        if (root != null) {
          if (urlFragments.size == 3) {
            root
          } else {
            val entry = urlFragments.subList(3, urlFragments.size).joinToString("/")
            val child = root.findFileByRelativePath(entry)
            if (child != null) {
              when (child.fileSystem) {
                local -> jarfs.getJarRootForLocalFile(child) ?: child
                jarfs -> {
                  val ext = child.extension?.lowercase()
                  if (ext == "jar" || ext == "aar" || ext == "war") {
                    // Extract nested jar entry into project out/tmp_lib and return its jar root
                    extractNestedJar(area.project, root, child) ?: child
                  } else child
                }
                else -> child
              }
            } else null
          }
        } else symbolicName2Module[bsn]?.let { module ->
          if (urlFragments.size == 3) {
            moduleDependency += module
            null
          } else {
            val entry = urlFragments.subList(3, urlFragments.size).joinToString("/")
            module.moduleRootManager.contentRoots.firstNotNullOfOrNull { it.findFileByRelativePath(entry) }
          }
        }
      } else {
        null
      }
    }

    area.updateModel { model ->
      val libraryTableModel = model.moduleLibraryTable.modifiableModel

      applicationInvokeAndWait {
        val library = libraryTableModel.getLibraryByName(ModuleCompileOnlyLibraryName) ?: writeCompute {
          libraryTableModel.createLibrary(ModuleCompileOnlyLibraryName)
        }

        model.findLibraryOrderEntry(library)?.apply {
          scope = DependencyScope.COMPILE
          isExported = false
        }

        val libraryModel = library.modifiableModel

        libraryModel.getUrls(OrderRootType.CLASSES).forEach { libraryModel.removeRoot(it, OrderRootType.CLASSES) }
        classesRoot.map { it.protocolUrl }.forEach { libraryModel.addRoot(it, OrderRootType.CLASSES) }

        writeRun {
          libraryModel.commit()
          libraryTableModel.commit()
        }
      }

      applicationInvokeAndWait {
        moduleDependency.forEach { model.findModuleOrderEntry(it) ?: model.addModuleOrderEntry(it) }
      }
    }
  }

  private fun extractNestedJar(
    project: com.intellij.openapi.project.Project,
    bundleRoot: com.intellij.openapi.vfs.VirtualFile,
    entry: com.intellij.openapi.vfs.VirtualFile
  ): com.intellij.openapi.vfs.VirtualFile? {
    val jarfs = com.intellij.openapi.vfs.JarFileSystem.getInstance()
    val local = com.intellij.openapi.vfs.LocalFileSystem.getInstance()

    val rootEntry = jarfs.getRootByEntry(bundleRoot)
    val rel = entry.presentableUrl.substringAfter(rootEntry?.presentableUrl ?: bundleRoot.presentableUrl, "")
    val safeName = sanitizeExtractName(bundleRoot.name, rel)
    val projectVf = local.findFileByPath(project.presentableUrl ?: return null) ?: return null
    val outDir = readCompute { projectVf.findChild("out") } ?: writeComputeAndWait {
      projectVf.createChildDirectory(this, "out")
    }
    val tmpLib = readCompute { outDir.findChild("tmp_lib") } ?: writeComputeAndWait {
      outDir.createChildDirectory(this, "tmp_lib")
    }
    val target = readCompute { tmpLib.findChild(safeName) } ?: writeComputeAndWait {
      tmpLib.createChildData(this, safeName)
    }
    writeComputeAndWait {
      target.getOutputStream(target, entry.modificationStamp, entry.timeStamp).use { os ->
        entry.inputStream.use { ins -> ins.copyTo(os) }
      }
    }
    return jarfs.getJarRootForLocalFile(target)
  }

  companion object {
    fun sanitizeExtractName(bundleName: String, rel: String): String =
      (bundleName + rel).replace(Regex("[^A-Za-z0-9._-]"), "_")
  }

  override fun postResolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    area.updateModel { model ->
      val orderEntries = model.orderEntries.toMutableList()
      val orderEntriesMap = orderEntries.associateBy { it.presentableName }

      val compileOrder = orderEntriesMap[ModuleCompileOnlyLibraryName]

      val arrangeOrderEntries = orderEntries.apply {
        compileOrder?.also {
          remove(it)
          add(it)
        }
      }.toTypedArray()
      val sortedEntries = arrangeOrderEntries.sortedWith(compareBy {it.presentableName})
      model.rearrangeOrderEntries(sortedEntries.toTypedArray())
    }
  }
}
