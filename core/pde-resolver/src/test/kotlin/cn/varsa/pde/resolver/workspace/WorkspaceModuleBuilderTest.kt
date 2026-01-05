package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.launch.WorkspaceInputs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceModuleBuilderTest {

  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test(expected = WorkspaceModuleException::class)
  fun failsWhenModuleDirectoryMissing() {
    val missingDir = temp.root.toPath().resolve("missing-module")
    WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(missingDir)))
  }

  @Test(expected = WorkspaceModuleException::class)
  fun failsWhenManifestMissing() {
    val moduleDir = temp.newFolder("module-no-manifest").toPath()
    WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(moduleDir)))
  }

  @Test
  fun succeedsWhenManifestPresent() {
    val moduleDir = temp.newFolder("module-with-manifest").toPath()
    val metaInf = File(moduleDir.toFile(), "META-INF")
    metaInf.mkdirs()
    val manifest = File(metaInf, "MANIFEST.MF")
    manifest.writeText(
      "Bundle-ManifestVersion: 2\n" +
        "Bundle-SymbolicName: test.module\n" +
        "Bundle-Version: 1.0.0\n\n"
    )
    // Provide a class output directory that matches the default root
    File(moduleDir.toFile(), "out/production").mkdirs()

    val inputs: WorkspaceInputs = WorkspaceModuleBuilder.build(
      listOf(WorkspaceModuleDefinition(moduleDir))
    )

    assertEquals(1, inputs.descriptors.size)
    assertEquals("test.module", inputs.descriptors.first().manifest.bundleSymbolicName?.key)
  }

  @Test(expected = WorkspaceModuleException::class)
  fun failsWhenNoClassOutputsFound() {
    val moduleDir = temp.newFolder("module-no-classes").toPath()
    val metaInf = File(moduleDir.toFile(), "META-INF")
    metaInf.mkdirs()
    File(metaInf, "MANIFEST.MF").writeText(
      "Bundle-ManifestVersion: 2\n" +
        "Bundle-SymbolicName: test.module\n" +
        "Bundle-Version: 1.0.0\n\n"
    )

    // No class output directories created under moduleDir; should fail fast
    WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(moduleDir)))
  }
}
