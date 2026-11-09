package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.launch.LaunchEnvironment
import cn.varsa.pde.resolver.launch.LaunchPlanner
import cn.varsa.pde.resolver.launch.LauncherOptions
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompileFragmentClasspathTest {
  private fun manifest(bsn: String, version: String = "1.0.0", vararg headers: Pair<String, String>) =
    BundleManifest.parse(mapOf("Bundle-SymbolicName" to bsn, "Bundle-Version" to version, *headers))

  @Test
  fun `consumer compiles against attached fragment and its dependencies before unrelated fragments`() {
    val root = Files.createTempDirectory("compile-fragments")
    try {
      fun workspace(bsn: String, vararg headers: Pair<String, String>): WorkspaceBundleDescriptor {
        val path = root.resolve(bsn)
        Files.createDirectories(path.resolve("src"))
        return WorkspaceBundleDescriptor(
          path, manifest(bsn, "1.0.0", *headers), classPathEntries = listOf(path.resolve("bin")),
          sourceRoots = listOf(path.resolve("src")), outputDirectory = path.resolve("bin")
        )
      }
      val consumer = workspace("consumer", "Require-Bundle" to "host;bundle-version=\"[1,2)\",lib;bundle-version=\"[1,2)\"")
      val fragment = workspace("fragment", "Fragment-Host" to "host;bundle-version=\"[1,2)\"", "Require-Bundle" to "dependency,lib")
      val dependency = workspace("dependency")
      val wrongVersion = workspace("wrong.version", "Fragment-Host" to "host;bundle-version=\"[2,3)\"")
      val unrelated = workspace("unrelated", "Fragment-Host" to "other")
      fun source(bundle: WorkspaceBundleDescriptor, name: String, text: String): Path =
        bundle.sourceRoots.single().resolve(name).also { Files.writeString(it, text) }
      source(dependency, "Base.java", "package dependency; public class Base { public void inherited() {} }")
      source(fragment, "Foo.java", "package p; public class Foo extends dependency.Base {}")
      source(consumer, "Consumer.java", "package c; public class Consumer { void use() { new p.Foo().inherited(); } }")
      val targets = listOf("host" to "1.0.0", "host" to "2.0.0", "other" to "1.0.0", "lib" to "1.0.0", "lib" to "2.0.0")
        .groupBy({ it.first }, { it.second }).mapValues { (bsn, versions) ->
          TreeMap<Version, ResolvedBundle>().apply {
            versions.forEach { version ->
              val path = Files.createDirectories(root.resolve("$bsn-$version"))
              put(Version(version), ResolvedBundle(path, manifest(bsn, version), true))
            }
          }
        }
      val target = TargetPlatformIndex(targets)
      // Consumer first exercises ordering: its attached fragment must be compiled before it.
      val workspace = listOf(consumer, fragment, dependency, wrongVersion, unrelated)
      val options = ResolveOptions()
      val plan = LaunchPlanner.build(LaunchEnvironment(target, workspace, options), LauncherOptions())
      val specs = CompileService.buildSpecs(plan, workspace, target, options).specs
      val consumerSpec = specs.single { it.bsn == "consumer" }
      assertTrue(consumerSpec.classpath.contains(fragment.outputDirectory.toString()))
      assertTrue(consumerSpec.classpath.contains(dependency.outputDirectory.toString()))
      assertFalse(consumerSpec.classpath.contains(wrongVersion.outputDirectory.toString()))
      assertFalse(consumerSpec.classpath.contains(unrelated.outputDirectory.toString()))
      assertTrue(consumerSpec.classpath.contains(root.resolve("lib-1.0.0").toString()))
      assertFalse(consumerSpec.classpath.contains(root.resolve("lib-2.0.0").toString()))
      assertTrue(specs.indexOfFirst { it.bsn == "dependency" } < specs.indexOfFirst { it.bsn == "fragment" })
      assertTrue(specs.indexOfFirst { it.bsn == "fragment" } < specs.indexOf(consumerSpec))
      // Compile the real class hierarchy in the emitted order and with the emitted classpaths.
      specs.filter { it.bsn in setOf("consumer", "fragment", "dependency") }.forEach { spec ->
        val output = Files.createDirectories(Path.of(requireNotNull(spec.outputDirectory)))
        val sources = Files.list(Path.of(spec.sourceRoots.single())).use { files -> files.toList() }
        val args = listOf("-classpath", spec.classpath.joinToString(java.io.File.pathSeparator), "-d", output.toString()) + sources.map { it.toString() }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray()), spec.bsn)
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `workspace host precedes its fragment and consumer waits for both`() {
    val root = Files.createTempDirectory("compile-workspace-host")
    try {
      fun bundle(bsn: String, vararg headers: Pair<String, String>) = WorkspaceBundleDescriptor(
        root.resolve(bsn), manifest(bsn, "1.0.0", *headers), classPathEntries = listOf(root.resolve("$bsn/bin"))
      )
      val consumer = bundle("consumer", "Require-Bundle" to "host")
      val host = bundle("host")
      val fragment = bundle("fragment", "Fragment-Host" to "host")
      val workspace = listOf(consumer, host, fragment)
      val target = TargetPlatformIndex(emptyMap())
      val options = ResolveOptions()
      val plan = LaunchPlanner.build(LaunchEnvironment(target, workspace, options), LauncherOptions())
      val specs = CompileService.buildSpecs(plan, workspace, target, options).specs
      assertEquals(listOf("host", "fragment", "consumer"), specs.map { it.bsn })
      assertTrue(specs.last().classpath.contains(fragment.classPathEntries.single().toString()))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `only plan-selected target fragment versions attach to workspace host`() {
    val root = Files.createTempDirectory("compile-target-fragments")
    try {
      val consumer = WorkspaceBundleDescriptor(root.resolve("consumer"), manifest("consumer", "1.0.0", "Require-Bundle" to "host"))
      val host = WorkspaceBundleDescriptor(root.resolve("host"), manifest("host"))
      val selector = WorkspaceBundleDescriptor(root.resolve("selector"), manifest("selector", "1.0.0", "Import-Package" to "p"))
      val target = TargetPlatformIndex(mapOf(
        "fragment" to TreeMap<Version, ResolvedBundle>().apply {
          listOf("1.0.0", "2.0.0").forEach { version ->
            put(Version(version), ResolvedBundle(root.resolve("fragment-$version"), manifest("fragment", version, "Fragment-Host" to "host", "Export-Package" to "p"), true))
          }
        }
      ))
      val workspace = listOf(consumer, host, selector)
      val options = ResolveOptions(pinnedVersions = mapOf("fragment" to Version("1.0.0")))
      val plan = LaunchPlanner.build(LaunchEnvironment(target, workspace, options), LauncherOptions())
      val spec = CompileService.buildSpecs(plan, workspace, target, options).specs.single { it.bsn == "consumer" }
      assertTrue(spec.classpath.contains(root.resolve("fragment-1.0.0").toString()))
      assertFalse(spec.classpath.contains(root.resolve("fragment-2.0.0").toString()))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

}
