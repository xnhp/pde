package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class ArgumentParsingTest {
  @Test
  fun splitsWhitespaceSeparatedArgs() {
    val tokens = tokenizeArgString("-testpluginname org.knime.gateway.impl")
    assertEquals(listOf("-testpluginname", "org.knime.gateway.impl"), tokens)
  }

  @Test
  fun preservesQuotedSegments() {
    val tokens = tokenizeArgString("-Dfoo=\"a b\" 'single quoted'")
    assertEquals(listOf("-Dfoo=a b", "single quoted"), tokens)
  }

  @Test
  fun ignoresBlankEntries() {
    val tokens = tokenizeArgString("   ")
    assertEquals(emptyList<String>(), tokens)
  }
}
