package cn.varsa.pde.resolver.compile

import org.junit.Test
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassfileVersionCheckerTest {

  @Test
  fun `detects newer classfile versions`() {
    val jar = Files.createTempFile("classfile", ".jar")
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      out.putNextEntry(JarEntry("com/example/Foo.class"))
      out.write(classHeaderBytes(major = 65))
      out.closeEntry()
    }

    val maxMajor = ClassfileVersionChecker.classMajorForLevel("17")
    assertEquals(61, maxMajor)
    val mismatches = ClassfileVersionChecker.findMismatches(listOf(jar.toString()), maxMajor!!)
    assertTrue(mismatches.isNotEmpty())
    assertEquals(jar.toString(), mismatches.first().classpathEntry)
    assertEquals(65, mismatches.first().majorVersion)
  }

  @Test
  fun `class major mapping supports java 8 and 21`() {
    assertEquals(52, ClassfileVersionChecker.classMajorForLevel("1.8"))
    assertEquals(65, ClassfileVersionChecker.classMajorForLevel("21"))
  }

  @Test
  fun `ignores multi-release entries above target`() {
    val jar = Files.createTempFile("multirelease", ".jar")
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      out.putNextEntry(JarEntry("META-INF/versions/22/com/example/Foo.class"))
      out.write(classHeaderBytes(major = 66))
      out.closeEntry()
    }

    val maxMajor = ClassfileVersionChecker.classMajorForLevel("21")
    val mismatches = ClassfileVersionChecker.findMismatches(listOf(jar.toString()), maxMajor!!, 21)
    assertTrue(mismatches.isEmpty())
  }

  @Test
  fun `flags multi-release entries at or below target`() {
    val jar = Files.createTempFile("multirelease-target", ".jar")
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      out.putNextEntry(JarEntry("META-INF/versions/21/com/example/Foo.class"))
      out.write(classHeaderBytes(major = 66))
      out.closeEntry()
    }

    val maxMajor = ClassfileVersionChecker.classMajorForLevel("21")
    val mismatches = ClassfileVersionChecker.findMismatches(listOf(jar.toString()), maxMajor!!, 21)
    assertTrue(mismatches.isNotEmpty())
    assertEquals(66, mismatches.first().majorVersion)
  }

  private fun classHeaderBytes(major: Int): ByteArray {
    val majorHigh = (major shr 8).toByte()
    val majorLow = (major and 0xFF).toByte()
    return byteArrayOf(
      0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
      0x00, 0x00, majorHigh, majorLow
    )
  }
}
