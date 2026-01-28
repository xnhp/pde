package cn.varsa.pde.resolver.compile

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceCopierTest {
  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test
  fun `copies bin includes and respects excludes`() {
    val bundle = temp.newFolder("bundle").toPath()
    bundle.resolve("plugin.xml").writeText("<plugin/>")
    bundle.resolve("notes.md").writeText("skip me")
    val srcDir = bundle.resolve("src").createDirectories()
    srcDir.resolve("Example.java").writeText("class Example {}")

    val outDir = bundle.resolve("bin")
    DefaultResourceCopier.copy(
      root = bundle,
      outDir = outDir,
      includes = listOf("."),
      excludes = listOf("**/*.md"),
      classpathEntries = emptyList(),
      sourceRoots = emptyList()
    )

    assertTrue(outDir.resolve("plugin.xml").toFile().exists(), "plugin.xml should be copied")
    assertFalse(outDir.resolve("notes.md").toFile().exists(), "excluded files should not be copied")
    assertFalse(outDir.resolve("src/Example.java").toFile().exists(), "java sources must not be copied")
  }

  @Test
  fun `copies embedded bundle classpath jars`() {
    val bundle = temp.newFolder("bundle2").toPath()
    val libDir = bundle.resolve("lib").createDirectories()
    val jarPath = libDir.resolve("embedded.jar")
    Files.write(jarPath, byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // minimal zip header

    val outDir = bundle.resolve("bin")
    DefaultResourceCopier.copy(
      root = bundle,
      outDir = outDir,
      includes = listOf("."),
      excludes = emptyList(),
      classpathEntries = listOf(jarPath.toString()),
      sourceRoots = emptyList()
    )

    assertTrue(outDir.resolve("lib/embedded.jar").toFile().exists(), "embedded jar should be copied to output")
  }

  @Test
  fun `copies non-java resources from source roots`() {
    val bundle = temp.newFolder("bundle3").toPath()
    val srcDir = bundle.resolve("src/eclipse").createDirectories()
    srcDir.resolve("log4j/log4j-0.xml").apply {
      parent.createDirectories()
      writeText("<log4j/>")
    }
    srcDir.resolve("Example.java").writeText("class Example {}")

    val outDir = bundle.resolve("bin")
    DefaultResourceCopier.copy(
      root = bundle,
      outDir = outDir,
      includes = listOf("."),
      excludes = emptyList(),
      classpathEntries = emptyList(),
      sourceRoots = listOf(srcDir.toString())
    )

    assertTrue(outDir.resolve("log4j/log4j-0.xml").toFile().exists(), "resource from source root should be copied")
    assertFalse(outDir.resolve("Example.java").toFile().exists(), "java sources must not be copied")
  }
}
