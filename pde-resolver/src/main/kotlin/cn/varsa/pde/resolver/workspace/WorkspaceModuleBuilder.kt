package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.WorkspaceInputs
import cn.varsa.pde.resolver.manifest.BundleManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

data class WorkspaceModuleDefinition(
  val moduleDir: Path,
  val classRoots: List<String> = emptyList(),
  val manifestOverride: BundleManifest? = null
)

object WorkspaceModuleBuilder {
  private val defaultClassRoots = listOf("build/classes/java/main", "out/production")

  fun build(definitions: List<WorkspaceModuleDefinition>): WorkspaceInputs {
    val descriptors = mutableListOf<WorkspaceBundleDescriptor>()
    val devProps = linkedMapOf<String, List<String>>()
    definitions.forEach { definition ->
      val moduleDir = definition.moduleDir.toAbsolutePath().normalize()
      if (!Files.exists(moduleDir)) {
        throw WorkspaceModuleException("Workspace module directory not found: $moduleDir")
      }
      if (!Files.isDirectory(moduleDir)) {
        throw WorkspaceModuleException("Workspace module path is not a directory: $moduleDir")
      }
      val manifest = definition.manifestOverride ?: loadManifest(moduleDir)
        ?: throw WorkspaceModuleException("Missing META-INF/MANIFEST.MF in workspace module: $moduleDir")
      val bsn = manifest.bundleSymbolicName?.key ?: moduleDir.fileName.toString()
      val classRoots = (definition.classRoots.takeIf { it.isNotEmpty() } ?: defaultClassRoots)
      val classPathEntries = classRoots.map { moduleDir.resolve(it).normalize() }.filter { Files.exists(it) }
      descriptors += WorkspaceBundleDescriptor(
        path = moduleDir,
        manifest = manifest,
        classPathEntries = classPathEntries
      )
      devProps[bsn] = classRoots
    }
    return WorkspaceInputs(descriptors, devProps)
  }

  private fun loadManifest(moduleDir: Path): BundleManifest? {
    val manifestFile = moduleDir.resolve("META-INF/MANIFEST.MF")
    if (!Files.exists(manifestFile)) return null
    return manifestFile.inputStream().use { BundleManifest.parse(java.util.jar.Manifest(it)) }
  }
}
