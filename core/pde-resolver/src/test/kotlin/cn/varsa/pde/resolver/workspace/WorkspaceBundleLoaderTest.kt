package cn.varsa.pde.resolver.workspace

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceBundleLoaderTest {

  @Test
  fun `loads PDE metadata from directory bundle`() {
    val dir = Files.createTempDirectory("ws-bundle")
    createDirBundle(dir)

    val desc = WorkspaceBundleLoader.load(dir)

    assertEquals("org.example.test", desc.manifest.bundleSymbolicName?.key)
    assertEquals("JavaSE-11", desc.executionEnvironment)

    assertTrue(desc.classPathEntries.any { it.endsWith("lib/dep.jar") })
    assertTrue(desc.sourceRoots.any { it.endsWith("src") })
    assertEquals(listOf("META-INF/", ".", "icons/"), desc.resourceIncludes)
    assertEquals(listOf("**/*.bak"), desc.resourceExcludes)
    assertEquals("11", desc.compilerPrefs["org.eclipse.jdt.core.compiler.source"])
    assertEquals("11", desc.compilerPrefs["org.eclipse.jdt.core.compiler.compliance"])
    assertTrue(desc.outputDirectory?.endsWith("bin/test-output") == true)
  }

  @Test
  fun `defaults for jar bundle`() {
    val jarPath = Files.createTempFile("bundle", ".jar")
    createJarBundle(jarPath)

    val desc = WorkspaceBundleLoader.load(jarPath)

    assertEquals(jarPath.toAbsolutePath().normalize(), desc.path)
    assertTrue(desc.classPathEntries.contains(jarPath.toAbsolutePath().normalize()))
    assertTrue(desc.sourceRoots.isEmpty())
    assertTrue(desc.resourceIncludes.isEmpty())
    assertTrue(desc.compilerPrefs.isEmpty())
    assertNull(desc.executionEnvironment)
    assertNull(desc.outputDirectory)
  }

  private fun createDirBundle(dir: Path) {
    val metaInf = dir.resolve("META-INF").createDirectories()
    val mf = """
      Manifest-Version: 1.0
      Bundle-ManifestVersion: 2
      Bundle-Name: Test Bundle
      Bundle-SymbolicName: org.example.test
      Bundle-Version: 1.0.0
      Bundle-RequiredExecutionEnvironment: JavaSE-11
      Bundle-ClassPath: .,lib/dep.jar
      
    """.trimIndent()
    metaInf.resolve("MANIFEST.MF").writeText(mf)
    dir.resolve("build.properties").writeText(
      """
        source.main=src
        bin.includes = META-INF/,.,icons/
        bin.excludes = **/*.bak
        output.. = bin/test-output
      """.trimIndent()
    )
    val settings = dir.resolve(".settings").createDirectories()
    settings.resolve("org.eclipse.jdt.core.prefs").writeText(
      """
        eclipse.preferences.version=1
        org.eclipse.jdt.core.compiler.source=11
        org.eclipse.jdt.core.compiler.compliance=11
        org.eclipse.jdt.core.compiler.codegen.targetPlatform=11
      """.trimIndent()
    )
    dir.resolve("lib").createDirectories().resolve("dep.jar").let { Files.createFile(it) }
    dir.resolve("src").createDirectories()
  }

  private fun createJarBundle(jarPath: Path) {
    val manifest = Manifest().apply {
      mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
      mainAttributes.putValue("Bundle-ManifestVersion", "2")
      mainAttributes.putValue("Bundle-SymbolicName", "org.example.jar")
      mainAttributes.putValue("Bundle-Version", "1.0.0")
    }
    JarOutputStream(Files.newOutputStream(jarPath), manifest).use { /* empty */ }
  }
}
