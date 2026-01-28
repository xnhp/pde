package cn.varsa.pde.resolver.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvMergeTest {
  @Test
  fun `mergeEnv keeps base and overrides keys`() {
    val base = mapOf("GDK_BACKEND" to "wayland", "A" to "1")
    val overrides = mapOf("GDK_BACKEND" to "x11", "B" to "2")

    val merged = mergeEnv(base, overrides)

    assertEquals(mapOf("GDK_BACKEND" to "x11", "A" to "1", "B" to "2"), merged)
  }
}
