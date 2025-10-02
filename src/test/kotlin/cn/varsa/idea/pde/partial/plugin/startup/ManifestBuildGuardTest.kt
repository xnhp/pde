package cn.varsa.idea.pde.partial.plugin.startup

import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ManifestBuildGuardTest {
  private val project = FakeProject()

  @Test
  fun buildBlockedWhenCollectorReportsProblems() {
    val problems = listOf(
      ManifestProblem("sample", "file://manifest", listOf(ManifestError("Invalid manifest", 0, 5)))
    )
    val collector = ManifestProblemCollector { problems }
    val notifier = RecordingNotifier()
    val context = FakeCompileContext(project.proxy)

    val result = ManifestBeforeCompileTask(project.proxy, collector, notifier).execute(context.proxy)

    assertFalse(result)
    assertEquals(1, context.messages.size)
    assertEquals(CompilerMessageCategory.ERROR, context.messages.first().category)
    assertTrue(context.messages.first().message.contains("Invalid manifest"))
    assertEquals(listOf("sample"), notifier.notifiedModules)
  }

  @Test
  fun buildSucceedsWhenCollectorReturnsEmpty() {
    val collector = ManifestProblemCollector { emptyList() }
    val notifier = RecordingNotifier()
    val context = FakeCompileContext(project.proxy)

    val result = ManifestBeforeCompileTask(project.proxy, collector, notifier).execute(context.proxy)

    assertTrue(result)
    assertTrue(context.messages.isEmpty())
    assertTrue(notifier.notifiedModules.isEmpty())
  }
}

private class RecordingNotifier : ManifestProblemNotifier {
  val notifiedModules = mutableListOf<String>()

  override fun notify(project: Project, problems: List<ManifestProblem>) {
    notifiedModules += problems.map { it.moduleName }
  }
}

private class FakeProject : InvocationHandler {
  val proxy: Project = Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java),
    this
  ) as Project

  private val messageBus: MessageBus = createProxy(MessageBus::class.java)

  override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
    "isDisposed" -> false
    "getName" -> "TestProject"
    "getMessageBus" -> messageBus
    else -> defaultValue(method.returnType)
  }
}

private class FakeCompileContext(private val project: Project) : InvocationHandler {
  data class Message(val category: CompilerMessageCategory, val message: String)

  val messages = mutableListOf<Message>()

  val proxy: CompileContext = Proxy.newProxyInstance(
    CompileContext::class.java.classLoader,
    arrayOf(CompileContext::class.java),
    this
  ) as CompileContext

  private val messageBus: MessageBus = createProxy(MessageBus::class.java)

  override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
    "addMessage" -> {
      messages += Message(
        args?.get(0) as CompilerMessageCategory,
        args.getOrNull(1) as? String ?: ""
      )
      null
    }

    "getProject" -> project
    "getMessageBus" -> messageBus
    else -> defaultValue(method.returnType)
  }
}

private fun <T> createProxy(type: Class<T>): T =
  Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> defaultValue(method.returnType) } as T

private fun defaultValue(type: Class<*>): Any? = when (type) {
  java.lang.Boolean.TYPE -> false
  java.lang.Byte.TYPE -> 0.toByte()
  java.lang.Short.TYPE -> 0.toShort()
  java.lang.Integer.TYPE -> 0
  java.lang.Long.TYPE -> 0L
  java.lang.Float.TYPE -> 0f
  java.lang.Double.TYPE -> 0.0
  java.lang.Character.TYPE -> 0.toChar()
  java.lang.Void.TYPE -> null
  else -> null
}
