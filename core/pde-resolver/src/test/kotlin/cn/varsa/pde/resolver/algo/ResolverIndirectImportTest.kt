package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Paths
import java.util.*

class ResolverIndirectImportTest {

  private fun navOf(vararg pairs: Pair<String, Pair<String, ResolvedBundle>>): Map<String, NavigableMap<Version, ResolvedBundle>> {
    val map = HashMap<String, NavigableMap<Version, ResolvedBundle>>()
    pairs.forEach { (bsn, pv) ->
      val (verText, rb) = pv
      map.computeIfAbsent(bsn) { TreeMap() }[Version.parseVersion(verText)] = rb
    }
    return map
  }

  private fun rb(bsn: String, path: String, manifest: BundleManifest) =
    ResolvedBundle(Paths.get(path), manifest, true)

  /** Workspace entry that Require-Bundles junit-jupiter-api (which Imports org.opentest4j). */
  private val entry = WorkspaceBundleDescriptor(
    Paths.get("/ws/org.example.tests"),
    BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.example.tests",
        "Bundle-Version" to "1.0.0",
        "Require-Bundle" to "junit-jupiter-api;bundle-version=\"[5.8.1,6.0.0)\""
      )
    )
  )

  private fun junitManifest() = BundleManifest.parse(
    mapOf(
      "Bundle-SymbolicName" to "junit-jupiter-api",
      "Bundle-Version" to "5.12.2",
      "Export-Package" to "org.junit.jupiter.api;version=\"5.12.2\"",
      "Import-Package" to "org.apiguardian.api;resolution:=optional;version=\"[1.1,2)\",org.opentest4j;version=\"[1.3,2)\""
    )
  )

  private fun opentest4jManifest(version: String) = BundleManifest.parse(
    mapOf(
      "Bundle-SymbolicName" to "org.opentest4j",
      "Bundle-Version" to version,
      "Export-Package" to "org.opentest4j;version=\"$version\""
    )
  )

  private fun apiguardianManifest() = BundleManifest.parse(
    mapOf(
      "Bundle-SymbolicName" to "org.apiguardian.api",
      "Bundle-Version" to "1.1.2",
      "Export-Package" to "org.apiguardian.api;version=\"1.1.2\""
    )
  )

  @Test
  fun pullsIndirectlyRequiredImportExporterOntoClasspath() {
    val index = TargetPlatformIndex(
      navOf(
        "junit-jupiter-api" to ("5.12.2" to rb("junit-jupiter-api", "/tp/junit", junitManifest())),
        "org.opentest4j" to ("1.3.0" to rb("org.opentest4j", "/tp/opentest4j", opentest4jManifest("1.3.0")))
      )
    )

    val result = Resolver.resolve(index, listOf(entry), entry, ResolveOptions())
    val bsns = result.bundles.map { it.bsn }.toSet()

    assertTrue("junit-jupiter-api selected (Require-Bundle)", bsns.contains("junit-jupiter-api"))
    assertTrue("org.opentest4j pulled in via junit's Import-Package", bsns.contains("org.opentest4j"))
  }

  @Test
  fun doesNotPullOptionalImports() {
    val index = TargetPlatformIndex(
      navOf(
        "junit-jupiter-api" to ("5.12.2" to rb("junit-jupiter-api", "/tp/junit", junitManifest())),
        "org.opentest4j" to ("1.3.0" to rb("org.opentest4j", "/tp/opentest4j", opentest4jManifest("1.3.0"))),
        "org.apiguardian.api" to ("1.1.2" to rb("org.apiguardian.api", "/tp/apiguardian", apiguardianManifest()))
      )
    )

    val result = Resolver.resolve(index, listOf(entry), entry, ResolveOptions())
    val bsns = result.bundles.map { it.bsn }.toSet()

    assertTrue("mandatory opentest4j present", bsns.contains("org.opentest4j"))
    assertFalse("optional apiguardian NOT pulled in", bsns.contains("org.apiguardian.api"))
  }

  @Test
  fun picksSingleHighestExporterWhenTargetHasMultipleVersions() {
    val index = TargetPlatformIndex(
      navOf(
        "junit-jupiter-api" to ("5.12.2" to rb("junit-jupiter-api", "/tp/junit", junitManifest())),
        "org.opentest4j" to ("1.3.0" to rb("org.opentest4j", "/tp/opentest4j-1.3.0", opentest4jManifest("1.3.0"))),
        "org.opentest4j" to ("1.5.0" to rb("org.opentest4j", "/tp/opentest4j-1.5.0", opentest4jManifest("1.5.0")))
      )
    )

    val result = Resolver.resolve(index, listOf(entry), entry, ResolveOptions())
    val opentest4j = result.bundles.filter { it.bsn == "org.opentest4j" }

    assertEquals("exactly one opentest4j on the classpath (no split package)", 1, opentest4j.size)
    assertEquals("highest in-range version chosen", Version.parseVersion("1.5.0"), opentest4j.single().version)
  }

  @Test
  fun pinnedVersionOverridesHighestInRange() {
    val index = TargetPlatformIndex(
      navOf(
        "junit-jupiter-api" to ("5.12.2" to rb("junit-jupiter-api", "/tp/junit", junitManifest())),
        "org.opentest4j" to ("1.3.0" to rb("org.opentest4j", "/tp/opentest4j-1.3.0", opentest4jManifest("1.3.0"))),
        "org.opentest4j" to ("1.5.0" to rb("org.opentest4j", "/tp/opentest4j-1.5.0", opentest4jManifest("1.5.0")))
      )
    )

    val result = Resolver.resolve(
      index, listOf(entry), entry,
      ResolveOptions(pinnedVersions = mapOf("org.opentest4j" to "1.3.0"))
    )
    val opentest4j = result.bundles.single { it.bsn == "org.opentest4j" }

    assertEquals("pin selects 1.3.0 over the highest 1.5.0", Version.parseVersion("1.3.0"), opentest4j.version)
  }

  @Test
  fun extraBundlesAreForceAdded() {
    val extraManifest = BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.example.extra",
        "Bundle-Version" to "2.0.0",
        "Export-Package" to "org.example.extra;version=\"2.0.0\""
      )
    )
    val index = TargetPlatformIndex(
      navOf(
        "junit-jupiter-api" to ("5.12.2" to rb("junit-jupiter-api", "/tp/junit", junitManifest())),
        "org.opentest4j" to ("1.3.0" to rb("org.opentest4j", "/tp/opentest4j", opentest4jManifest("1.3.0"))),
        "org.example.extra" to ("2.0.0" to rb("org.example.extra", "/tp/extra", extraManifest))
      )
    )

    val result = Resolver.resolve(
      index, listOf(entry), entry,
      ResolveOptions(extraBundles = listOf("org.example.extra"))
    )
    val bsns = result.bundles.map { it.bsn }.toSet()

    assertTrue("extra bundle force-added even though nothing requires it", bsns.contains("org.example.extra"))
  }

  private fun fooManifest(version: String) = BundleManifest.parse(
    mapOf(
      "Bundle-SymbolicName" to "com.foo",
      "Bundle-Version" to version,
      "Export-Package" to "com.foo;version=\"$version\""
    )
  )

  @Test
  fun extraBundlesAreNotDeduplicatedAcrossVersions() {
    // OSGi allows several versions of a non-singleton bundle; extraBundles must not collapse them.
    val index = TargetPlatformIndex(
      navOf(
        "junit-jupiter-api" to ("5.12.2" to rb("junit-jupiter-api", "/tp/junit", junitManifest())),
        "org.opentest4j" to ("1.3.0" to rb("org.opentest4j", "/tp/opentest4j", opentest4jManifest("1.3.0"))),
        "com.foo" to ("1.0.0" to rb("com.foo", "/tp/foo-1.0.0", fooManifest("1.0.0"))),
        "com.foo" to ("2.0.0" to rb("com.foo", "/tp/foo-2.0.0", fooManifest("2.0.0")))
      )
    )

    val result = Resolver.resolve(
      index, listOf(entry), entry,
      ResolveOptions(extraBundles = listOf("com.foo@1.0.0", "com.foo@2.0.0"))
    )
    val fooVersions = result.bundles.filter { it.bsn == "com.foo" }.map { it.version }.toSet()

    assertEquals(
      "both requested versions of the non-singleton bundle are present",
      setOf(Version.parseVersion("1.0.0"), Version.parseVersion("2.0.0")),
      fooVersions
    )
  }

  @Test
  fun extraBundleAddedAlongsideDifferentResolvedVersion() {
    // Dependency resolution selects com.foo 2.0.0; extraBundles forces 1.0.0 to ALSO be present.
    val appEntry = WorkspaceBundleDescriptor(
      Paths.get("/ws/org.example.app"),
      BundleManifest.parse(
        mapOf(
          "Bundle-SymbolicName" to "org.example.app",
          "Bundle-Version" to "1.0.0",
          "Require-Bundle" to "com.foo;bundle-version=\"[2.0.0,3.0.0)\""
        )
      )
    )
    val index = TargetPlatformIndex(
      navOf(
        "com.foo" to ("1.0.0" to rb("com.foo", "/tp/foo-1.0.0", fooManifest("1.0.0"))),
        "com.foo" to ("2.0.0" to rb("com.foo", "/tp/foo-2.0.0", fooManifest("2.0.0")))
      )
    )

    val result = Resolver.resolve(
      index, listOf(appEntry), appEntry,
      ResolveOptions(extraBundles = listOf("com.foo@1.0.0"))
    )
    val fooVersions = result.bundles.filter { it.bsn == "com.foo" }.map { it.version }.toSet()

    assertTrue("resolved 2.0.0 present", fooVersions.contains(Version.parseVersion("2.0.0")))
    assertTrue("forced 1.0.0 also present", fooVersions.contains(Version.parseVersion("1.0.0")))
  }
}
