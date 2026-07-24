package cn.varsa.pde.resolver.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ApiFilterRuleTest {

  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test
  fun `exact match on all fields`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = "src/org/example/Type.java",
      problemId = 12345,
      messageArguments = listOf("arg1", "arg2")
    )
    assertTrue(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = "src/org/example/Type.java",
        messageArguments = listOf("arg1", "arg2")
      )
    )
  }

  @Test
  fun `FQN normalization - filter simple name matches problem FQN`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = null,
      problemId = 12345,
      messageArguments = listOf("foo")
    )
    assertTrue(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = null,
        messageArguments = listOf("com.example.foo")
      )
    )
  }

  @Test
  fun `FQN normalization - filter FQN does not match problem simple name`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = null,
      problemId = 12345,
      messageArguments = listOf("com.example.foo")
    )
    assertFalse(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = null,
        messageArguments = listOf("foo")
      )
    )
  }

  @Test
  fun `FQN normalization - both have dots uses strict equality`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = null,
      problemId = 12345,
      messageArguments = listOf("com.example.foo")
    )
    assertFalse(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = null,
        messageArguments = listOf("com.other.foo")
      )
    )
  }

  @Test
  fun `path match - both non-null and equal`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = "src/org/example/Type.java",
      problemId = 12345,
      messageArguments = listOf("arg1")
    )
    assertTrue(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = "src/org/example/Type.java",
        messageArguments = listOf("arg1")
      )
    )
  }

  @Test
  fun `path match - both non-null and different`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = "src/org/example/Type.java",
      problemId = 12345,
      messageArguments = listOf("arg1")
    )
    assertFalse(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = "src/org/example/Other.java",
        messageArguments = listOf("arg1")
      )
    )
  }

  @Test
  fun `path match - filter has path, problem has null - path check skipped`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = "src/org/example/Type.java",
      problemId = 12345,
      messageArguments = listOf("arg1")
    )
    assertTrue(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = null,
        messageArguments = listOf("arg1")
      )
    )
  }

  @Test
  fun `path match - filter has null path - path check skipped`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = null,
      problemId = 12345,
      messageArguments = listOf("arg1")
    )
    assertTrue(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = "src/org/example/Whatever.java",
        messageArguments = listOf("arg1")
      )
    )
  }

  @Test
  fun `no match - different problemId`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = null,
      problemId = 12345,
      messageArguments = listOf("arg1")
    )
    assertFalse(
      rule.matches(
        problemId = 99999,
        typeName = "org.example.Type",
        resourcePath = null,
        messageArguments = listOf("arg1")
      )
    )
  }

  @Test
  fun `no match - different typeName`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = null,
      problemId = 12345,
      messageArguments = listOf("arg1")
    )
    assertFalse(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Other",
        resourcePath = null,
        messageArguments = listOf("arg1")
      )
    )
  }

  @Test
  fun `no match - different argument count`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = null,
      problemId = 12345,
      messageArguments = listOf("arg1")
    )
    assertFalse(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = null,
        messageArguments = listOf("arg1", "arg2")
      )
    )
  }

  @Test
  fun `no match - different argument value without FQN normalization`() {
    val rule = ApiFilterRule(
      typeName = "org.example.Type",
      path = null,
      problemId = 12345,
      messageArguments = listOf("foo")
    )
    assertFalse(
      rule.matches(
        problemId = 12345,
        typeName = "org.example.Type",
        resourcePath = null,
        messageArguments = listOf("bar")
      )
    )
  }

  @Test
  fun `argumentsEqual - exact match`() {
    assertTrue(ApiFilterRule.argumentsEqual("foo", "foo"))
  }

  @Test
  fun `argumentsEqual - filter simple name matches problem FQN`() {
    assertTrue(ApiFilterRule.argumentsEqual("foo", "com.example.foo"))
  }

  @Test
  fun `argumentsEqual - filter FQN does not match problem simple name`() {
    assertFalse(ApiFilterRule.argumentsEqual("com.example.foo", "foo"))
  }

  @Test
  fun `argumentsEqual - both have dots - strict equality`() {
    assertFalse(ApiFilterRule.argumentsEqual("com.example.foo", "com.other.foo"))
  }

  @Test
  fun `argumentsEqual - both have dots - equal`() {
    assertTrue(ApiFilterRule.argumentsEqual("com.example.foo", "com.example.foo"))
  }

  @Test
  fun `load parses api_filters XML file`() {
    val xml = """
      <?xml version="1.0" encoding="UTF-8"?>
      <component id="org.example.bundle" version="2">
        <resource type="org.example.Type" path="src/org/example/Type.java">
          <filter id="12345">
            <message_arguments>
              <message_argument value="arg1"/>
              <message_argument value="arg2"/>
            </message_arguments>
          </filter>
        </resource>
        <resource type="org.example.Other">
          <filter id="67890">
            <message_arguments>
              <message_argument value="simple"/>
            </message_arguments>
          </filter>
        </resource>
      </component>
    """.trimIndent()
    val path = temp.root.toPath().resolve(".api_filters")
    Files.writeString(path, xml)

    val rules = ApiFilterRule.load(path)

    assertEquals(2, rules.size)

    val first = rules[0]
    assertEquals("org.example.Type", first.typeName)
    assertEquals("src/org/example/Type.java", first.path)
    assertEquals(12345, first.problemId)
    assertEquals(listOf("arg1", "arg2"), first.messageArguments)

    val second = rules[1]
    assertEquals("org.example.Other", second.typeName)
    assertEquals(null, second.path)
    assertEquals(67890, second.problemId)
    assertEquals(listOf("simple"), second.messageArguments)
  }

  @Test
  fun `load returns empty list for non-existent file`() {
    val path = temp.root.toPath().resolve("does-not-exist")
    assertEquals(emptyList<ApiFilterRule>(), ApiFilterRule.load(path))
  }
}
