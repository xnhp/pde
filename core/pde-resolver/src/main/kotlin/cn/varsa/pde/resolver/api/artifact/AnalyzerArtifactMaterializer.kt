package cn.varsa.pde.resolver.api.artifact

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.api.AnalyzerBundleArtifact
import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.manifest.requiredBundleAndVersion
import org.osgi.framework.Constants.SYSTEM_BUNDLE_SYMBOLICNAME
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

data class AnalyzerArtifactMaterializerOptions(
  val syntheticRoot: Path
)

data class AnalyzerArtifactMaterializerInput(
  val targetBundles: List<ResolvedBundle> = emptyList(),
  val workspaceBundles: List<WorkspaceBundleDescriptor> = emptyList()
)

data class AnalyzerArtifactMaterializerResult(
  val artifacts: List<AnalyzerBundleArtifact>,
  val diagnostics: List<AnalyzerArtifactDiagnostic>,
  val manifests: List<Pair<Path, BundleManifest>> = emptyList()
)

data class AnalyzerArtifactDiagnostic(
  val severity: AnalyzerArtifactDiagnosticSeverity,
  val type: AnalyzerArtifactDiagnosticType,
  val path: Path? = null,
  val bundleSymbolicName: String? = null,
  val message: String
)

enum class AnalyzerArtifactDiagnosticSeverity { INFO, WARNING, ERROR }

enum class AnalyzerArtifactDiagnosticType {
  UNRESOLVED_REQUIRE_BUNDLE_PROVIDER,
  WORKSPACE_BUNDLE_WITHOUT_COMPILED_OUTPUT,
  INVALID_BUNDLE_ARTIFACT
}

object AnalyzerArtifactMaterializer {
  fun materialize(
    input: AnalyzerArtifactMaterializerInput,
    options: AnalyzerArtifactMaterializerOptions,
    computeRequireBundleDiagnostics: Boolean = true
  ): AnalyzerArtifactMaterializerResult {
    val syntheticRoot = options.syntheticRoot.toAbsolutePath().normalize()
    Files.createDirectories(syntheticRoot)

    val artifacts = mutableListOf<AnalyzerBundleArtifact>()
    val diagnostics = mutableListOf<AnalyzerArtifactDiagnostic>()
    val manifests = mutableListOf<Pair<Path, BundleManifest>>()

    input.targetBundles.forEach { bundle ->
      val bsn = bundle.manifest.bundleSymbolicName?.key
      if (bsn == null) {
        diagnostics += AnalyzerArtifactDiagnostic(
          severity = AnalyzerArtifactDiagnosticSeverity.ERROR,
          type = AnalyzerArtifactDiagnosticType.INVALID_BUNDLE_ARTIFACT,
          path = bundle.location,
          message = "Target bundle artifact lacks Bundle-SymbolicName: ${bundle.location}"
        )
        return@forEach
      }

      if (Files.isDirectory(bundle.location)) {
        artifacts += AnalyzerBundleArtifact(bsn, bundle.manifest.bundleVersion.toString(), bundle.location, synthetic = false)
        manifests += bundle.location to bundle.manifest
      } else if (Files.isRegularFile(bundle.location)) {
        artifacts += AnalyzerBundleArtifact(bsn, bundle.manifest.bundleVersion.toString(), bundle.location, synthetic = false)
        manifests += bundle.location to bundle.manifest
      } else {
        diagnostics += AnalyzerArtifactDiagnostic(
          severity = AnalyzerArtifactDiagnosticSeverity.ERROR,
          type = AnalyzerArtifactDiagnosticType.INVALID_BUNDLE_ARTIFACT,
          path = bundle.location,
          bundleSymbolicName = bsn,
          message = "Target bundle artifact is not a file or directory: ${bundle.location}"
        )
      }
    }

    input.workspaceBundles.forEach { bundle ->
      val bsn = bundle.manifest.bundleSymbolicName?.key
      if (bsn == null) {
        diagnostics += AnalyzerArtifactDiagnostic(
          severity = AnalyzerArtifactDiagnosticSeverity.ERROR,
          type = AnalyzerArtifactDiagnosticType.INVALID_BUNDLE_ARTIFACT,
          path = bundle.path,
          message = "Workspace bundle lacks Bundle-SymbolicName: ${bundle.path}"
        )
        return@forEach
      }
      val compiledRoots = bundle.classPathEntries
        .filterNot { Files.isDirectory(it) && it.toAbsolutePath().normalize() == bundle.path.toAbsolutePath().normalize() }
        .filter { Files.exists(it) && containsClassesOrJar(it) }
      if (compiledRoots.isEmpty()) {
        diagnostics += AnalyzerArtifactDiagnostic(
          severity = AnalyzerArtifactDiagnosticSeverity.ERROR,
          type = AnalyzerArtifactDiagnosticType.WORKSPACE_BUNDLE_WITHOUT_COMPILED_OUTPUT,
          path = bundle.path,
          bundleSymbolicName = bsn,
          message = "Workspace bundle has no compiled class roots. Run compile/materialization before API analysis."
        )
        return@forEach
      }

      val synthetic = syntheticRoot.resolve("${sanitize(bsn)}_${bundle.manifest.bundleVersion}_workspace.jar")
      createWorkspaceJar(bundle, compiledRoots, synthetic)
      artifacts += AnalyzerBundleArtifact(bsn, bundle.manifest.bundleVersion.toString(), synthetic, bundle.path, synthetic = true)
      manifests += synthetic to bundle.manifest
    }

    if (computeRequireBundleDiagnostics) {
      diagnostics += unresolvedRequireBundleDiagnostics(manifests)
    }
    return AnalyzerArtifactMaterializerResult(artifacts = artifacts, diagnostics = diagnostics, manifests = manifests)
  }

  private fun createWorkspaceJar(bundle: WorkspaceBundleDescriptor, compiledRoots: List<Path>, target: Path) {
    val manifestPath = bundle.path.resolve("META-INF/MANIFEST.MF")
    val manifest = Files.newInputStream(manifestPath).use(::Manifest)
    target.parent?.let { Files.createDirectories(it) }
    val written = linkedSetOf<String>()
    JarOutputStream(Files.newOutputStream(target), manifest).use { jar ->
      written += "META-INF/MANIFEST.MF"
      val buildProperties = bundle.path.resolve("build.properties")
      if (Files.isRegularFile(buildProperties)) {
        addFileEntry(jar, written, "build.properties", buildProperties)
      }
      compiledRoots.sorted().forEach { root ->
        if (Files.isDirectory(root)) {
          addDirectoryContents(jar, written, root, "")
        } else if (Files.isRegularFile(root)) {
          val relative = runCatching { bundle.path.relativize(root).toString().replace('\\', '/') }
            .getOrDefault(root.fileName.toString())
          addFileEntry(jar, written, relative, root)
        }
      }
    }
  }

  private fun addDirectoryContents(jar: JarOutputStream, written: MutableSet<String>, root: Path, prefix: String) {
    Files.walk(root).use { stream ->
      stream
        .filter { Files.isRegularFile(it) }
        .sorted()
        .forEach { file ->
          val relative = root.relativize(file).toString().replace('\\', '/')
          addFileEntry(jar, written, prefix + relative, file)
        }
    }
  }

  private fun addFileEntry(jar: JarOutputStream, written: MutableSet<String>, entryName: String, file: Path) {
    val normalized = entryName.trimStart('/')
    if (normalized.isEmpty() || !written.add(normalized)) return
    jar.putNextEntry(JarEntry(normalized))
    Files.newInputStream(file).use { input -> input.copyTo(jar) }
    jar.closeEntry()
  }

  private fun containsClassesOrJar(path: Path): Boolean = when {
    Files.isRegularFile(path) -> path.toString().endsWith(".jar", ignoreCase = true) || path.toString().endsWith(".class", ignoreCase = true)
    Files.isDirectory(path) -> Files.walk(path).use { stream ->
      stream.anyMatch { Files.isRegularFile(it) && it.toString().endsWith(".class", ignoreCase = true) }
    }
    else -> false
  }

  /**
   * Checks each manifest's Require-Bundle entries against providers drawn from [manifests] itself
   * -- i.e. the manifests to check ARE the provider universe. Callers that only materialize a
   * narrow slice of the real analyzer input in one [materialize] call (e.g. "current bundle" and
   * "its dependencies" as two separate calls) must pass materialize(computeRequireBundleDiagnostics
   * = false) and instead call this directly with the union of every scope's manifests, otherwise
   * requirements satisfied by a sibling scope get falsely flagged as unresolved. See
   * AnalyzerArtifactMaterializerTest for the narrow-call-produces-false-positive regression tests.
   */
  fun unresolvedRequireBundleDiagnostics(manifests: List<Pair<Path, BundleManifest>>): List<AnalyzerArtifactDiagnostic> {
    val providers = manifests.mapNotNull { (_, manifest) ->
      manifest.bundleSymbolicName?.key?.let { it to manifest.bundleVersion }
    }.groupBy({ it.first }, { it.second })
    return manifests.flatMap { (path, manifest) ->
      val bsn = manifest.bundleSymbolicName?.key
      manifest.requiredBundleAndVersion().mapNotNull { (requiredBsn, range) ->
        // system.bundle is an OSGi alias for the framework bundle (bundle 0) which is always
        // present at runtime.  Unlike a real BSN it never appears in the analyzer artifact set,
        // so checking it here would produce a false-positive warning.  Eclipse PDE's own API
        // analysis avoids the problem by delegating to the OSGi resolver state which resolves
        // the alias transparently; we achieve the same effect by skipping it.
        if (requiredBsn == SYSTEM_BUNDLE_SYMBOLICNAME) return@mapNotNull null
        val resolved = providers[requiredBsn]?.any { range.includes(it) } == true
        if (resolved) null else AnalyzerArtifactDiagnostic(
          severity = AnalyzerArtifactDiagnosticSeverity.WARNING,
          type = AnalyzerArtifactDiagnosticType.UNRESOLVED_REQUIRE_BUNDLE_PROVIDER,
          path = path,
          bundleSymbolicName = bsn,
          message = "Require-Bundle provider not present in analyzer artifacts: $requiredBsn $range"
        )
      }
    }
  }

  private fun sanitize(value: String): String = buildString(value.length) {
    value.forEach { ch ->
      append(
        when {
          ch.isLetterOrDigit() -> ch
          ch == '.' || ch == '_' || ch == '-' -> ch
          else -> '_'
        }
      )
    }
  }.lowercase(Locale.US)
}
