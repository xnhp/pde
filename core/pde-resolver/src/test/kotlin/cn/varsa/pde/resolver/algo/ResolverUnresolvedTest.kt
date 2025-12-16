package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.VersionRange
import java.nio.file.Paths

class ResolverUnresolvedTest {

  @Test
  fun reportsMissingRequireBundle() {
    val ws = listOf(
      WorkspaceBundleDescriptor(
        Paths.get("/ws/app"),
        BundleManifest.parse(
          mapOf(
            "Bundle-SymbolicName" to "org.example.app",
            "Bundle-Version" to "1.0.0",
            "Require-Bundle" to "org.missing.dep;bundle-version=\"[1.0.0,2.0.0)\""
          )
        )
      )
    )

    val emptyIndex = TargetPlatformIndex(emptyMap())

    val result = Resolver.resolve(emptyIndex, ws, ws.first(), ResolveOptions())

    assertTrue(result.unresolved.isNotEmpty())
    val u = result.unresolved.first()
    assertEquals("org.missing.dep", u.bsn)
    assertEquals("require-bundle", u.reason)
    // defensive: range text equality is enough for unit intent
    assertEquals(VersionRange("[1.0.0,2.0.0)").toString(), u.range?.toString())
  }

  @Test
  fun reportsMissingFragmentHost() {
    val ws = listOf(
      WorkspaceBundleDescriptor(
        Paths.get("/ws/fragment"),
        BundleManifest.parse(
          mapOf(
            "Bundle-SymbolicName" to "org.example.fragment",
            "Bundle-Version" to "1.0.0",
            "Fragment-Host" to "org.missing.host;bundle-version=\"[2.0.0,3.0.0)\""
          )
        )
      )
    )

    val emptyIndex = TargetPlatformIndex(emptyMap())

    val result = Resolver.resolve(emptyIndex, ws, ws.first(), ResolveOptions(includeHostsForFragments = true))

    assertTrue(result.unresolved.isNotEmpty())
    val u = result.unresolved.first()
    assertEquals("org.missing.host", u.bsn)
    assertEquals("fragmentHost", u.reason)
    assertEquals(VersionRange("[2.0.0,3.0.0)").toString(), u.range?.toString())
  }
}

