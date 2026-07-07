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
  fun `converts exploded target bundle to valid jar preserving manifest resources and nested jars`() {
    val bundleDir = temp.newFolder("org.example.exploded").toPath()
    writeManifest(bundleDir, "org.example.exploded", "1.0.0", extraHeaders = mapOf("Bundle-ClassPath" to ".,lib/nested.jar"))
    Files.createDirectories(bundleDir.resolve("lib"))
    Files.createDirectories(bundleDir.resolve("OSGI-INF"))
    Files.writeString(bundleDir.resolve("OSGI-INF/component.xml"), "<component/>")
    Files.write(bundleDir.resolve("lib/nested.jar"), byteArrayOf(1, 2, 3))
    val bundle = ResolvedBundle(bundleDir, readDirectoryManifest(bundleDir), isDirectory = true)

    val result = materialize(targetBundles = listOf(bundle))

    assertEquals(1, result.artifacts.size)
    assertTrue(result.artifacts.single().synthetic)
    JarFile(result.artifacts.single().path.toFile()).use { jar ->
      assertEquals("org.example.exploded", jar.manifest.mainAttributes.getValue("Bundle-SymbolicName"))
      assertTrue(jar.getEntry("OSGI-INF/component.xml") != null)
      assertTrue(jar.getEntry("lib/nested.jar") != null)
    }
    assertTrue(result.diagnostics.any { it.type == AnalyzerArtifactDiagnosticType.EXPLODED_TARGET_BUNDLE })
    assertTrue(result.diagnostics.any { it.type == AnalyzerArtifactDiagnosticType.NESTED_JAR_WITHOUT_OSGI_IDENTITY })
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

  private fun readDirectoryManifest(path: Path): BundleManifest =
    Files.newInputStream(path.resolve("META-INF/MANIFEST.MF")).use { BundleManifest.parse(Manifest(it)) }

  private fun readJarManifest(path: Path): BundleManifest =
    JarFile(path.toFile()).use { BundleManifest.parse(it.manifest) }
}
