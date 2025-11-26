package cn.varsa.pde.resolver.cli.config

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.manifest.BundleManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

data class WorkspaceInputs(
  val descriptors: List<WorkspaceBundleDescriptor>,
  val devProperties: Map<String, List<String>>
)

object WorkspaceModuleResolver {
  private val defaultClassRoots = listOf("build/classes/java/main", "out/production")

  fun resolve(context: LaunchConfigContext): WorkspaceInputs {
    val descriptors = mutableListOf<WorkspaceBundleDescriptor>()
    val devProps = linkedMapOf<String, List<String>>()
    context.config.workspaceModules.forEach { module ->
      val modulePath = context.baseDir.resolve(module.path).normalize()
      val manifest = loadManifest(modulePath)
        ?: error("Missing MANIFEST.MF for workspace module ${module.path}")
      val bsn = manifest.bundleSymbolicName?.key
        ?: error("Workspace module ${module.path} lacks Bundle-SymbolicName")
      val classRoots = (module.classes?.takeIf { it.isNotEmpty() } ?: defaultClassRoots)
      val classPaths = classRoots.map { modulePath.resolve(it).normalize() }.filter { Files.exists(it) }
      descriptors += WorkspaceBundleDescriptor(
        path = modulePath,
        manifest = manifest,
        classPathEntries = classPaths
      )
      devProps[bsn] = classRoots
    }
    return WorkspaceInputs(descriptors = descriptors, devProperties = devProps)
  }

  private fun loadManifest(modulePath: Path): BundleManifest? {
    val manifestFile = modulePath.resolve("META-INF/MANIFEST.MF")
    if (!manifestFile.exists()) return null
    return manifestFile.inputStream().use { BundleManifest.parse(java.util.jar.Manifest(it)) }
  }
}
