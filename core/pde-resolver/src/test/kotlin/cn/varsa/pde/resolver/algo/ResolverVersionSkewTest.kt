package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Paths
import java.util.*

class ResolverVersionSkewTest {

  private fun navOf(
    vararg pairs: Pair<String, Pair<String, ResolvedBundle>>
  ): Map<String, NavigableMap<Version, ResolvedBundle>> {
    val map = HashMap<String, NavigableMap<Version, ResolvedBundle>>()
    pairs.forEach { (bsn, pv) ->
      val (verText, rb) = pv
      map.computeIfAbsent(bsn) { TreeMap() }[Version.parseVersion(verText)] = rb
    }
    return map
  }

  @Test
  fun `entry's own require-bundle range wins over a wider transitive range`() {
    // Version skew: the entry requires com.foo [1.0,2.0), while a closure member (mid) requires
    // com.foo [2.0,3.0). The target ships both 1.0 and 2.0. Phase-1 pre-selection must lock the
    // entry's own 1.0 before mid's closure would otherwise pull the higher 2.0. `mid` is declared
    // first so a single-phase closure would expose the bug (select 2.0 before 1.0 is seen).
    val wsManifest = BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.example.app",
        "Bundle-Version" to "1.0.0",
        "Require-Bundle" to
          "org.example.mid;bundle-version=\"[1.0.0,2.0.0)\", com.foo;bundle-version=\"[1.0.0,2.0.0)\""
      )
    )
    val workspace = listOf(WorkspaceBundleDescriptor(Paths.get("/ws/org.example.app"), wsManifest))

    val midManifest = BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.example.mid",
        "Bundle-Version" to "1.0.0",
        "Require-Bundle" to "com.foo;bundle-version=\"[2.0.0,3.0.0)\""
      )
    )
    val foo1 = BundleManifest.parse(mapOf("Bundle-SymbolicName" to "com.foo", "Bundle-Version" to "1.0.0"))
    val foo2 = BundleManifest.parse(mapOf("Bundle-SymbolicName" to "com.foo", "Bundle-Version" to "2.0.0"))
    val midRb = ResolvedBundle(Paths.get("/tp/plugins/org.example.mid"), midManifest, true)
    val foo1Rb = ResolvedBundle(Paths.get("/tp/plugins/com.foo_1"), foo1, true)
    val foo2Rb = ResolvedBundle(Paths.get("/tp/plugins/com.foo_2"), foo2, true)
    val tpIndex = TargetPlatformIndex(
      navOf(
        "org.example.mid" to ("1.0.0" to midRb),
        "com.foo" to ("1.0.0" to foo1Rb),
        "com.foo" to ("2.0.0" to foo2Rb)
      )
    )

    val result = Resolver.resolve(tpIndex, workspace, workspace.first(), ResolveOptions())
    val foo = result.bundles.first { it.bsn == "com.foo" }
    assertEquals("entry's own [1.0,2.0) must win", Version.parseVersion("1.0.0"), foo.version)
  }
}
