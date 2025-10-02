package cn.varsa.pde.resolver.features

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureScannerTest {
  @Test
  fun scanEclipseSdkFeatures() {
    val root = createTempDir(prefix = "sdkFeat")
    try {
      val features = File(root, "features").apply { mkdirs() }
      File(features, "feat.a_1.0.0.jar").writeText("jar")
      File(features, "feat.b_2.0.0").mkdirs()

      val seen = mutableListOf<File>()
      FeatureScanner.scanEclipseSdkFeatures(root) { seen += it }
      assertTrue(seen.any { it.name.startsWith("feat.a_1.0.0") })
      assertTrue(seen.any { it.name.startsWith("feat.b_2.0.0") })
    } finally {
      root.deleteRecursively()
    }
  }
}

