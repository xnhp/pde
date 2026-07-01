package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class ArgumentParsingTest {
  @Test
  fun preservesYamlListEntriesAsAtomicArgs() {
    val tokens = expandArgs(listOf("-testpluginname org.knime.gateway.impl"))
    assertEquals(listOf("-testpluginname org.knime.gateway.impl"), tokens)
  }

  @Test
  fun preservesSpacesWithoutQuoteProcessing() {
    val tokens = expandArgs(listOf("--include=^(?!.*/File Handling v2/).*"))
    assertEquals(listOf("--include=^(?!.*/File Handling v2/).*"), tokens)
  }

  @Test
  fun ignoresBlankEntries() {
    val tokens = expandArgs(listOf("   "))
    assertEquals(emptyList<String>(), tokens)
  }
}
