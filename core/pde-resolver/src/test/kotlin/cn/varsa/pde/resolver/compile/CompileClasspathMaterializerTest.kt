package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME
import org.osgi.framework.Constants.BUNDLE_VERSION
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class CompileClasspathMaterializerTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  private fun manifest(vararg headers: Pair<String, String>) = BundleManifest.parse(mapOf(*headers))

  private fun resolvedBundle(name: String, version: String, isDir: Boolean = false): ResolvedBundle {
    val location = if (isDir) tmp.newFolder(name).toPath() else tmp.newFile("$name.jar").toPath()
    if (!isDir) createJar(location)
    return ResolvedBundle(location, manifest(BUNDLE_SYMBOLICNAME to name, BUNDLE_VERSION to version), isDir)
  }

  private fun createJar(path: Path, entries: Map<String, ByteArray> = emptyMap()) {
    JarOutputStream(Files.newOutputStream(path)).use { jar ->
      if (entries.isEmpty()) {
        jar.putNextEntry(JarEntry("dummy.txt"))
        jar.write("x".toByteArray())
        jar.closeEntry()
      } else {
        entries.forEach { (name, bytes) ->
          jar.putNextEntry(JarEntry(name))
          jar.write(bytes)
          jar.closeEntry()
        }
      }
    }
  }

  @Test
  fun extractsNestedJar() {
    val nestedName = "lib/inner.jar"
    val jarBytes = ByteArray(4) { it.toByte() }
    val bundle = resolvedBundle("org.example.bundle", "1.0.0")
    createJar(bundle.location, mapOf(nestedName to jarBytes))

    val result = CompileClasspathResult(
      entries = listOf(CompileClasspathEntry.TargetBundle(bundle, nestedName)),
      workspaceDependencies = emptySet(),
      problems = emptyList()
    )
    val extraction = tmp.newFolder("extract").toPath()
    val materialized = CompileClasspathMaterializer.materialize(result, CompileClasspathMaterializerOptions(extraction))

    assertEquals(1, materialized.classpath.size)
    val extractedFile = materialized.classpath.single()
    assertTrue(Files.exists(extractedFile))
    assertArrayEquals(jarBytes, Files.readAllBytes(extractedFile))
    assertTrue(materialized.problems.isEmpty())
  }

  @Test
  fun reusesCachedExtraction() {
    val nestedName = "lib/util.jar"
    val jarBytes = "data".toByteArray()
    val bundle = resolvedBundle("org.example.bundle", "1.0.0")
    createJar(bundle.location, mapOf(nestedName to jarBytes))
    val result = CompileClasspathResult(
      entries = listOf(
        CompileClasspathEntry.TargetBundle(bundle, nestedName),
        CompileClasspathEntry.TargetBundle(bundle, nestedName)
      ),
      workspaceDependencies = emptySet(),
      problems = emptyList()
    )
    val extraction = tmp.newFolder("extract2").toPath()
    val materialized = CompileClasspathMaterializer.materialize(result, CompileClasspathMaterializerOptions(extraction))

    assertEquals(2, materialized.classpath.size)
    assertEquals(materialized.classpath.first(), materialized.classpath.last())
  }

  @Test
  fun resolvesDirectoryEntries() {
    val dirBundle = resolvedBundle("org.example.dir", "1.0.0", isDir = true)
    Files.write(dirBundle.location.resolve("lib.txt"), byteArrayOf(5))
    val result = CompileClasspathResult(
      entries = listOf(CompileClasspathEntry.TargetBundle(dirBundle, "lib.txt")),
      workspaceDependencies = emptySet(),
      problems = emptyList()
    )
    val mat = CompileClasspathMaterializer.materialize(result, CompileClasspathMaterializerOptions(tmp.newFolder("ex").toPath()))
    assertEquals(dirBundle.location.resolve("lib.txt"), mat.classpath.single())
  }

  @Test
  fun reportsMissingJarEntries() {
    val bundle = resolvedBundle("org.example.bundle", "1.0.0")
    val result = CompileClasspathResult(
      entries = listOf(CompileClasspathEntry.TargetBundle(bundle, "missing.jar")),
      workspaceDependencies = emptySet(),
      problems = emptyList()
    )
    val mat = CompileClasspathMaterializer.materialize(result, CompileClasspathMaterializerOptions(tmp.newFolder("ex3").toPath()))
    assertTrue(mat.classpath.isEmpty())
    assertEquals(1, mat.problems.size)
    assertTrue(mat.problems.first().message.contains("missing.jar"))
  }
}
