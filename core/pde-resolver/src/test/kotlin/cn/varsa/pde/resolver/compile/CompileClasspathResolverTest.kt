package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME
import org.osgi.framework.Constants.BUNDLE_VERSION
import java.nio.file.Paths
import java.util.NavigableMap
import java.util.Properties
import java.util.TreeMap

class CompileClasspathResolverTest {

  private fun manifest(vararg headers: Pair<String, String>): BundleManifest =
    BundleManifest.parse(mapOf(*headers))

  private fun workspaceDescriptor(bsn: String, version: String) = WorkspaceBundleDescriptor(
    path = Paths.get("/workspace/$bsn"),
    manifest = manifest(BUNDLE_SYMBOLICNAME to bsn, BUNDLE_VERSION to version)
  )

  private fun resolvedBundle(bsn: String, version: String): ResolvedBundle =
    ResolvedBundle(
      location = Paths.get("/target/$bsn-$version"),
      manifest = manifest(BUNDLE_SYMBOLICNAME to bsn, BUNDLE_VERSION to version),
      isDirectory = false
    )

  private fun tpIndex(vararg bundles: ResolvedBundle): TargetPlatformIndex {
    val byBsn = HashMap<String, NavigableMap<org.osgi.framework.Version, ResolvedBundle>>()
    bundles.forEach { rb ->
      val bsn = rb.manifest.bundleSymbolicName?.key ?: return@forEach
      val version = rb.manifest.bundleVersion
      byBsn.computeIfAbsent(bsn) { TreeMap() }[version] = rb
    }
    return TargetPlatformIndex(byBsn)
  }

  @Test
  fun resolvesModuleRelativeEntries() {
    val props = Properties().apply { setProperty("jars.extra.classpath", "lib/helper.jar") }
    val env = CompileClasspathEnvironment(
      moduleRoot = Paths.get("/workspace/app"),
      buildProperties = props,
      targetIndex = tpIndex(),
      workspaceBundles = emptyMap()
    )
    val result = CompileClasspathResolver.resolve(env)
    val entry = result.entries.single() as CompileClasspathEntry.ModulePath
    assertEquals(Paths.get("/workspace/app/lib/helper.jar"), entry.path)
    assertTrue(result.workspaceDependencies.isEmpty())
    assertTrue(result.problems.isEmpty())
  }

  @Test
  fun resolvesTargetBundleEntries() {
    val props = Properties().apply { setProperty("jars.extra.classpath", "platform:/plugin/org.example.dep/lib/inner.jar") }
    val dep = resolvedBundle("org.example.dep", "1.0.0")
    val env = CompileClasspathEnvironment(
      moduleRoot = Paths.get("/workspace/app"),
      buildProperties = props,
      targetIndex = tpIndex(dep),
      workspaceBundles = emptyMap()
    )
    val result = CompileClasspathResolver.resolve(env)
    val entry = result.entries.single() as CompileClasspathEntry.TargetBundle
    assertEquals(dep, entry.bundle)
    assertEquals("lib/inner.jar", entry.entryPath)
  }

  @Test
  fun recordsWorkspaceDependenciesWhenBundleMissingFromTarget() {
    val props = Properties().apply { setProperty("jars.extra.classpath", "platform:/plugin/org.example.workspace") }
    val workspace = workspaceDescriptor("org.example.workspace", "1.0.0")
    val env = CompileClasspathEnvironment(
      moduleRoot = Paths.get("/workspace/app"),
      buildProperties = props,
      targetIndex = tpIndex(),
      workspaceBundles = mapOf("org.example.workspace" to workspace)
    )
    val result = CompileClasspathResolver.resolve(env)
    assertTrue(result.entries.isEmpty())
    assertEquals(setOf("org.example.workspace"), result.workspaceDependencies)
  }

  @Test
  fun recordsProblemsForUnknownBundles() {
    val props = Properties().apply { setProperty("jars.extra.classpath", "platform:/plugin/missing.bundle/lib/a.jar") }
    val env = CompileClasspathEnvironment(
      moduleRoot = Paths.get("/workspace/app"),
      buildProperties = props,
      targetIndex = tpIndex(),
      workspaceBundles = emptyMap()
    )
    val result = CompileClasspathResolver.resolve(env)
    assertTrue(result.entries.isEmpty())
    val problem = result.problems.single()
    assertEquals(CompileClasspathProblemType.MISSING_BUNDLE, problem.type)
  }
}
