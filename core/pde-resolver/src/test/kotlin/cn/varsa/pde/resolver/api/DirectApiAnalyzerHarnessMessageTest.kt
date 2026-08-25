package cn.varsa.pde.resolver.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class DirectApiAnalyzerHarnessMessageTest {
  private val pdeOriginal = "API analysis aborted: org.knime.foo has unresolved constraints: Require-Bundle: org.knime.bar_[5.0.0,6.0.0)"

  @Test
  fun `component resolution problem is reworded to say analysis continued`() {
    val message = DirectApiAnalyzerHarness.renderProblemMessage(
      DirectApiAnalyzerHarness.CATEGORY_API_COMPONENT_RESOLUTION,
      listOf("org.knime.foo", "Require-Bundle: org.knime.bar_[5.0.0,6.0.0)"),
      pdeOriginal
    )
    assertEquals(
      "Component resolution incomplete for org.knime.foo: unresolved constraints: Require-Bundle: org.knime.bar_[5.0.0,6.0.0). " +
        "Compatibility, version, @since and usage analysis continued for the whole bundle; " +
        "findings that involve types from the unresolved bundles may be missing or incomplete.",
      message
    )
    assertFalse(message.contains("aborted"))
  }

  @Test
  fun `other categories keep the PDE message`() {
    val original = "The type Foo has been removed"
    assertEquals(original, DirectApiAnalyzerHarness.renderProblemMessage(DirectApiAnalyzerHarness.CATEGORY_COMPATIBILITY, listOf("Foo"), original))
  }

  @Test
  fun `falls back to PDE message when arguments are missing`() {
    val message = DirectApiAnalyzerHarness.renderProblemMessage(DirectApiAnalyzerHarness.CATEGORY_API_COMPONENT_RESOLUTION, listOf("org.knime.foo"), pdeOriginal)
    assertSame(pdeOriginal, message)
  }
}
