package cn.varsa.pde.resolver.index

import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.VersionRange
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory

class TargetPlatformIndexTest {
  @Test
  fun scanEclipseSdkRoot() {
    val root = createTempDirectory("sdkRoot").toFile()
    try {
      val plugins = File(root, "plugins").apply { mkdirs() }

      // dir bundle (as typical exploded bundle)
      val dirJar = File(plugins, "com.example.jar_1.2.3").apply { mkdirs() }
      writeDirManifest(dirJar, "com.example.jar", "1.2.3")

      // dir bundle
      val dir = File(plugins, "com.example.dir_0.9.0").apply { mkdirs() }
      writeDirManifest(dir, "com.example.dir", "0.9.0")

      val index = TargetPlatformIndex.build(listOf(root.toPath()))

      // by BSN
      assertNotNull(index.get("com.example.jar"))
      assertNotNull(index.get("com.example.dir"))

      // pick latest
      val rb = index.get("com.example.jar")
      assertNotNull(rb)
      val v = rb!!.manifest.bundleVersion
      assertEquals(1, v.major)
      assertEquals(2, v.minor)
      assertEquals(3, v.micro)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun writeDirManifest(dir: File, bsn: String, version: String) {
    val metaInf = File(dir, "META-INF").apply { mkdirs() }
    File(metaInf, "MANIFEST.MF").writeText(manifestText(bsn, version))
  }

  private fun manifestText(bsn: String, version: String) =
    "Manifest-Version: 1.0\n" +
      "Bundle-SymbolicName: $bsn\n" +
      "Bundle-Version: $version\n"
}
