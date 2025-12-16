package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ResolverWorkspaceTest {

  @Test
  fun `workspace bundle without dependencies is included`() {
    withTempDir { root ->
      val workspaceDir = createBundle(root.resolve("workspace"), "org.example.ws", "1.0.0")

      val index = TargetPlatformIndex.build(emptyList())
      val workspace = listOf(WorkspaceBundleDescriptor(workspaceDir, readManifest(workspaceDir)))

      val result = Resolver.resolve(index, workspace, workspace.first())

      assertEquals(1, result.bundles.size)
      val bundle = result.bundles.first()
      assertEquals("org.example.ws", bundle.bsn)
      assertTrue(bundle.isWorkspace)
    }
  }

  @Test
  fun `workspace bundle prefers workspace version over target`() {
    withTempDir { root ->
      val targetRoot = root.resolve("target").also { Files.createDirectories(it) }
      val pluginsRoot = targetRoot.resolve("plugins").also { Files.createDirectories(it) }
      createBundle(pluginsRoot.resolve("org.example.dependency_1.0.0"), "org.example.dependency", "1.0.0")

      val workspaceDir = createBundle(
        root.resolve("workspace"),
        "org.example.ws",
        "2.0.0",
        require = "org.example.dependency;bundle-version=\"1.0.0\""
      )

      val index = TargetPlatformIndex.build(listOf(targetRoot))
      val workspace = listOf(WorkspaceBundleDescriptor(workspaceDir, readManifest(workspaceDir)))

      val result = Resolver.resolve(index, workspace, workspace.first())

      val wsBundle = result.bundles.firstOrNull { it.bsn == "org.example.ws" }
      assertTrue(wsBundle?.isWorkspace == true)

      val depBundle = result.bundles.firstOrNull { it.bsn == "org.example.dependency" }
      assertTrue(depBundle?.isWorkspace == false)
    }
  }

  private fun withTempDir(block: (Path) -> Unit) {
    val dir = Files.createTempDirectory("resolver-test")
    try {
      block(dir)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  private fun createBundle(path: Path, bsn: String, version: String, require: String? = null): Path {
    Files.createDirectories(path)
    val metaInf = path.resolve("META-INF").also { Files.createDirectories(it) }
    val manifest = buildString {
      appendLine("Manifest-Version: 1.0")
      appendLine("Bundle-SymbolicName: $bsn")
      appendLine("Bundle-Version: $version")
      require?.let { appendLine("Require-Bundle: $it") }
    }
    Files.writeString(metaInf.resolve("MANIFEST.MF"), manifest)
    return path
  }

  private fun readManifest(path: Path) = java.util.jar.Manifest(Files.newInputStream(path.resolve("META-INF/MANIFEST.MF")))
    .let { BundleManifest.parse(it) }
}
