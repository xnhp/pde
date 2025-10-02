package cn.varsa.pde.resolver.index

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TargetPlatformIndexCacheTest {
  @Test
  fun cacheHitAndMiss() {
    val root = createTempDir(prefix = "cacheRoot")
    val cache = File.createTempFile("idx-", ".properties")
    try {
      val plugins = File(root, "plugins").apply { mkdirs() }
      val dir = File(plugins, "com.example.a_1.0.0").apply { mkdirs() }
      writeDirManifest(dir, "com.example.a", "1.0.0")

      val first = TargetPlatformCache.buildWithCache(listOf(root.toPath()), cache)
      assertEquals(TargetPlatformIndex.Source.SCANNED, first.source)
      assertTrue(cache.exists())

      val second = TargetPlatformCache.buildWithCache(listOf(root.toPath()), cache)
      assertEquals(TargetPlatformIndex.Source.CACHED, second.source)
      assertNotNull(second.get("com.example.a"))

      // Modify manifest (touch) to force fingerprint change
      val mf = File(dir, "META-INF/MANIFEST.MF")
      mf.appendText("\n")
      val third = TargetPlatformCache.buildWithCache(listOf(root.toPath()), cache)
      assertEquals(TargetPlatformIndex.Source.SCANNED, third.source)
    } finally {
      cache.delete()
      root.deleteRecursively()
    }
  }

  private fun writeDirManifest(dir: File, bsn: String, version: String) {
    val metaInf = File(dir, "META-INF").apply { mkdirs() }
    File(metaInf, "MANIFEST.MF").writeText(
      "Manifest-Version: 1.0\n" +
        "Bundle-SymbolicName: $bsn\n" +
        "Bundle-Version: $version\n"
    )
  }
}

