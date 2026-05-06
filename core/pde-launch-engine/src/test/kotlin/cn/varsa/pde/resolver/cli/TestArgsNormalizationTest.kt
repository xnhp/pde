package cn.varsa.pde.resolver.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class TestArgsNormalizationTest {

  @Test
  fun `captures multiple requested test names`() {
    val normalized = normalizeTestArgs(arrayOf("myA", "myB"))

    assertEquals(listOf("myA", "myB"), normalized.requestedTests)
    assertEquals(emptyList(), normalized.parserArgs.toList())
  }

  @Test
  fun `keeps positional yaml for parser and strips it from requested tests`() {
    val normalized = normalizeTestArgs(arrayOf("pde.yaml", "myA", "myB"))

    assertEquals(listOf("myA", "myB"), normalized.requestedTests)
    assertEquals(listOf("pde.yaml"), normalized.parserArgs.toList())
  }

  @Test
  fun `does not treat yaml as config when --config is present`() {
    val normalized = normalizeTestArgs(arrayOf("--config", "launch.yaml", "pde.yaml", "myA"))

    assertEquals(listOf("pde.yaml", "myA"), normalized.requestedTests)
    assertEquals(listOf("--config", "launch.yaml"), normalized.parserArgs.toList())
  }
}
