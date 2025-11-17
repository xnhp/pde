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
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.compile.CompileClasspathEntry
import cn.varsa.pde.resolver.compile.CompileClasspathEnvironment
import cn.varsa.pde.resolver.compile.CompileClasspathResolver
import com.intellij.openapi.module.*
import com.intellij.openapi.roots.*
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths
import java.util.*

/**
 * Maps Eclipse `build.properties` compile-only classpaths into IntelliJ module order entries.
 *
 * Note: this resolver is intentionally separate from the shared `pde-resolver` runtime planner.
 * It does not attempt dependency resolution; it simply mirrors jars.extra.classpath semantics so
 * IDEA can compile modules with the same libraries Eclipse would see.
 */
class PdeModuleCompileOnlyResolver : BuildLibraryResolver {
  override val displayName: String = message("resolver.pde.buildCompileOnly")

  override fun resolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val moduleRoot = area.moduleRootManager.contentRoots.firstOrNull() ?: return
    moduleRoot.refresh(false, false)
    val buildPropertiesFile = moduleRoot.findChild(BuildProperties)?.also { it.refresh(false, false) } ?: return

    val buildProperties = Properties().apply { buildPropertiesFile.inputStream.use { load(it) } }

    val cacheService = BundleManifestCacheService.getInstance(area.project)
    val targetIndex = PluginTargetIndexService.getInstance(area.project).getIndex()

    val allModules = area.project.allPDEModules(area)
    val symbolicName2Module = allModules.mapNotNull { module ->
      val manifest = cacheService.getManifest(module)
      val bsn = manifest?.bundleSymbolicName?.key ?: return@mapNotNull null
      bsn to module
    }.toMap()

    val workspaceDescriptors = symbolicName2Module.mapNotNull { (bsn, module) ->
      val manifest = cacheService.getManifest(module) ?: return@mapNotNull null
      val root = module.moduleRootManager.contentRoots.firstOrNull() ?: return@mapNotNull null
      bsn to WorkspaceBundleDescriptor(Paths.get(root.path), manifest)
    }.toMap()

    val compileEnv = CompileClasspathEnvironment(
      moduleRoot = Paths.get(moduleRoot.path),
      buildProperties = buildProperties,
      targetIndex = targetIndex,
      workspaceBundles = workspaceDescriptors
    )

    val compileResult = CompileClasspathResolver.resolve(compileEnv)

    val moduleDependency = compileResult.workspaceDependencies.mapNotNullTo(linkedSetOf()) { symbolicName2Module[it] }
    val classesRoot = linkedSetOf<VirtualFile>()
    val localFs = LocalFileSystem.getInstance()
    val jarFs = JarFileSystem.getInstance()

    compileResult.entries.forEach { entry ->
      when (entry) {
        is CompileClasspathEntry.ModulePath ->
          localFs.findFileByNioFile(entry.path)?.let { classesRoot += it }

        is CompileClasspathEntry.TargetBundle ->
          resolveTargetEntry(entry, area.project, jarFs, localFs)?.let { classesRoot += it }

        is CompileClasspathEntry.WorkspaceResource -> {
          val module = entry.descriptor.manifest.bundleSymbolicName?.key?.let { symbolicName2Module[it] }
          val vf = module?.moduleRootManager?.contentRoots?.firstNotNullOfOrNull { root ->
            root.findFileByRelativePath(entry.entryPath)
          }
          if (vf != null) classesRoot += vf
        }
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

  private fun resolveTargetEntry(
    entry: CompileClasspathEntry.TargetBundle,
    project: com.intellij.openapi.project.Project,
    jarFs: JarFileSystem,
    localFs: LocalFileSystem
  ): com.intellij.openapi.vfs.VirtualFile? {
    val bundleFile = localFs.findFileByNioFile(entry.bundle.location) ?: return null
    val root = if (entry.bundle.isDirectory) bundleFile else jarFs.getJarRootForLocalFile(bundleFile) ?: bundleFile
    val relativePath = entry.entryPath
    val target = if (relativePath == null) {
      root
    } else {
      root.findFileByRelativePath(relativePath) ?: return null
    }
    return when {
      target.fileSystem === jarFs && target.extension?.lowercase() in setOf("jar", "aar", "war") ->
        extractNestedJar(project, root, target)
      target.fileSystem === localFs -> jarFs.getJarRootForLocalFile(target) ?: target
      else -> target
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
