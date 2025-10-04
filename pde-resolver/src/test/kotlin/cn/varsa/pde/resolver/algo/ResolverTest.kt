package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME
import org.osgi.framework.Constants.BUNDLE_VERSION
import org.osgi.framework.Constants.EXPORT_PACKAGE
import org.osgi.framework.Constants.FRAGMENT_HOST
import org.osgi.framework.Constants.IMPORT_PACKAGE
import org.osgi.framework.Constants.REQUIRE_BUNDLE
import org.osgi.framework.Constants.VISIBILITY_DIRECTIVE
import org.osgi.framework.Constants.VISIBILITY_REEXPORT
import org.osgi.framework.Version
import org.osgi.framework.VersionRange
import java.nio.file.Paths
import java.util.NavigableMap
import java.util.TreeMap

class ResolverTest {

  private fun bm(vararg pairs: Pair<String, String>) =
    BundleManifest.parse(mapOf(*pairs))

  private fun rb(bsn: String, ver: String, vararg headers: Pair<String, String>): ResolvedBundle =
    ResolvedBundle(
      location = Paths.get("/tmp/$bsn-$ver"),
      manifest = bm(BUNDLE_SYMBOLICNAME to bsn, BUNDLE_VERSION to ver, *headers),
      isDirectory = false
    )

  private fun wbd(bsn: String, ver: String, vararg headers: Pair<String, String>) =
    WorkspaceBundleDescriptor(
      path = Paths.get("/workspace/$bsn-$ver"),
      manifest = bm(BUNDLE_SYMBOLICNAME to bsn, BUNDLE_VERSION to ver, *headers)
    )

  private fun tpIndex(vararg bundles: ResolvedBundle): TargetPlatformIndex {
    val byBsn: MutableMap<String, NavigableMap<Version, ResolvedBundle>> = hashMapOf()
    bundles.forEach { b ->
      val bsn = b.manifest.bundleSymbolicName?.key!!
      val ver = b.manifest.bundleVersion
      byBsn.computeIfAbsent(bsn) { TreeMap() }[ver] = b
    }
    return TargetPlatformIndex(byBsn)
  }

  @Test
  fun requireBundle_prefersWorkspace_and_selectsHighestInRangeOtherwise() {
    val target = tpIndex(
      rb("b", "1.0.0"),
      rb("b", "1.8.0")
    )
    val workspace = listOf(
      wbd("b", "1.5.0")
    )
    val entry = wbd(
      "a",
      "1.0.0",
      REQUIRE_BUNDLE to "b;bundle-version=\"[1.0.0,2.0.0)\""
    )

    // prefer workspace
    val r1 = Resolver.resolve(target, workspace, entry, ResolveOptions(preferWorkspace = true))
    val picked1 = r1.bundles.firstOrNull { it.bsn == "b" }
    assertNotNull(picked1)
    assertTrue(picked1!!.isWorkspace)
    assertEquals(Version.parseVersion("1.5.0"), picked1.version)

    // prefer target
    val r2 = Resolver.resolve(target, workspace, entry, ResolveOptions(preferWorkspace = false))
    val picked2 = r2.bundles.firstOrNull { it.bsn == "b" }
    assertNotNull(picked2)
    assertFalse(picked2!!.isWorkspace)
    assertEquals(Version.parseVersion("1.8.0"), picked2.version)
  }

  @Test
  fun reexport_closure_pulls_transitive_requirements() {
    // A requires B, B requires C with re-export
    val target = tpIndex(
      rb("b", "1.0.0", REQUIRE_BUNDLE to "c;bundle-version=\"[1.0.0,1.0.0]\";$VISIBILITY_DIRECTIVE:=$VISIBILITY_REEXPORT"),
      rb("c", "1.0.0")
    )
    val workspace = emptyList<WorkspaceBundleDescriptor>()
    val entry = wbd("a", "1.0.0", REQUIRE_BUNDLE to "b;bundle-version=\"[1.0.0,1.0.0]\"")

    val r = Resolver.resolve(target, workspace, entry)
    val bsns = r.bundles.map { it.bsn }.toSet()
    assertTrue("b must be selected", "b" in bsns)
    assertTrue("c must be pulled via re-export", "c" in bsns)
  }

  @Test
  fun transitive_require_bundle_are_included() {
    // A requires B (no re-export), B requires C (no re-export) -> expect B and C
    val target = tpIndex(
      rb("b", "1.0.0", REQUIRE_BUNDLE to "c;bundle-version=\"[1.0.0,1.0.0]\""),
      rb("c", "1.0.0")
    )
    val workspace = emptyList<WorkspaceBundleDescriptor>()
    val entry = wbd("a", "1.0.0", REQUIRE_BUNDLE to "b;bundle-version=\"[1.0.0,1.0.0]\"")

    val r = Resolver.resolve(target, workspace, entry)
    val bsns = r.bundles.map { it.bsn }.toSet()
    assertTrue("direct requirement must be included", "b" in bsns)
    assertTrue("transitive requirement must be included", "c" in bsns)
  }

  @Test
  fun importPackage_selects_provider_workspace_then_target() {
    val target = tpIndex(
      rb("y", "2.0.0", EXPORT_PACKAGE to "p;version=\"1.3.0\"")
    )
    val workspace = listOf(
      wbd("x", "1.0.0", EXPORT_PACKAGE to "p;version=\"1.2.3\"")
    )
    val entry = wbd("a", "1.0.0", IMPORT_PACKAGE to "p;version=\"[1.0.0,2.0.0)\"")

    val r1 = Resolver.resolve(target, workspace, entry, ResolveOptions(preferWorkspace = true))
    val pick1 = r1.bundles.firstOrNull { it.bsn == "x" }
    assertNotNull("workspace provider must be selected", pick1)
    assertTrue(pick1!!.isWorkspace)

    val r2 = Resolver.resolve(target, workspace, entry, ResolveOptions(preferWorkspace = false))
    val pick2 = r2.bundles.firstOrNull { it.bsn == "y" }
    assertNotNull("target provider must be selected when not preferring workspace", pick2)
    assertFalse(pick2!!.isWorkspace)
  }

  @Test
  fun fragment_host_included_first_with_flag() {
    val target = tpIndex(
      rb("host", "1.0.0")
    )
    val workspace = emptyList<WorkspaceBundleDescriptor>()
    val entry = wbd(
      "frag",
      "1.0.0",
      FRAGMENT_HOST to "host;bundle-version=\"[1.0.0,1.0.0]\""
    )

    val r = Resolver.resolve(target, workspace, entry, ResolveOptions(includeHostsForFragments = true))
    assertTrue(r.bundles.isNotEmpty())
    val first = r.bundles.first()
    assertEquals("host", first.bsn)
    assertTrue(first.isHost)
  }

  @Test
  fun whitelist_prefixes_adds_bundles() {
    val target = tpIndex(
      rb("org.eclipse.swt", "3.0.0"),
      rb("com.acme.other", "1.0.0")
    )
    val workspace = emptyList<WorkspaceBundleDescriptor>()
    val entry = wbd("a", "1.0.0")

    val r = Resolver.resolve(target, workspace, entry, ResolveOptions(whitelistPrefixes = setOf("org.eclipse.")))
    val bsns = r.bundles.map { it.bsn }.toSet()
    assertTrue("whitelisted bundle must be present", "org.eclipse.swt" in bsns)
    assertFalse("non-whitelisted bundle must not be added", "com.acme.other" in bsns)
  }
}
