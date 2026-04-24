package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApiAnalyzeDependencyListTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun expandsBundleClassPathEntriesForDirectoryBundles() {
    val bundleDir = tmp.newFolder("org.example.bundle")
    val metaInf = File(bundleDir, "META-INF").apply { mkdirs() }
    File(metaInf, "MANIFEST.MF").writeText(
      """
      Manifest-Version: 1.0
      Bundle-SymbolicName: org.example.bundle
      Bundle-Version: 1.0.0
      Bundle-ClassPath: .,lib/core.jar,lib/helper.jar

      """.trimIndent()
    )
    val coreJar = File(bundleDir, "lib/core.jar").apply {
      parentFile.mkdirs()
      writeText("x")
    }
    val helperJar = File(bundleDir, "lib/helper.jar").apply { writeText("x") }
    val standaloneJar = tmp.newFile("standalone.jar")

    val expanded = expandBundleClassPathEntries(listOf(bundleDir.toPath(), standaloneJar.toPath()))
      .map { it.toAbsolutePath().normalize() }

    assertEquals(4, expanded.size)
    assertTrue(expanded.contains(bundleDir.toPath().toAbsolutePath().normalize()))
    assertTrue(expanded.contains(coreJar.toPath().toAbsolutePath().normalize()))
    assertTrue(expanded.contains(helperJar.toPath().toAbsolutePath().normalize()))
    assertTrue(expanded.contains(standaloneJar.toPath().toAbsolutePath().normalize()))
  }

  @Test
  fun ignoresMissingBundleClassPathEntries() {
    val bundleDir = tmp.newFolder("org.example.missing")
    val metaInf = File(bundleDir, "META-INF").apply { mkdirs() }
    File(metaInf, "MANIFEST.MF").writeText(
      """
      Manifest-Version: 1.0
      Bundle-SymbolicName: org.example.missing
      Bundle-Version: 1.0.0
      Bundle-ClassPath: .,lib/missing.jar

      """.trimIndent()
    )

    val expanded = expandBundleClassPathEntries(listOf(bundleDir.toPath()))

    assertEquals(listOf(bundleDir.toPath().toAbsolutePath().normalize()), expanded)
  }
}
