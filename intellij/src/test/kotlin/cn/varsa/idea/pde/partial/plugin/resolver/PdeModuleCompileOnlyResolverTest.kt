package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.pde.resolver.compile.CompileClasspathEntry
import cn.varsa.pde.resolver.compile.CompileClasspathMaterializer
import cn.varsa.pde.resolver.compile.CompileClasspathMaterializerOptions
import cn.varsa.pde.resolver.compile.CompileClasspathResult
import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME
import org.osgi.framework.Constants.BUNDLE_VERSION
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class PdeModuleCompileOnlyResolverTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun sanitizeExtractName_replaces_illegal_chars_stably() {
    val bundleJar = tmp.newFile("org.knime.core.pmml.jar").toPath()
    val entryPath = "lib/nested dir/inner.jar"
    val bytes = "payload".toByteArray()
    createJar(bundleJar, mapOf(entryPath to bytes))
    val bundle = ResolvedBundle(
      location = bundleJar,
      manifest = BundleManifest.parse(
        mapOf(BUNDLE_SYMBOLICNAME to "org.knime.core.pmml", BUNDLE_VERSION to "1.0.0")
      ),
      isDirectory = false
    )

    val result = CompileClasspathResult(
      entries = listOf(CompileClasspathEntry.TargetBundle(bundle, entryPath)),
      workspaceDependencies = emptySet(),
      problems = emptyList()
    )

    val extractionRoot = tmp.newFolder("extract").toPath()
    val materialized = CompileClasspathMaterializer.materialize(
      result,
      CompileClasspathMaterializerOptions(extractionRoot)
    )

    assertTrue(materialized.problems.isEmpty())
    val extracted = materialized.classpath.single()
    assertTrue(Files.exists(extracted))
    assertEquals("org.knime.core.pmml_lib_nested_dir_inner.jar", extracted.fileName.toString())
  }

  private fun createJar(path: Path, entries: Map<String, ByteArray>) {
    JarOutputStream(Files.newOutputStream(path)).use { jar ->
      entries.forEach { (name, bytes) ->
        jar.putNextEntry(JarEntry(name))
        jar.write(bytes)
        jar.closeEntry()
      }
    }
  }
}
