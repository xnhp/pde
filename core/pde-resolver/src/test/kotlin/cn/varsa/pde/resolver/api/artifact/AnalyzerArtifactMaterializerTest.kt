package cn.varsa.pde.resolver.api.artifact

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.workspace.WorkspaceModuleBuilder
import cn.varsa.pde.resolver.workspace.WorkspaceModuleDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class AnalyzerArtifactMaterializerTest {
  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test
  fun `passes packed bundle jars through unchanged`() {
    val jar = temp.root.toPath().resolve("org.example.bundle.jar")
    createJar(jar, "org.example.bundle", "1.0.0")
    val bundle = ResolvedBundle(jar, readJarManifest(jar), isDirectory = false)

    val result = materialize(targetBundles = listOf(bundle))

    assertEquals(1, result.artifacts.size)
    assertEquals(jar, result.artifacts.single().path)
    assertFalse(result.artifacts.single().synthetic)
    assertTrue(result.diagnostics.isEmpty())
  }

  @Test
  fun `passes exploded target bundle directories through unchanged`() {
    val bundleDir = temp.newFolder("org.example.exploded").toPath()
    writeManifest(bundleDir, "org.example.exploded", "1.0.0", extraHeaders = mapOf("Bundle-ClassPath" to ".,lib/nested.jar"))
    Files.createDirectories(bundleDir.resolve("lib"))
    Files.write(bundleDir.resolve("lib/nested.jar"), byteArrayOf(1, 2, 3))
    val bundle = ResolvedBundle(bundleDir, readDirectoryManifest(bundleDir), isDirectory = true)

    val result = materialize(targetBundles = listOf(bundle))

    assertEquals(1, result.artifacts.size)
    assertEquals(bundleDir, result.artifacts.single().path)
    assertFalse(result.artifacts.single().synthetic)
    assertTrue(Files.list(temp.root.toPath().resolve("synthetic")).use { it.noneMatch(Files::isRegularFile) })
    assertTrue(result.diagnostics.isEmpty())
  }

  @Test
  fun `reports invalid target bundle artifact paths`() {
    val missing = temp.root.toPath().resolve("missing.jar")
    val bundle = ResolvedBundle(missing, BundleManifest.parse(manifest("org.example.missing", "1.0.0", emptyMap())), isDirectory = false)

    val result = materialize(targetBundles = listOf(bundle))

    assertTrue(result.artifacts.isEmpty())
    assertEquals(AnalyzerArtifactDiagnosticType.INVALID_BUNDLE_ARTIFACT, result.diagnostics.single().type)
  }

  @Test
  fun `reports target bundle artifacts without bundle identity`() {
    val jar = temp.root.toPath().resolve("no-identity.jar")
    JarOutputStream(Files.newOutputStream(jar), manifestWithoutBundleIdentity()).use { }
    val bundle = ResolvedBundle(jar, readJarManifest(jar), isDirectory = false)

    val result = materialize(targetBundles = listOf(bundle))

    assertTrue(result.artifacts.isEmpty())
    assertEquals(AnalyzerArtifactDiagnosticType.INVALID_BUNDLE_ARTIFACT, result.diagnostics.single().type)
  }

  @Test
  fun `creates synthetic workspace bundle jar from bin output`() {
    val module = createWorkspaceModule("workspace-bin", "org.example.workspace", "bin")
    Files.write(module.resolve("bin/org/example/Dummy.class"), byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
    val workspace = WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(module))).descriptors

    val result = materialize(workspaceBundles = workspace)

    assertEquals(1, result.artifacts.size)
    assertTrue(result.artifacts.single().synthetic)
    JarFile(result.artifacts.single().path.toFile()).use { jar ->
      assertEquals("org.example.workspace", jar.manifest.mainAttributes.getValue("Bundle-SymbolicName"))
      assertTrue(jar.getEntry("org/example/Dummy.class") != null)
      assertTrue(jar.getEntry("bin/org/example/Dummy.class") == null)
      assertTrue(jar.getEntry("build.properties") != null)
    }
    assertTrue(result.diagnostics.isEmpty())
  }

  @Test
  fun `creates synthetic workspace bundle jar from build properties output`() {
    val module = createWorkspaceModule("workspace-output", "org.example.output", "bin/eclipse")
    Files.write(module.resolve("bin/eclipse/org/example/Dummy.class"), byteArrayOf(1))
    val workspace = WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(module))).descriptors

    val result = materialize(workspaceBundles = workspace)

    assertEquals(1, result.artifacts.size)
    JarFile(result.artifacts.single().path.toFile()).use { jar ->
      assertTrue(jar.getEntry("org/example/Dummy.class") != null)
    }
  }

  @Test
  fun `missing workspace outputs fail early with diagnostic`() {
    val module = createWorkspaceModule("workspace-missing", "org.example.missing", "bin")
    val descriptor = WorkspaceBundleDescriptor(module, readDirectoryManifest(module), classPathEntries = listOf(module.resolve("bin")))

    val result = materialize(workspaceBundles = listOf(descriptor))

    assertTrue(result.artifacts.isEmpty())
    assertEquals(AnalyzerArtifactDiagnosticType.WORKSPACE_BUNDLE_WITHOUT_COMPILED_OUTPUT, result.diagnostics.single().type)
  }

  @Test
  fun `reports unresolved require bundle providers`() {
    val jar = temp.root.toPath().resolve("org.example.needs.jar")
    createJar(jar, "org.example.needs", "1.0.0", extraHeaders = mapOf("Require-Bundle" to "org.example.missing"))
    val bundle = ResolvedBundle(jar, readJarManifest(jar), isDirectory = false)

    val result = materialize(targetBundles = listOf(bundle))

    assertTrue(result.diagnostics.any { it.type == AnalyzerArtifactDiagnosticType.UNRESOLVED_REQUIRE_BUNDLE_PROVIDER })
  }

  @Test
  fun `computeRequireBundleDiagnostics false skips the check but still exposes manifests`() {
    val jar = temp.root.toPath().resolve("org.example.needs.jar")
    createJar(jar, "org.example.needs", "1.0.0", extraHeaders = mapOf("Require-Bundle" to "org.example.missing"))
    val bundle = ResolvedBundle(jar, readJarManifest(jar), isDirectory = false)

    val result = AnalyzerArtifactMaterializer.materialize(
      AnalyzerArtifactMaterializerInput(targetBundles = listOf(bundle)),
      AnalyzerArtifactMaterializerOptions(temp.newFolder("synthetic-skip").toPath()),
      computeRequireBundleDiagnostics = false
    )

    assertTrue(result.diagnostics.none { it.type == AnalyzerArtifactDiagnosticType.UNRESOLVED_REQUIRE_BUNDLE_PROVIDER })
    assertEquals(1, result.manifests.size)
    assertEquals("org.example.needs", result.manifests.single().second.bundleSymbolicName?.key)
  }

  @Test
  fun `unresolvedRequireBundleDiagnostics resolves requirements against the full manifest union`() {
    val requirerJar = temp.root.toPath().resolve("org.example.requirer.jar")
    createJar(requirerJar, "org.example.requirer", "1.0.0", extraHeaders = mapOf("Require-Bundle" to "org.example.provider"))
    val providerJar = temp.root.toPath().resolve("org.example.provider.jar")
    createJar(providerJar, "org.example.provider", "1.0.0")

    val manifests = listOf(
      requirerJar to readJarManifest(requirerJar),
      providerJar to readJarManifest(providerJar)
    )

    val diagnostics = AnalyzerArtifactMaterializer.unresolvedRequireBundleDiagnostics(manifests)

    assertTrue(diagnostics.none { it.type == AnalyzerArtifactDiagnosticType.UNRESOLVED_REQUIRE_BUNDLE_PROVIDER })
  }

  @Test
  fun `unresolvedRequireBundleDiagnostics still flags a genuine gap in the manifest union`() {
    val requirerJar = temp.root.toPath().resolve("org.example.requirer2.jar")
    createJar(requirerJar, "org.example.requirer2", "1.0.0", extraHeaders = mapOf("Require-Bundle" to "org.example.absent"))
    val otherJar = temp.root.toPath().resolve("org.example.unrelated.jar")
    createJar(otherJar, "org.example.unrelated", "1.0.0")

    val manifests = listOf(
      requirerJar to readJarManifest(requirerJar),
      otherJar to readJarManifest(otherJar)
    )

    val diagnostics = AnalyzerArtifactMaterializer.unresolvedRequireBundleDiagnostics(manifests)

    assertTrue(diagnostics.any { it.type == AnalyzerArtifactDiagnosticType.UNRESOLVED_REQUIRE_BUNDLE_PROVIDER })
  }

  private fun materialize(
    targetBundles: List<ResolvedBundle> = emptyList(),
    workspaceBundles: List<WorkspaceBundleDescriptor> = emptyList()
  ) = AnalyzerArtifactMaterializer.materialize(
    AnalyzerArtifactMaterializerInput(targetBundles = targetBundles, workspaceBundles = workspaceBundles),
    AnalyzerArtifactMaterializerOptions(temp.newFolder("synthetic").toPath())
  )

  private fun createWorkspaceModule(dirName: String, bsn: String, output: String): Path {
    val module = temp.newFolder(dirName).toPath()
    writeManifest(module, bsn, "1.0.0")
    Files.writeString(module.resolve("build.properties"), "output.. = $output\n")
    Files.createDirectories(module.resolve(output).resolve("org/example"))
    return module
  }

  private fun createJar(
    path: Path,
    bsn: String,
    version: String,
    extraHeaders: Map<String, String> = emptyMap()
  ) {
    val manifest = manifest(bsn, version, extraHeaders)
    JarOutputStream(Files.newOutputStream(path), manifest).use { jar ->
      jar.putNextEntry(JarEntry("payload.txt"))
      jar.write("payload".toByteArray())
      jar.closeEntry()
    }
  }

  private fun writeManifest(
    bundleDir: Path,
    bsn: String,
    version: String,
    extraHeaders: Map<String, String> = emptyMap()
  ) {
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.newOutputStream(metaInf.resolve("MANIFEST.MF")).use { manifest(bsn, version, extraHeaders).write(it) }
  }

  private fun manifest(bsn: String, version: String, extraHeaders: Map<String, String>): Manifest = Manifest().apply {
    mainAttributes.putValue("Manifest-Version", "1.0")
    mainAttributes.putValue("Bundle-ManifestVersion", "2")
    mainAttributes.putValue("Bundle-SymbolicName", bsn)
    mainAttributes.putValue("Bundle-Version", version)
    extraHeaders.forEach { (key, value) -> mainAttributes.putValue(key, value) }
  }

  private fun manifestWithoutBundleIdentity(): Manifest = Manifest().apply {
    mainAttributes.putValue("Manifest-Version", "1.0")
    mainAttributes.putValue("Bundle-ManifestVersion", "2")
  }

  private fun readDirectoryManifest(path: Path): BundleManifest =
    Files.newInputStream(path.resolve("META-INF/MANIFEST.MF")).use { BundleManifest.parse(Manifest(it)) }

  private fun readJarManifest(path: Path): BundleManifest =
    JarFile(path.toFile()).use { BundleManifest.parse(it.manifest) }
}
