package cn.varsa.pde.resolver

import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.pde.resolver.support.parseVersionRange
import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Constants
import cn.varsa.pde.resolver.support.unquote

class BundleManifestParseTest {
  @Test
  fun parseBasicHeaders() {
    val map = mapOf(
      Constants.BUNDLE_SYMBOLICNAME to "com.example.demo",
      Constants.BUNDLE_VERSION to "1.2.3",
      Constants.REQUIRE_BUNDLE to "org.foo;bundle-version=\"[1.0.0,2.0.0)\";visibility:=reexport",
      Constants.IMPORT_PACKAGE to "a.b.c;version=\"[1.0,2.0)\""
    )

    val bm = BundleManifest.parse(map)

    assertEquals("com.example.demo", bm.bundleSymbolicName?.key)
    assertEquals("1.2.3", bm.bundleVersion.toString())

    val req = bm.requireBundle!!["org.foo"]
    assertNotNull(req)
    assertEquals("[1.0.0,2.0.0)", req!!.attribute[Constants.BUNDLE_VERSION_ATTRIBUTE]?.unquote())
    assertEquals("reexport", req.directive[Constants.VISIBILITY_DIRECTIVE])

    val imp = bm.importPackage!!["a.b.c"]
    assertNotNull(imp)
    assertEquals("[1.0,2.0)", imp!!.attribute[Constants.VERSION_ATTRIBUTE]?.unquote())

    // Ensure version range text parses as expected
    val rangeText = req.attribute[Constants.BUNDLE_VERSION_ATTRIBUTE]
    val range = rangeText.parseVersionRange()
    assertTrue(range.includes(org.osgi.framework.Version.parseVersion("1.5.0")))
  }
}
