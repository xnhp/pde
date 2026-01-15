package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.WorkspaceInputs
import cn.varsa.pde.resolver.manifest.BundleManifest
import java.nio.file.Files
import java.nio.file.Path

data class WorkspaceModuleDefinition(
  val moduleDir: Path,
  val classRoots: List<String> = emptyList(),
  val manifestOverride: BundleManifest? = null
)

object WorkspaceModuleBuilder {
  private val defaultClassRoots = listOf("out/production")

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
      val descriptor = runCatching { WorkspaceBundleLoader.load(moduleDir) }
        .getOrElse { cause ->
          throw WorkspaceModuleException("Failed to load workspace module at $moduleDir: ${cause.message}")
        }
      val manifest = definition.manifestOverride ?: descriptor.manifest
      val bsn = manifest.bundleSymbolicName?.key ?: moduleDir.fileName.toString()

      val classRoots = (definition.classRoots.takeIf { it.isNotEmpty() } ?: defaultClassRoots)
      val devClassPaths = classRoots.map { moduleDir.resolve(it).normalize() }.filter { Files.exists(it) }

      val effectiveClassPath = when {
        devClassPaths.isNotEmpty() -> {
          if (moduleDir.toFile().isDirectory && !containsClasses(devClassPaths)) {
            val requested = classRoots.joinToString(", ") { moduleDir.resolve(it).toString() }
            throw WorkspaceModuleException(
              "No compiled classes found for workspace bundle $bsn. Checked: $requested"
            )
          }
          (devClassPaths + descriptor.classPathEntries).distinct()
        }
        else -> descriptor.classPathEntries
      }

      descriptors += descriptor.copy(
        manifest = manifest,
        classPathEntries = effectiveClassPath
      )
      if (devClassPaths.isNotEmpty()) {
        devProps[bsn] = classRoots
      }
    }
    return WorkspaceInputs(descriptors, devProps)
  }

  private fun containsClasses(classPaths: List<Path>): Boolean =
    classPaths.any { path ->
      Files.isDirectory(path) &&
        Files.walk(path).use { stream ->
          stream.anyMatch { Files.isRegularFile(it) && it.toString().endsWith(".class") }
        }
    }
}
