package cn.varsa.pde.resolver.features

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureScannerP2AndTargetTest {
  @Test
  fun scanTargetDefinitionFeatures_emitsFromBundlePool() {
    val tmp = createTempDir(prefix = "tdefFeat")
    try {
      val profileDir = File(tmp, "My.profile").apply { mkdirs() }
      File(profileDir, "0001.profile").writeText(profileXml())

      val bundlePool = File(tmp, "pool").apply { mkdirs() }
      val plugins = File(bundlePool, "plugins").apply { mkdirs() }
      val features = File(bundlePool, "features").apply { mkdirs() }

      File(features, "feat.a_1.0.0.jar").writeText("jar")
      File(features, "feat.b_2.0.0").mkdirs()
      File(plugins, "com.example.a_1.0.0.jar").writeText("jar")
      File(plugins, "com.example.b_2.0.0").mkdirs()

      val seen = mutableListOf<File>()
      FeatureScanner.scanTargetDefinitionFeatures(profileDir, bundlePool) { seen += it }

      assertTrue(seen.any { it.name.startsWith("feat.a_1.0.0") })
      assertTrue(seen.any { it.name.startsWith("feat.b_2.0.0") })
    } finally {
      tmp.deleteRecursively()
    }
  }

  @Test
  fun scanP2Features_emitsFromProfile() {
    val tmp = createTempDir(prefix = "p2Feat")
    try {
      val p2Dir = File(tmp, "p2").apply { mkdirs() }
      val profileName = "SDKProfile"
      val profileDir = File(
        p2Dir,
        "org.eclipse.equinox.p2.engine/profileRegistry/$profileName.profile"
      ).apply { mkdirs() }
      File(profileDir, "0001.profile").writeText(profileXml())

      val plugins = File(p2Dir, "pool/plugins").apply { mkdirs() }
      val features = File(p2Dir, "pool/features").apply { mkdirs() }

      File(features, "feat.a_1.0.0.jar").writeText("jar")
      File(features, "feat.b_2.0.0").mkdirs()
      File(plugins, "com.example.a_1.0.0.jar").writeText("jar")
      File(plugins, "com.example.b_2.0.0").mkdirs()

      val seen = mutableListOf<File>()
      FeatureScanner.scanP2Features(p2Dir, profileName) { seen += it }

      assertTrue(seen.any { it.name.startsWith("feat.a_1.0.0") })
      assertTrue(seen.any { it.name.startsWith("feat.b_2.0.0") })
    } finally {
      tmp.deleteRecursively()
    }
  }

  private fun profileXml(): String = """
    <profile>
      <artifacts>
        <artifact classifier="osgi.bundle" id="com.example.a" version="1.0.0"/>
        <artifact classifier="osgi.bundle" id="com.example.b" version="2.0.0"/>
        <artifact classifier="org.eclipse.update.feature" id="feat.a" version="1.0.0"/>
        <artifact classifier="org.eclipse.update.feature" id="feat.b" version="2.0.0"/>
      </artifacts>
    </profile>
  """.trimIndent()
}

