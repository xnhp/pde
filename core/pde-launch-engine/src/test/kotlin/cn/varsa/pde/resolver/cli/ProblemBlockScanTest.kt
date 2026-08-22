package cn.varsa.pde.resolver.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemBlockScanTest {

  private val mixedOutput = """
    ----------
    1. WARNING in C:\ws\demo\src\Foo.java (at line 3)
    ${'\t'}String s = "hi";
    ${'\t'}           ^^^^
    Non-externalized string literal; it should be followed by //${'$'}NON-NLS-1${'$'}
    ----------
    2. ERROR in C:\ws\demo\src\Bar.java (at line 5)
    ${'\t'}int x = "no";
    ${'\t'}        ^^^^
    Type mismatch: cannot convert from String to int
    ----------
    3. INFO in C:\ws\demo\src\Baz.java (at line 7)
    ${'\t'}foo();
    ${'\t'}^^^^^
    Some informational diagnostic
    ----------
    3 problems (1 error, 1 warning, 1 info)
  """.trimIndent()

  @Test
  fun `error scan keeps only error blocks`() {
    val blocks = scanProblemBlocks(mixedOutput, setOf("ERROR"))!!

    assertTrue(blocks.contains("ERROR in"), blocks)
    assertTrue(blocks.contains("Type mismatch"), blocks)
    assertFalse(blocks.contains("Non-externalized"), blocks)
    assertFalse(blocks.contains("informational"), blocks)
  }

  @Test
  fun `warning and info scan keeps both in source order`() {
    val blocks = scanProblemBlocks(mixedOutput, setOf("WARNING", "INFO"))!!

    assertTrue(blocks.contains("Non-externalized"), blocks)
    assertTrue(blocks.contains("informational"), blocks)
    assertFalse(blocks.contains("Type mismatch"), blocks)
    assertTrue(
      blocks.indexOf("WARNING in") < blocks.indexOf("INFO in"),
      "blocks must keep source order: $blocks"
    )
  }

  @Test
  fun `no matching blocks yields null`() {
    assertNull(scanProblemBlocks("", setOf("WARNING")))
    assertNull(scanProblemBlocks("2 problems (2 errors)", setOf("WARNING", "INFO")))
  }

  @Test
  fun `single block runs to end of output when no next entry follows`() {
    val single = """
      ----------
      1. WARNING in C:\ws\demo\src\Foo.java (at line 3)
      ${'\t'}String s = "hi";
      Non-externalized string literal
      ----------
      1 problem (1 warning)
    """.trimIndent()

    val blocks = scanProblemBlocks(single, setOf("WARNING"))!!
    assertTrue(blocks.contains("Non-externalized string literal"), blocks)
  }
}
