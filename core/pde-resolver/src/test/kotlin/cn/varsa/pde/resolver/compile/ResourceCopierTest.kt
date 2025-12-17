package cn.varsa.pde.resolver.compile

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class ResourceCopierTest {
  @Test
  fun `copies includes and respects excludes`() {
    val root = Files.createTempDirectory("rc-root")
    val out = Files.createTempDirectory("rc-out")
    root.resolve("META-INF").createDirectories().resolve("plugin.xml").writeText("<plugin/>")
    root.resolve("icons").createDirectories().resolve("a.png").writeText("x")
    root.resolve("src").createDirectories().resolve("Foo.java").writeText("class Foo {}")
    root.resolve("tmp.bak").writeText("skip")

    DefaultResourceCopier.copy(
      root,
      out,
      includes = listOf("META-INF/", ".", "icons/"),
      excludes = listOf("**/*.bak")
    )

    assertTrue(out.resolve("META-INF/plugin.xml").toFile().exists())
    assertTrue(out.resolve("icons/a.png").toFile().exists())
    assertTrue(out.resolve("tmp.bak").notExists())
    assertTrue(out.resolve("src/Foo.java").notExists()) // Java sources are not copied
  }

  @Test
  fun `defaults to include all when bin includes missing`() {
    val root = Files.createTempDirectory("rc-default")
    val out = Files.createTempDirectory("rc-default-out")
    root.resolve("file.txt").writeText("x")
    DefaultResourceCopier.copy(root, out, includes = emptyList(), excludes = emptyList())
    assertTrue(out.resolve("file.txt").toFile().exists())
  }

  private fun Path.notExists(): Boolean = !this.toFile().exists()
}
