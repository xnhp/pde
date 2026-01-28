package cn.varsa.pde.resolver.compile

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class CompileExecutorIncrementalTest {
  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test
  fun `skips unchanged bundle`() {
    val bundle = temp.newFolder("bundle").toPath()
    val src = bundle.resolve("src").createDirectories()
    src.resolve("Example.java").writeText("class Example {}")
    bundle.resolve("plugin.xml").writeText("<plugin/>")

    val spec = compileSpec(bundle, src)
    val cache = BundleCompileCache(temp.newFile("compile-cache.properties").toPath())
    val compiler = RecordingCompiler()
    val copier = RecordingCopier()

    CompileExecutor.compile(listOf(spec), compiler, copier, cache)
    CompileExecutor.compile(listOf(spec), compiler, copier, cache)

    assertEquals(1, compiler.invocations.size, "compile should run only once")
    assertEquals(1, copier.invocations.size, "resources should be copied only once")
  }

  @Test
  fun `copies resources when only resources change`() {
    val bundle = temp.newFolder("bundle-res").toPath()
    val src = bundle.resolve("src").createDirectories()
    src.resolve("Example.java").writeText("class Example {}")
    val resource = bundle.resolve("plugin.xml")
    resource.writeText("<plugin/>")

    val spec = compileSpec(bundle, src)
    val cache = BundleCompileCache(temp.newFile("compile-cache2.properties").toPath())
    val compiler = RecordingCompiler()
    val copier = RecordingCopier()

    CompileExecutor.compile(listOf(spec), compiler, copier, cache)
    resource.writeText("<plugin id=\"changed\"/>")
    CompileExecutor.compile(listOf(spec), compiler, copier, cache)

    assertEquals(1, compiler.invocations.size, "compile should be skipped on resource-only change")
    assertEquals(2, copier.invocations.size, "resources should be recopied on change")
  }

  @Test
  fun `rebuilds dependents when dependency changes`() {
    val bundleA = temp.newFolder("bundle-a").toPath()
    val srcA = bundleA.resolve("src").createDirectories()
    srcA.resolve("A.java").writeText("class A {}")

    val bundleB = temp.newFolder("bundle-b").toPath()
    val srcB = bundleB.resolve("src").createDirectories()
    srcB.resolve("B.java").writeText("class B {}")

    val specA = compileSpec(bundleA, srcA, bsn = "org.example.a")
    val specB = compileSpec(bundleB, srcB, bsn = "org.example.b")

    val cache = BundleCompileCache(temp.newFile("compile-cache3.properties").toPath())
    val compiler = RecordingCompiler()
    val copier = RecordingCopier()

    val deps = mapOf("org.example.b" to setOf("org.example.a"))
    CompileExecutor.compile(listOf(specA, specB), compiler, copier, cache, deps)

    srcA.resolve("A.java").writeText("class A { int v; }")
    compiler.invocations.clear()
    copier.invocations.clear()

    CompileExecutor.compile(listOf(specA, specB), compiler, copier, cache, deps)

    assertEquals(setOf("org.example.a", "org.example.b"), compiler.invocations.toSet())
    assertEquals(2, copier.invocations.size)
  }

  @Test
  fun `force rebuild ignores cache`() {
    val bundle = temp.newFolder("bundle-force").toPath()
    val src = bundle.resolve("src").createDirectories()
    src.resolve("Example.java").writeText("class Example {}")

    val spec = compileSpec(bundle, src)
    val cache = BundleCompileCache(temp.newFile("compile-cache4.properties").toPath())
    val compiler = RecordingCompiler()
    val copier = RecordingCopier()

    CompileExecutor.compile(listOf(spec), compiler, copier, cache)
    CompileExecutor.compile(listOf(spec), compiler, copier, cache, forceFullRebuild = true)

    assertEquals(2, compiler.invocations.size, "force rebuild should recompile")
    assertEquals(2, copier.invocations.size, "force rebuild should recopy resources")
  }

  @Test
  fun `output dir changes do not invalidate cache`() {
    val bundle = temp.newFolder("bundle-output").toPath()
    val src = bundle.resolve("src").createDirectories()
    src.resolve("Example.java").writeText("class Example {}")
    val outDir = bundle.resolve("bin")

    val spec = compileSpec(bundle, src, classpath = listOf(outDir.toString()))
    val cache = BundleCompileCache(temp.newFile("compile-cache5.properties").toPath())
    val compiler = RecordingCompiler()
    val copier = RecordingCopier()

    CompileExecutor.compile(listOf(spec), compiler, copier, cache)
    outDir.resolve("Example.class").writeText("noop")
    compiler.invocations.clear()
    copier.invocations.clear()

    CompileExecutor.compile(listOf(spec), compiler, copier, cache)

    assertEquals(0, compiler.invocations.size, "output dir churn should not force recompile")
    assertEquals(0, copier.invocations.size, "output dir churn should not force recopy")
  }

  private fun compileSpec(
    bundle: Path,
    src: Path,
    bsn: String = "org.example.test",
    classpath: List<String> = emptyList()
  ): CompileSpec =
    CompileSpec(
      bsn = bsn,
      version = "1.0.0",
      origin = "workspace",
      bundlePath = bundle.toString(),
      classpath = classpath,
      sourceRoots = listOf(src.toString()),
      resourceIncludes = listOf("."),
      resourceExcludes = emptyList(),
      compilerPrefs = emptyMap(),
      executionEnvironment = null,
      outputDirectory = bundle.resolve("bin").toString(),
      isWorkspace = true
    )

  private class RecordingCompiler : CompilerPort {
    val invocations = mutableListOf<String>()

    override fun compile(spec: CompileSpec): BundleCompileResult {
      invocations.add(spec.bsn)
      return BundleCompileResult(
        spec.bsn,
        success = true,
        output = "compiled",
        durationMillis = 0,
        skipped = false
      )
    }
  }

  private class RecordingCopier : ResourceCopier {
    val invocations = mutableListOf<Path>()

    override fun copy(
      root: Path,
      outDir: Path,
      includes: List<String>,
      excludes: List<String>,
      classpathEntries: List<String>,
      sourceRoots: List<String>
    ) {
      invocations.add(outDir)
      if (!Files.exists(outDir)) Files.createDirectories(outDir)
    }
  }
}
