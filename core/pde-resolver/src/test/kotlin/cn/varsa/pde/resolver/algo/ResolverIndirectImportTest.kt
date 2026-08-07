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

  private fun bm(vararg pairs: Pair<String, String>) = BundleManifest.parse(mapOf(*pairs))

  private fun tpBundle(bsn: String, vararg headers: Pair<String, String>): ResolvedBundle {
    val manifest = bm(*headers)
    return ResolvedBundle(Paths.get("/tp/plugins/$bsn"), manifest, true)
  }

  private fun tpIndex(vararg bundles: ResolvedBundle): TargetPlatformIndex {
    val map = HashMap<String, NavigableMap<Version, ResolvedBundle>>()
    bundles.forEach { rb ->
      val bsn = rb.manifest.bundleSymbolicName?.key ?: error("fixture bundle without BSN")
      map.computeIfAbsent(bsn) { TreeMap() }[rb.manifest.bundleVersion] = rb
    }
    return TargetPlatformIndex(map)
  }

  private fun wsEntry(vararg headers: Pair<String, String>): List<WorkspaceBundleDescriptor> {
    val manifest = bm(*headers)
    val bsn = manifest.bundleSymbolicName?.key ?: error("fixture entry without BSN")
    return listOf(WorkspaceBundleDescriptor(Paths.get("/ws/$bsn"), manifest))
  }

  @Test
  fun `selects exporter of a package imported only by a require-bundle-closure member`() {
    // The entry requires jupiter-api (a target bundle) which Import-Packages org.opentest4j but
    // does not export or require it. That exporter is reachable only via the closure member's
    // import, so it is selected only if the requirements-closure fixpoint follows the imports of
    // selected bundles, not just the entry's own.
    val workspace = wsEntry(
      "Bundle-SymbolicName" to "org.example.tests",
      "Bundle-Version" to "1.0.0",
      "Require-Bundle" to "org.junit.jupiter.api;bundle-version=\"[5.0.0,6.0.0)\""
    )
    val tp = tpIndex(
      tpBundle(
        "org.junit.jupiter.api",
        "Bundle-SymbolicName" to "org.junit.jupiter.api",
        "Bundle-Version" to "5.10.0",
        "Export-Package" to "org.junit.jupiter.api",
        "Import-Package" to "org.opentest4j"
      ),
      tpBundle(
        "org.opentest4j",
        "Bundle-SymbolicName" to "org.opentest4j",
        "Bundle-Version" to "1.3.0",
        "Export-Package" to "org.opentest4j"
      )
    )

    val result = Resolver.resolve(tp, workspace, workspace.first(), ResolveOptions())
    assertTrue(
      "closure member's imported exporter must be selected",
      result.bundles.any { it.bsn == "org.opentest4j" }
    )
  }

  @Test
  fun `unrelated selected exporter does not mask the package's actual provider`() {
    // org.a (selected via Require-Bundle) exports p at 1.0. org.b imports p [2.0,3.0), whose only
    // in-range exporter is org.c. A "package already provided by the selection" shortcut would
    // skip org.c because org.a exports p; the import must instead follow the provider that
    // actually satisfies the range (Eclipse PDE follows the wire to the actual exporter).
    val workspace = wsEntry(
      "Bundle-SymbolicName" to "org.example.app",
      "Bundle-Version" to "1.0.0",
      "Require-Bundle" to "org.a;bundle-version=\"[1.0.0,2.0.0)\", org.b;bundle-version=\"[1.0.0,2.0.0)\""
    )
    val tp = tpIndex(
      tpBundle(
        "org.a",
        "Bundle-SymbolicName" to "org.a",
        "Bundle-Version" to "1.0.0",
        "Export-Package" to "p;version=\"1.0.0\""
      ),
      tpBundle(
        "org.b",
        "Bundle-SymbolicName" to "org.b",
        "Bundle-Version" to "1.0.0",
        "Import-Package" to "p;version=\"[2.0.0,3.0.0)\""
      ),
      tpBundle(
        "org.c",
        "Bundle-SymbolicName" to "org.c",
        "Bundle-Version" to "1.0.0",
        "Export-Package" to "p;version=\"2.5.0\""
      )
    )

    val result = Resolver.resolve(tp, workspace, workspace.first(), ResolveOptions())
    val bsns = result.bundles.map { it.bsn }.toSet()
    assertTrue("the in-range provider must be selected despite org.a exporting p", "org.c" in bsns)
    assertTrue("org.a stays selected", "org.a" in bsns)
    assertTrue("no unresolved import for p", result.unresolved.none { it.bsn == "p" })
  }

  @Test
  fun `same-version exporters of one package do not shadow each other`() {
    // org.x and org.y have the SAME bundle version and both export p, but only org.y's export
    // version satisfies the import range. A provider map keyed by bundle version would keep only
    // one of the two (hash-order dependent) and could lose org.y entirely.
    val workspace = wsEntry(
      "Bundle-SymbolicName" to "org.example.app",
      "Bundle-Version" to "1.0.0",
      "Import-Package" to "p;version=\"[2.0.0,3.0.0)\""
    )
    val tp = tpIndex(
      tpBundle(
        "org.x",
        "Bundle-SymbolicName" to "org.x",
        "Bundle-Version" to "1.0.0",
        "Export-Package" to "p;version=\"1.0.0\""
      ),
      tpBundle(
        "org.y",
        "Bundle-SymbolicName" to "org.y",
        "Bundle-Version" to "1.0.0",
        "Export-Package" to "p;version=\"2.0.0\""
      )
    )

    val result = Resolver.resolve(tp, workspace, workspace.first(), ResolveOptions())
    assertTrue("the exporter satisfying the range must win", result.bundles.any { it.bsn == "org.y" })
    assertTrue("no unresolved import for p", result.unresolved.none { it.bsn == "p" })
  }

  @Test
  fun `optional imports of closure members are followed, unsatisfiable ones stay silent`() {
    // Eclipse PDE's build closure runs with INCLUDE_OPTIONAL_DEPENDENCIES: an optional import
    // whose provider exists lands on the classpath; an unsatisfiable optional import is skipped
    // without recording a problem.
    val workspace = wsEntry(
      "Bundle-SymbolicName" to "org.example.app",
      "Bundle-Version" to "1.0.0",
      "Require-Bundle" to "org.m;bundle-version=\"[1.0.0,2.0.0)\""
    )
    val tp = tpIndex(
      tpBundle(
        "org.m",
        "Bundle-SymbolicName" to "org.m",
        "Bundle-Version" to "1.0.0",
        "Import-Package" to
          "p;version=\"[1.0.0,2.0.0)\";resolution:=optional, q;version=\"[1.0.0,2.0.0)\";resolution:=optional"
      ),
      tpBundle(
        "org.p.provider",
        "Bundle-SymbolicName" to "org.p.provider",
        "Bundle-Version" to "1.0.0",
        "Export-Package" to "p;version=\"1.5.0\""
      )
      // q has no provider anywhere.
    )

    val result = Resolver.resolve(tp, workspace, workspace.first(), ResolveOptions())
    assertTrue("satisfiable optional import is followed", result.bundles.any { it.bsn == "org.p.provider" })
    assertTrue("unsatisfiable optional import records no problem", result.unresolved.none { it.bsn == "q" })
  }

  @Test
  fun `import-added bundles bring their own require-bundle closure`() {
    // org.c enters the selection as an Import-Package provider; its Require-Bundle dependency
    // org.d must be expanded too (fixpoint over requires, not only imports).
    val workspace = wsEntry(
      "Bundle-SymbolicName" to "org.example.app",
      "Bundle-Version" to "1.0.0",
      "Import-Package" to "p;version=\"[1.0.0,2.0.0)\""
    )
    val tp = tpIndex(
      tpBundle(
        "org.c",
        "Bundle-SymbolicName" to "org.c",
        "Bundle-Version" to "1.0.0",
        "Export-Package" to "p;version=\"1.0.0\"",
        "Require-Bundle" to "org.d;bundle-version=\"[1.0.0,2.0.0)\""
      ),
      tpBundle(
        "org.d",
        "Bundle-SymbolicName" to "org.d",
        "Bundle-Version" to "1.0.0"
      )
    )

    val result = Resolver.resolve(tp, workspace, workspace.first(), ResolveOptions())
    val bsns = result.bundles.map { it.bsn }.toSet()
    assertTrue("provider itself selected", "org.c" in bsns)
    assertTrue("provider's required bundle selected", "org.d" in bsns)
  }

  @Test
  fun `a fragment selected as provider pulls its host`() {
    // org.frag exports p but is a fragment of org.host; without the host its classes cannot be
    // loaded, so the host must join the selection (fixpoint over Fragment-Host).
    val workspace = wsEntry(
      "Bundle-SymbolicName" to "org.example.app",
      "Bundle-Version" to "1.0.0",
      "Import-Package" to "p;version=\"[1.0.0,2.0.0)\""
    )
    val tp = tpIndex(
      tpBundle(
        "org.frag",
        "Bundle-SymbolicName" to "org.frag",
        "Bundle-Version" to "1.0.0",
        "Fragment-Host" to "org.host;bundle-version=\"[1.0.0,2.0.0)\"",
        "Export-Package" to "p;version=\"1.0.0\""
      ),
      tpBundle(
        "org.host",
        "Bundle-SymbolicName" to "org.host",
        "Bundle-Version" to "1.0.0"
      )
    )

    val result = Resolver.resolve(tp, workspace, workspace.first(), ResolveOptions())
    val bsns = result.bundles.map { it.bsn }.toSet()
    assertTrue("fragment provider selected", "org.frag" in bsns)
    assertTrue("fragment's host selected", "org.host" in bsns)
  }
}
