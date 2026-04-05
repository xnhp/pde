package cn.varsa.pde.resolver.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EclipseRuntimeBootstrapTest {
  @Test
  fun cacheKeyChangesWhenManifestChanges() {
    val base = RuntimeManifest(
      version = "1.0.0",
      requiredIUs = listOf("a", "b"),
      defaultRuntimeZip = null,
      defaultRuntimeZipSha256 = null,
    )
    val changed = base.copy(version = "1.0.1")

    val keyA = EclipseRuntimeBootstrap.cacheKey(base, emptyList())
    val keyB = EclipseRuntimeBootstrap.cacheKey(changed, emptyList())

    assertNotEquals(keyA, keyB)
  }

  @Test
  fun cacheKeyIgnoresRepositoryOrder() {
    val manifest = RuntimeManifest(
      version = "1.0.0",
      requiredIUs = listOf("a", "b"),
      defaultRuntimeZip = null,
      defaultRuntimeZipSha256 = null,
    )

    val keyA = EclipseRuntimeBootstrap.cacheKey(manifest, listOf("https://a", "https://b"))
    val keyB = EclipseRuntimeBootstrap.cacheKey(manifest, listOf("https://b", "https://a"))

    assertEquals(keyA, keyB)
  }
}
