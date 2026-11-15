package cn.varsa.pde.resolver.manifest

import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Version

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

  @Test
  fun exportPackage_keepsEveryVersionOfARepeatedPackage() {
    val mf = BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.apache.commons.commons-io",
        "Bundle-Version" to "2.19.0",
        "Export-Package" to
          "org.apache.commons.io;version=\"1.4.9999\",org.apache.commons.io.output;version=\"1.4.9999\"," +
          "org.apache.commons.io;version=\"2.19.0\""
      )
    )

    val versions = mf.exportedPackageVersions()
    assertEquals(
      listOf(Version("1.4.9999"), Version("2.19.0")),
      versions["org.apache.commons.io"]
    )
    assertEquals(listOf(Version("1.4.9999")), versions["org.apache.commons.io.output"])
  }
}

