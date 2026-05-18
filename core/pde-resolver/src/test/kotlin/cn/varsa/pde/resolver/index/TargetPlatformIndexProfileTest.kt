package cn.varsa.pde.resolver.index

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TargetPlatformIndexProfileTest {
  @Test
  fun buildFromTargetDefinitionProfile_usesBundlePool() {
    val tmp = createTempDirectory("idxProfile").toFile()
    try {
      val bundlePool = File(tmp, "bundlepool").apply { mkdirs() }
      val plugins = File(bundlePool, "plugins").apply { mkdirs() }
      // valid jar with manifest
      writeJarWithManifest(File(plugins, "com.example.p_1.0.0.jar"), "com.example.p", "1.0.0")
      // exploded dir with manifest
      val qDir = File(plugins, "com.example.q_2.0.0").apply { mkdirs() }
      writeDirManifest(qDir, "com.example.q", "2.0.0")

      val profileDir = File(tmp, "TD.profile").apply { mkdirs() }
      // include p2 cache property so TargetPlatformIndex can resolve bundle pool path
      File(profileDir, "0001.profile").writeText(
        profileWithCacheXml("file:${bundlePool.absolutePath}")
      )

      val index = TargetPlatformIndex.build(listOf(profileDir.toPath()))

      assertNotNull(index.get("com.example.p"))
      assertNotNull(index.get("com.example.q"))
    } finally {
      tmp.deleteRecursively()
    }
  }

  private fun profileWithCacheXml(cacheUrl: String): String = """
    <profile>
      <properties>
        <property name="org.eclipse.equinox.p2.cache" value="$cacheUrl"/>
      </properties>
      <artifacts>
        <artifact classifier="osgi.bundle" id="com.example.p" version="1.0.0"/>
        <artifact classifier="osgi.bundle" id="com.example.q" version="2.0.0"/>
      </artifacts>
    </profile>
  """.trimIndent()

  private fun writeDirManifest(dir: File, bsn: String, version: String) {
    val metaInf = File(dir, "META-INF").apply { mkdirs() }
    File(metaInf, "MANIFEST.MF").writeText(
      "Manifest-Version: 1.0\n" +
        "Bundle-SymbolicName: $bsn\n" +
        "Bundle-Version: $version\n"
    )
  }

  private fun writeJarWithManifest(jarFile: File, bsn: String, version: String) {
    jarFile.outputStream().use { fos ->
      java.util.jar.JarOutputStream(fos).use { jos ->
        val mfContent = (
          "Manifest-Version: 1.0\n" +
            "Bundle-SymbolicName: $bsn\n" +
            "Bundle-Version: $version\n"
          ).toByteArray()
        jos.putNextEntry(java.util.jar.JarEntry("META-INF/"))
        jos.closeEntry()
        jos.putNextEntry(java.util.jar.JarEntry("META-INF/MANIFEST.MF"))
        jos.write(mfContent)
        jos.closeEntry()
      }
    }
  }
}
