package cn.varsa.pde.resolver.manifest

import org.junit.Assert.*
import org.junit.Test

class BundleManifestParametersTrimTest {
  @Test
  fun requireBundle_itemsAreTrimmed() {
    val mf = BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.example.app",
        "Bundle-Version" to "1.0.0",
        // Intentionally add a space after comma to mimic typical formatting/wrapping
        "Require-Bundle" to "org.foo.api;bundle-version=\"[1.0.0,2.0.0)\", org.bar.core;bundle-version=\"[2.0.0,3.0.0)\""
      )
    )

    val requires = mf.requiredBundleAndVersion()
    assertTrue(requires.containsKey("org.foo.api"))
    assertTrue(requires.containsKey("org.bar.core"))
  }
}

