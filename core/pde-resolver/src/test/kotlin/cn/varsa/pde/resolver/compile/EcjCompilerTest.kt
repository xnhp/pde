package cn.varsa.pde.resolver.compile

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EcjCompilerTest {

  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test
  fun `compiles simple java sources`() {
    val bundle = temp.newFolder("bundle").toPath()
    val srcDir = bundle.resolve("src/main/java").also { it.toFile().mkdirs() }
    val javaFile = srcDir.resolve("Example.java").toFile()
    javaFile.writeText(
      """
        package demo;
        public class Example { public String greet() { return "hi"; } }
      """.trimIndent()
    )

    val spec = CompileSpec(
      bsn = "demo.example",
      version = "1.0.0",
      origin = "workspace",
      bundlePath = bundle.toString(),
      classpath = emptyList(),
      sourceRoots = listOf(srcDir.toString()),
      resourceIncludes = emptyList(),
      resourceExcludes = emptyList(),
      compilerPrefs = mapOf("org.eclipse.jdt.core.compiler.source" to "17"),
      executionEnvironment = "JavaSE-17",
      outputDirectory = bundle.resolve("bin").toString(),
      isWorkspace = true
    )

    val result = EcjCompiler().compile(spec)

    assertTrue(result.success, "ECJ should compile simple sources")
    assertTrue(bundle.resolve("bin/demo/Example.class").toFile().exists(), "Class file should be emitted")
  }

  @Test
  fun `fails fast when annotation processors present`() {
    val bundle = temp.newFolder("bundle2").toPath()
    val srcDir = bundle.resolve("src").also { it.toFile().mkdirs() }
    srcDir.resolve("Dummy.java").toFile().writeText("class Dummy {}")

    // Create a fake processor jar with service entry
    val procJar = temp.newFile("processor.jar")
    JarOutputStream(procJar.outputStream()).use { jar ->
      jar.putNextEntry(JarEntry("META-INF/services/javax.annotation.processing.Processor"))
      jar.write("com.example.Processor".toByteArray())
      jar.closeEntry()
    }

    val spec = CompileSpec(
      bsn = "demo.processor",
      version = "1.0.0",
      origin = "workspace",
      bundlePath = bundle.toString(),
      classpath = listOf(procJar.absolutePath),
      sourceRoots = listOf(srcDir.toString()),
      resourceIncludes = emptyList(),
      resourceExcludes = emptyList(),
      compilerPrefs = emptyMap(),
      executionEnvironment = null,
      outputDirectory = bundle.resolve("bin").toString(),
      isWorkspace = true
    )

    val result = EcjCompiler().compile(spec)

    assertFalse(result.success, "Compile should fail fast when processors are detected")
    assertTrue(result.output.contains("Annotation processors"), "Output should mention processors")
  }
}
