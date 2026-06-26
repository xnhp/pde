package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.WorkspaceInputs
import cn.varsa.pde.resolver.manifest.BundleManifest
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

data class WorkspaceModuleDefinition(
  val moduleDir: Path,
  val classRoots: List<String>? = null,
  val manifestOverride: BundleManifest? = null,
  val compilerArgs: List<String> = emptyList()
)

object WorkspaceModuleBuilder {
  private val logger = Logger.getLogger(WorkspaceModuleBuilder::class.java.name)

  fun build(definitions: List<WorkspaceModuleDefinition>, allowMissingClasses: Boolean = false): WorkspaceInputs {
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

      val classRoots = resolveClassRoots(definition, descriptor, moduleDir)
      val devClassPaths = if (allowMissingClasses) {
        classRoots.map { moduleDir.resolve(it).normalize() }
      } else {
        classRoots.map { moduleDir.resolve(it).normalize() }.filter { Files.exists(it) }
      }

      val effectiveClassPath = when {
        devClassPaths.isNotEmpty() -> {
          if (!allowMissingClasses && moduleDir.toFile().isDirectory && !containsClasses(devClassPaths)) {
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
        classPathEntries = effectiveClassPath,
        compilerArgs = mergeCompilerArgs(descriptor.compilerArgs, definition.compilerArgs)
      )
      if (devClassPaths.isNotEmpty()) {
        devProps[bsn] = classRoots
      }
    }
    return WorkspaceInputs(descriptors, devProps)
  }

  private fun resolveClassRoots(
    definition: WorkspaceModuleDefinition,
    descriptor: WorkspaceBundleDescriptor,
    moduleDir: Path
  ): List<String> {
    val configured = definition.classRoots
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.takeIf { it.isNotEmpty() }
    if (configured != null) return configured

    val derivedRoot = if (descriptor.outputDirectoryFromBuildProperties) {
      descriptor.outputDirectory?.toRelativeClassRoot(moduleDir)
    } else {
      null
    }

    if (derivedRoot != null) {
      logger.info("Auto-derived classRoots for ${moduleDir}: [${derivedRoot}] (from build.properties output..)")
      return listOf(derivedRoot)
    }

    return WorkspaceDefaults.DEFAULT_CLASS_ROOTS
  }

  private fun Path.toRelativeClassRoot(moduleDir: Path): String? {
    val normalizedModule = moduleDir.toAbsolutePath().normalize()
    val normalizedOutput = toAbsolutePath().normalize()
    if (!normalizedOutput.startsWith(normalizedModule)) return null
    val relative = normalizedModule.relativize(normalizedOutput).toString()
      .replace('\\', '/')
      .trimEnd('/')
      .ifEmpty { "." }
    return relative
  }

  private fun mergeCompilerArgs(fromClasspath: List<String>, fromConfig: List<String>): List<String> =
    (fromClasspath + fromConfig)
      .map { it.trim() }
      .filter { it.isNotEmpty() }

  private fun containsClasses(classPaths: List<Path>): Boolean =
    classPaths.any { path ->
      Files.isDirectory(path) &&
        Files.walk(path).use { stream ->
          stream.anyMatch { Files.isRegularFile(it) && it.toString().endsWith(".class") }
        }
    }
}
