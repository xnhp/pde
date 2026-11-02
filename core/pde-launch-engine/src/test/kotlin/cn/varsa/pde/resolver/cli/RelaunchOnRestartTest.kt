package cn.varsa.pde.resolver.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class RelaunchOnRestartTest {
  private fun runWithExits(vararg exits: Int): Pair<Int, List<Int>> {
    val attempts = mutableListOf<Int>()
    val final = runRelaunchingOnRestart { attempt ->
      attempts += attempt
      exits[attempt]
    }
    return final to attempts
  }

  @Test
  fun `a normal exit launches once`() {
    assertEquals(0 to listOf(0), runWithExits(0))
  }

  @Test
  fun `restart and relaunch codes launch again until a final exit`() {
    assertEquals(
      0 to listOf(0, 1, 2),
      runWithExits(ECLIPSE_EXIT_RESTART, ECLIPSE_EXIT_RELAUNCH, 0)
    )
  }

  @Test
  fun `a failure after a restart is returned as the final exit`() {
    assertEquals(13 to listOf(0, 1), runWithExits(ECLIPSE_EXIT_RESTART, 13))
  }
}
