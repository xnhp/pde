package cn.varsa.pde.resolver.algo

import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Paths

class BundleSelectionAccumulatorTest {
  private fun resolved(
    bsn: String,
    version: String,
    workspace: Boolean,
    pathSuffix: String = version
  ): ResolvedBundle = ResolvedBundle(
    bsn = bsn,
    version = Version.parseVersion(version),
    path = Paths.get("/$bsn/$pathSuffix"),
    origin = if (workspace) BundleOrigin.WORKSPACE else BundleOrigin.TARGET,
    classPathEntries = listOf(Paths.get("/$bsn/$pathSuffix")),
    sourceEntries = emptyList(),
    fragmentHost = null,
    isHost = false
  )

  @Test
  fun prefersWorkspaceBundles() {
    val acc = BundleSelectionAccumulator()
    acc.register(resolved("a", "1.0.0", workspace = false))
    acc.register(resolved("a", "1.0.1", workspace = true))
    acc.register(resolved("a", "1.1.0", workspace = false))

    val entries = acc.entries()
    assertEquals(1, entries.size)
    assertTrue(entries.first().isWorkspace)
    assertEquals(Version.parseVersion("1.0.1"), entries.first().version)
  }

  @Test
  fun suppressesDuplicateTargetEntries() {
    val acc = BundleSelectionAccumulator()
    acc.register(resolved("b", "1.0.0", workspace = false))
    acc.register(resolved("b", "1.0.0", workspace = false))

    assertEquals(1, acc.entries().size)
  }

  @Test
  fun retainsMultipleTargetVersionsWhenDistinct() {
    val acc = BundleSelectionAccumulator()
    acc.register(resolved("c", "1.0.0", workspace = false, pathSuffix = "v1"))
    acc.register(resolved("c", "1.1.0", workspace = false, pathSuffix = "v2"))

    val versions = acc.entries().map { it.version }.toSet()
    assertEquals(setOf(Version.parseVersion("1.0.0"), Version.parseVersion("1.1.0")), versions)
  }

  @Test
  fun ensureBundleUsesProviderWhenMissing() {
    val acc = BundleSelectionAccumulator()
    val provided = resolved("d", "2.0.0", workspace = false)
    val result = acc.ensureBundle("d") { provided }
    assertTrue(result)
    assertTrue(acc.contains("d"))
  }

  @Test
  fun ensureBundleReturnsFalseWhenProviderMissing() {
    val acc = BundleSelectionAccumulator()
    val result = acc.ensureBundle("missing") { null }
    assertFalse(result)
    assertFalse(acc.contains("missing"))
  }
}
