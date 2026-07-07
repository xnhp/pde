package cn.varsa.pde.resolver.api.artifact

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.api.AnalyzerBundleArtifact
import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.manifest.requiredBundleAndVersion
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarFile
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
  val diagnostics: List<AnalyzerArtifactDiagnostic>
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
  EXPLODED_TARGET_BUNDLE,
  NESTED_JAR_WITHOUT_OSGI_IDENTITY,
  UNRESOLVED_REQUIRE_BUNDLE_PROVIDER,
  WORKSPACE_BUNDLE_WITHOUT_COMPILED_OUTPUT,
  INVALID_BUNDLE_ARTIFACT
}

object AnalyzerArtifactMaterializer {
  fun materialize(
    input: AnalyzerArtifactMaterializerInput,
    options: AnalyzerArtifactMaterializerOptions
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
          message = "Target bundle lacks Bundle-SymbolicName: ${bundle.location}"
        )
        return@forEach
      }

      if (bundle.isDirectory) {
        diagnostics += AnalyzerArtifactDiagnostic(
          severity = AnalyzerArtifactDiagnosticSeverity.WARNING,
          type = AnalyzerArtifactDiagnosticType.EXPLODED_TARGET_BUNDLE,
          path = bundle.location,
          bundleSymbolicName = bsn,
          message = "Exploded target bundle would be ignored by BundleJarFiles; created synthetic jar"
        )
        val synthetic = syntheticRoot.resolve("${sanitize(bsn)}_${bundle.manifest.bundleVersion}.jar")
        createJarFromDirectory(bundle.location, synthetic)
        artifacts += AnalyzerBundleArtifact(bsn, bundle.manifest.bundleVersion.toString(), synthetic, bundle.location, synthetic = true)
        manifests += synthetic to bundle.manifest
        diagnostics += nestedJarDiagnostics(bundle.location, bsn)
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

    diagnostics += unresolvedRequireBundleDiagnostics(manifests)
    return AnalyzerArtifactMaterializerResult(artifacts = artifacts, diagnostics = diagnostics)
  }

  private fun createJarFromDirectory(source: Path, target: Path) {
    val manifestPath = source.resolve("META-INF/MANIFEST.MF")
    val manifest = Files.newInputStream(manifestPath).use(::Manifest)
    target.parent?.let { Files.createDirectories(it) }
    val written = linkedSetOf<String>()
    JarOutputStream(Files.newOutputStream(target), manifest).use { jar ->
      written += "META-INF/MANIFEST.MF"
      Files.walk(source).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .sorted()
          .forEach { file ->
            val entryName = source.relativize(file).toString().replace('\\', '/')
            if (entryName == "META-INF/MANIFEST.MF") return@forEach
            addFileEntry(jar, written, entryName, file)
          }
      }
    }
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

  private fun nestedJarDiagnostics(bundleDir: Path, bundleBsn: String): List<AnalyzerArtifactDiagnostic> {
    val diagnostics = mutableListOf<AnalyzerArtifactDiagnostic>()
    Files.walk(bundleDir).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && it.toString().endsWith(".jar", ignoreCase = true) }
        .forEach { jarPath ->
          val hasIdentity = runCatching {
            JarFile(jarPath.toFile()).use { jar ->
              jar.manifest?.let { BundleManifest.parse(it).bundleSymbolicName?.key } != null
            }
          }.getOrDefault(false)
          if (!hasIdentity) {
            diagnostics += AnalyzerArtifactDiagnostic(
              severity = AnalyzerArtifactDiagnosticSeverity.WARNING,
              type = AnalyzerArtifactDiagnosticType.NESTED_JAR_WITHOUT_OSGI_IDENTITY,
              path = jarPath,
              bundleSymbolicName = bundleBsn,
              message = "Nested jar does not provide an OSGi identity: $jarPath"
            )
          }
        }
    }
    return diagnostics
  }

  private fun unresolvedRequireBundleDiagnostics(manifests: List<Pair<Path, BundleManifest>>): List<AnalyzerArtifactDiagnostic> {
    val providers = manifests.mapNotNull { (_, manifest) ->
      manifest.bundleSymbolicName?.key?.let { it to manifest.bundleVersion }
    }.groupBy({ it.first }, { it.second })
    return manifests.flatMap { (path, manifest) ->
      val bsn = manifest.bundleSymbolicName?.key
      manifest.requiredBundleAndVersion().mapNotNull { (requiredBsn, range) ->
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
