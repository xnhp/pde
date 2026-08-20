package cn.varsa.pde.resolver.compile

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
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
  fun `warns but compiles when the bundle declares annotation processors`() {
    val bundle = temp.newFolder("bundle2").toPath()
    val srcDir = bundle.resolve("src").also { it.toFile().mkdirs() }
    srcDir.resolve("Dummy.java").toFile().writeText("class Dummy {}")

    val procJar = processorJar("processor.jar")

    val spec = specFor(
      bsn = "demo.processor",
      bundle = bundle,
      srcDir = srcDir,
      classpath = listOf(procJar.absolutePath),
      ownClasspath = listOf(procJar.absolutePath)
    )

    val result = EcjCompiler().compile(spec)

    assertTrue(result.success, "Processors on the classpath must not fail an otherwise valid bundle")
    val warning = result.warnings.single()
    assertTrue(warning.contains("Annotation processors"), "Warning should mention processors")
    assertTrue(warning.contains("-proc:none"), "Warning should say the processors are not run")
    assertTrue(warning.contains(procJar.absolutePath), "Warning should name the offending jar")
    assertTrue(bundle.resolve("bin/Dummy.class").toFile().exists(), "Class file should be emitted")
  }

  @Test
  fun `does not warn when a processor jar comes only from a dependency`() {
    val bundle = temp.newFolder("bundle3").toPath()
    val srcDir = bundle.resolve("src").also { it.toFile().mkdirs() }
    srcDir.resolve("Dummy.java").toFile().writeText("class Dummy {}")

    // Mirrors the KNIME case: auto-value ships inside a required bundle's libs/, so it lands on
    // the resolved compile classpath of every bundle in the plan without any of them using APT.
    val procJar = processorJar("auto-value-1.11.0.jar")
    val ownBin = bundle.resolve("bin").also { it.toFile().mkdirs() }

    val spec = specFor(
      bsn = "org.knime.email",
      bundle = bundle,
      srcDir = srcDir,
      classpath = listOf(ownBin.toString(), procJar.absolutePath),
      ownClasspath = listOf(ownBin.toString())
    )

    val result = EcjCompiler().compile(spec)

    assertTrue(result.success, "A dependency's processor jar must not fail the bundle")
    assertTrue(result.warnings.isEmpty(), "A dependency's processor jar must not warn: ${result.warnings}")
    assertTrue(bundle.resolve("bin/Dummy.class").toFile().exists(), "Class file should be emitted")
  }

  @Test
  fun `warns when a factorypath configures annotation processing`() {
    val bundle = temp.newFolder("bundle4").toPath()
    val srcDir = bundle.resolve("src").also { it.toFile().mkdirs() }
    srcDir.resolve("Dummy.java").toFile().writeText("class Dummy {}")
    bundle.resolve(".factorypath").toFile().writeText("<factorypath/>")

    val result = EcjCompiler().compile(
      specFor(bsn = "demo.apt", bundle = bundle, srcDir = srcDir, classpath = emptyList(), ownClasspath = emptyList())
    )

    assertTrue(result.success, "APT configuration must not fail the bundle")
    assertTrue(result.warnings.single().contains("factorypath"), "Warning should name the factorypath")
  }

  private fun processorJar(name: String): File {
    val jarFile = temp.newFile(name)
    JarOutputStream(jarFile.outputStream()).use { jar ->
      jar.putNextEntry(JarEntry("META-INF/services/javax.annotation.processing.Processor"))
      jar.write("com.example.Processor".toByteArray())
      jar.closeEntry()
    }
    return jarFile
  }

  private fun specFor(
    bsn: String,
    bundle: Path,
    srcDir: Path,
    classpath: List<String>,
    ownClasspath: List<String>
  ) = CompileSpec(
    bsn = bsn,
    version = "1.0.0",
    origin = "workspace",
    bundlePath = bundle.toString(),
    classpath = classpath,
    ownClasspath = ownClasspath,
    sourceRoots = listOf(srcDir.toString()),
    resourceIncludes = emptyList(),
    resourceExcludes = emptyList(),
    compilerPrefs = emptyMap(),
    executionEnvironment = null,
    outputDirectory = bundle.resolve("bin").toString(),
    isWorkspace = true
  )
}
