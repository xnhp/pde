package cn.varsa.pde.resolver.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileUtilsTest {
  @Test
  fun mapProfileEmitsArtifacts() {
    val tmp = createTempDir(prefix = "profileTest")
    try {
      val profileDir = File(tmp, "abc.profile").apply { mkdirs() }
      val profileFile = File(profileDir, "0001.profile").apply { writeText(profileXml()) }

      val plugins = File(tmp, "pool/plugins").apply { mkdirs() }
      val features = File(tmp, "pool/features").apply { mkdirs() }

      // Create placeholders that mapProfileFile will reference
      File(plugins, "com.example.a_1.0.0.jar").apply { writeText("jar") }
      File(plugins, "com.example.b_2.0.0").apply { mkdirs() }
      File(features, "feat.one_3.0.0.jar").apply { writeText("jar") }
      File(features, "feat.two_4.0.0").apply { mkdirs() }

      val bundles = mutableListOf<File>()
      val feats = mutableListOf<File>()

      mapProfileFile(profileFile, plugins, features, { bundles += it }, { feats += it })

      assertTrue(bundles.any { it.name.startsWith("com.example.a_1.0.0") })
      assertTrue(bundles.any { it.name.startsWith("com.example.b_2.0.0") })
      assertTrue(feats.any { it.name.startsWith("feat.one_3.0.0") })
      assertTrue(feats.any { it.name.startsWith("feat.two_4.0.0") })
    } finally {
      tmp.deleteRecursively()
    }
  }

  private fun profileXml(): String = """
    <profile>
      <units>
        <unit id="u1" version="1.0.0"/>
      </units>
      <artifacts>
        <artifact classifier="osgi.bundle" id="com.example.a" version="1.0.0"/>
        <artifact classifier="osgi.bundle" id="com.example.b" version="2.0.0"/>
        <artifact classifier="org.eclipse.update.feature" id="feat.one" version="3.0.0"/>
        <artifact classifier="org.eclipse.update.feature" id="feat.two" version="4.0.0"/>
      </artifacts>
    </profile>
  """.trimIndent()
}

