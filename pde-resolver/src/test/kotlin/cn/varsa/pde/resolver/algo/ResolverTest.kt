package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Constants.BUNDLE_CLASSPATH
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
import java.nio.file.Files
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
  fun requireBundle_prefers_workspace_on_version_ties() {
    val target = tpIndex(
      rb("b", "1.0.0"),
      rb("b", "1.4.0")
    )
    val workspace = listOf(
      wbd("b", "1.4.0")
    )
    val entry = wbd(
      "a",
      "1.0.0",
      REQUIRE_BUNDLE to "b;bundle-version=\"[1.0.0,2.0.0)\""
    )

    val preferWorkspace = Resolver.resolve(target, workspace, entry, ResolveOptions(preferWorkspace = true))
    val workspacePick = preferWorkspace.bundles.firstOrNull { it.bsn == "b" }
    assertNotNull("workspace bundle with matching version should be selected", workspacePick)
    assertTrue(workspacePick!!.isWorkspace)
    assertEquals(Version.parseVersion("1.4.0"), workspacePick.version)

    val preferTarget = Resolver.resolve(target, workspace, entry, ResolveOptions(preferWorkspace = false))
    val targetPick = preferTarget.bundles.firstOrNull { it.bsn == "b" }
    assertNotNull("target bundle with matching version should be selected when workspace is not preferred", targetPick)
    assertFalse(targetPick!!.isWorkspace)
    assertEquals(Version.parseVersion("1.4.0"), targetPick.version)
  }

  @Test
  fun requireBundle_skips_workspace_versions_outside_range() {
    val target = tpIndex(
      rb("b", "1.1.0"),
      rb("b", "1.9.0"),
      rb("b", "2.4.0")
    )
    val workspace = listOf(
      wbd("b", "2.2.0")
    )
    val entry = wbd(
      "a",
      "1.0.0",
      REQUIRE_BUNDLE to "b;bundle-version=\"[1.0.0,2.0.0)\""
    )

    val result = Resolver.resolve(target, workspace, entry, ResolveOptions(preferWorkspace = true))
    val picked = result.bundles.firstOrNull { it.bsn == "b" }
    assertNotNull(picked)
    assertFalse("workspace version outside the declared range must be ignored", picked!!.isWorkspace)
    assertEquals(Version.parseVersion("1.9.0"), picked.version)
  }

  @Test
  fun requireBundle_picks_highest_version_deterministically() {
    val target = tpIndex(
      rb("b", "1.1.0"),
      rb("b", "1.5.0"),
      rb("b", "1.7.0")
    )
    val workspace = emptyList<WorkspaceBundleDescriptor>()
    val entry = wbd(
      "a",
      "1.0.0",
      REQUIRE_BUNDLE to "b;bundle-version=\"[1.0.0,2.0.0)\""
    )

    val expectedVersion = Version.parseVersion("1.7.0")
    repeat(3) { runIndex ->
      val result = Resolver.resolve(target, workspace, entry)
      val picks = result.bundles.filter { it.bsn == "b" }
      assertEquals("run \\#${runIndex + 1} must include exactly one copy of b", 1, picks.size)
      val pick = picks.single()
      assertEquals(expectedVersion, pick.version)
      assertFalse("selected bundle comes from target platform", pick.isWorkspace)
    }
  }

  @Test
  fun bundle_classpath_entries_include_embedded_jars() {
    val bundleDir = Files.createTempDirectory("resolver-bundle-classpath").toAbsolutePath().normalize()
    bundleDir.toFile().deleteOnExit()

    val libDir = bundleDir.resolve("lib")
    Files.createDirectories(libDir)
    val embeddedJar = libDir.resolve("embedded.jar")
    Files.createFile(embeddedJar)
    embeddedJar.toFile().deleteOnExit()

    val manifest = bm(
      BUNDLE_SYMBOLICNAME to "embedded.bundle",
      BUNDLE_VERSION to "1.0.0",
      BUNDLE_CLASSPATH to ".,lib/embedded.jar"
    )

    val targetBundle = ResolvedBundle(
      location = bundleDir,
      manifest = manifest,
      isDirectory = true
    )
    val target = tpIndex(targetBundle)

    val consumer = wbd(
      "consumer",
      "1.0.0",
      REQUIRE_BUNDLE to "embedded.bundle"
    )

    val result = Resolver.resolve(target, emptyList(), consumer)
    val resolved = result.bundles.firstOrNull { it.bsn == "embedded.bundle" }
    assertNotNull("embedded bundle should be resolved from target", resolved)
    val classPathEntries = resolved!!.classPathEntries.map { it.toAbsolutePath().normalize() }

    val expectedEntries = listOf(bundleDir, embeddedJar)
    assertEquals("bundle-classpath must include both base dir and embedded jar", expectedEntries, classPathEntries)
    assertEquals("class path entries should not contain duplicates", classPathEntries.size, classPathEntries.toSet().size)
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
  fun reexport_chain_pulls_all_transitive_requirements() {
    // Workspace A re-exports B, which re-exports C. Both B and C must be selected once.
    val target = tpIndex(
      rb(
        "b",
        "1.0.0",
        REQUIRE_BUNDLE to "c;bundle-version=\"[1.0.0,1.0.0]\";$VISIBILITY_DIRECTIVE:=$VISIBILITY_REEXPORT"
      ),
      rb("c", "1.0.0")
    )
    val workspace = emptyList<WorkspaceBundleDescriptor>()
    val entry = wbd(
      "a",
      "1.0.0",
      REQUIRE_BUNDLE to "b;bundle-version=\"[1.0.0,1.0.0]\";$VISIBILITY_DIRECTIVE:=$VISIBILITY_REEXPORT"
    )

    val result = Resolver.resolve(target, workspace, entry)
    val counts = result.bundles.groupingBy { it.bsn }.eachCount()

    assertEquals("b must be pulled exactly once", 1, counts["b"] ?: 0)
    assertEquals("c must be pulled exactly once via re-export chain", 1, counts["c"] ?: 0)
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
  fun optional_dependencies_are_ignored_when_missing() {
    val target = tpIndex(
      rb("mandatory.bundle", "1.0.0"),
      rb("pkg.provider", "1.0.0", EXPORT_PACKAGE to "pkg.required;version=\"1.2.0\"")
    )
    val workspace = emptyList<WorkspaceBundleDescriptor>()
    val entry = wbd(
      "a",
      "1.0.0",
      REQUIRE_BUNDLE to "mandatory.bundle;bundle-version=\"[1.0.0,1.0.0]\", optional.bundle;bundle-version=\"[1.0.0,2.0.0)\";resolution:=optional",
      IMPORT_PACKAGE to "pkg.required;version=\"[1.2.0,2.0.0)\", pkg.optional;version=\"[1.0.0,2.0.0)\";resolution:=optional"
    )

    val result = Resolver.resolve(target, workspace, entry)

    val selectedBsns = result.bundles.map { it.bsn }
    assertTrue("mandatory bundle must resolve", selectedBsns.contains("mandatory.bundle"))
    assertTrue("required package provider must resolve", selectedBsns.contains("pkg.provider"))
    assertFalse("optional bundle must not be selected", selectedBsns.contains("optional.bundle"))

    assertTrue("requires map must include mandatory bundle", result.requires.keys.contains("mandatory.bundle"))
    assertFalse("requires map must omit optional bundle", result.requires.keys.contains("optional.bundle"))
    assertTrue("imports map must include required package", result.imports.keys.contains("pkg.required"))
    assertFalse("imports map must omit optional package", result.imports.keys.contains("pkg.optional"))

    assertTrue("no unresolved entries expected", result.unresolved.isEmpty())
    assertTrue(
      "problem list must not mention optional dependencies",
      result.problems.none { it.symbol == "optional.bundle" || it.symbol == "pkg.optional" }
    )
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
  fun fragment_selects_highest_matching_host_version() {
    val target = tpIndex(
      rb("host", "1.0.0"),
      rb("host", "1.5.0"),
      rb("host", "2.2.0")
    )
    val fragment = wbd(
      "frag",
      "1.0.0",
      FRAGMENT_HOST to "host;bundle-version=\"[1.0.0,2.0.0)\""
    )
    val workspace = listOf(fragment)

    val result = Resolver.resolve(target, workspace, fragment)

    val hosts = result.bundles.filter { it.bsn == "host" }
    assertEquals("exactly one host bundle must be selected", 1, hosts.size)
    val host = hosts.single()
    assertEquals(Version.parseVersion("1.5.0"), host.version)
    assertTrue("selected host must be marked as host", host.isHost)

    val hostIndex = result.bundles.indexOf(host)
    val fragmentIndex = result.bundles.indexOfFirst { it.bsn == "frag" }
    assertTrue(
      "resolver must emit host before fragment",
      hostIndex >= 0 && fragmentIndex >= 0 && hostIndex < fragmentIndex
    )
  }

  @Test
  fun fragment_inherits_host_dependencies() {
    val target = tpIndex(
      rb(
        "host",
        "1.0.0",
        REQUIRE_BUNDLE to "org.example.require;bundle-version=\"[1.0.0,2.0.0)\", org.example.reexport;bundle-version=\"[1.0.0,2.0.0)\";${VISIBILITY_DIRECTIVE}:=${VISIBILITY_REEXPORT}",
        IMPORT_PACKAGE to "org.example.hostpkg;version=\"[1.0.0,2.0.0)\""
      ),
      rb("org.example.require", "1.1.0"),
      rb(
        "org.example.reexport",
        "1.0.0",
        REQUIRE_BUNDLE to "org.example.transitive;bundle-version=\"[1.0.0,2.0.0)\""
      ),
      rb("org.example.transitive", "1.0.0"),
      rb("org.example.pkgprovider", "1.0.0", EXPORT_PACKAGE to "org.example.hostpkg;version=\"1.5.0\"")
    )
    val fragment = wbd(
      "fragment",
      "1.0.0",
      FRAGMENT_HOST to "host;bundle-version=\"[1.0.0,1.0.0]\""
    )
    val workspace = listOf(fragment)

    val result = Resolver.resolve(target, workspace, fragment, ResolveOptions(includeHostsForFragments = true))
    val bsns = result.bundles.map { it.bsn }

    assertTrue("host bundle present", bsns.contains("host"))
    assertTrue("host require-bundle dependency present", bsns.contains("org.example.require"))
    assertTrue("host re-export dependency pulled in", bsns.contains("org.example.reexport"))
    assertTrue("transitive dependency of re-export present", bsns.contains("org.example.transitive"))
    assertTrue("host import-package provider present", bsns.contains("org.example.pkgprovider"))
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

  @Test
  fun importPackage_target_provider_prefers_acceptable_export_version() {
    // Two target bundles export same package with different export versions.
    // Range excludes the higher bundle's export; must pick acceptable one.
    val target = tpIndex(
      rb("y", "3.0.0", EXPORT_PACKAGE to "p;version=\"1.2.0\""),
      rb("z", "5.0.0", EXPORT_PACKAGE to "p;version=\"1.1.0\"")
    )
    val workspace = emptyList<WorkspaceBundleDescriptor>()
    val entry = wbd("a", "1.0.0", IMPORT_PACKAGE to "p;version=\"[1.2.0,2.0.0)\"")

    val r = Resolver.resolve(target, workspace, entry, ResolveOptions(preferWorkspace = false))
    val bsns = r.bundles.map { it.bsn }
    assertTrue(bsns.contains("y"))
    assertFalse(bsns.contains("z"))
  }

  @Test
  fun whitelist_does_not_duplicate_already_selected_bsn() {
    val target = tpIndex(
      rb("org.eclipse.swt", "3.0.0"),
      rb("b", "1.0.0")
    )
    val workspace = listOf(
      wbd("b", "1.0.0")
    )
    val entry = wbd("a", "1.0.0", REQUIRE_BUNDLE to "b;bundle-version=\"[1.0.0,2.0.0)\"")

    val r = Resolver.resolve(target, workspace, entry, ResolveOptions(whitelistPrefixes = setOf("org.eclipse.")))
    val bsns = r.bundles.map { it.bsn }
    // 'b' selected once via workspace; 'org.eclipse.swt' added via whitelist
    assertEquals(bsns.toSet().size, bsns.size)
    assertTrue(bsns.contains("org.eclipse.swt"))
  }
}
