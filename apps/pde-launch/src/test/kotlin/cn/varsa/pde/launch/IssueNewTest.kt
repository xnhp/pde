package cn.varsa.pde.launch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueNewTest {

  @Test
  fun `branch name is capped to 50 chars`() {
    val method = Class.forName("cn.varsa.pde.launch.IssueNewKt")
      .getDeclaredMethod("formatBranchName", String::class.java, String::class.java, String::class.java)
      .apply { isAccessible = true }

    val prefix = "issue"
    val issueId = "PDE-1234"
    val base = "${issueId}-${"a".repeat(80)}"
    val result = method.invoke(null, prefix, issueId, base) as String

    assertTrue(result.length <= 50, "Expected branch name length <= 50, got ${result.length}")
    assertTrue(result.startsWith("${prefix}/${issueId}"))
  }

  @Test
  fun `prefix is truncated when it exceeds limit`() {
    val method = Class.forName("cn.varsa.pde.launch.IssueNewKt")
      .getDeclaredMethod("formatBranchName", String::class.java, String::class.java, String::class.java)
      .apply { isAccessible = true }

    val prefix = "a".repeat(60)
    val issueId = "PDE-1"
    val base = issueId
    val result = method.invoke(null, prefix, issueId, base) as String

    assertEquals(prefix.take(50), result)
  }
}
