package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Paths
import java.util.TreeMap

class ResolverTraversalTest {
  @Test
  fun `require closure terminates cycles and expands shared dependencies once`() {
    fun manifest(bsn: String, requires: String) = BundleManifest.parse(
      mapOf("Bundle-SymbolicName" to bsn, "Bundle-Version" to "1.0.0", "Require-Bundle" to requires)
    )
    val entry = WorkspaceBundleDescriptor(Paths.get("/ws/entry"), manifest("entry", "left,right"))
    val target = TargetPlatformIndex(
      listOf("left" to "shared", "right" to "shared", "shared" to "left,entry,leaf", "leaf" to "")
        .associate { (bsn, requires) ->
          bsn to TreeMap<Version, ResolvedBundle>().apply {
            put(Version("1.0.0"), ResolvedBundle(Paths.get("/tp/$bsn"), manifest(bsn, requires), true))
          }
        }
    )

    val result = Resolver.resolve(target, listOf(entry), entry)

    assertEquals(setOf("entry", "left", "right", "shared", "leaf"), result.bundles.map { it.bsn }.toSet())
    assertEquals(5, result.bundles.size)
    assertTrue(result.unresolved.isEmpty())
  }
}
