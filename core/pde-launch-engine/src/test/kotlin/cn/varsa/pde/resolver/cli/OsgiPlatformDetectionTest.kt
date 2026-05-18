package cn.varsa.pde.resolver.cli

import org.junit.Test
import kotlin.test.assertEquals

class OsgiPlatformDetectionTest {
  @Test
  fun `detects macOS os ws and arm64 arch`() {
    withSystemProperties(osName = "Mac OS X", osArch = "arm64") {
      assertEquals("macosx", currentOsgiOs())
      assertEquals("cocoa", currentOsgiWs())
      assertEquals("aarch64", currentOsgiArch())
    }
  }

  @Test
  fun `detects windows os ws and x86_64 arch`() {
    withSystemProperties(osName = "Windows 11", osArch = "amd64") {
      assertEquals("win32", currentOsgiOs())
      assertEquals("win32", currentOsgiWs())
      assertEquals("x86_64", currentOsgiArch())
    }
  }

  @Test
  fun `defaults to linux gtk and x86_64`() {
    withSystemProperties(osName = "Linux", osArch = "x86_64") {
      assertEquals("linux", currentOsgiOs())
      assertEquals("gtk", currentOsgiWs())
      assertEquals("x86_64", currentOsgiArch())
    }
  }

  private fun withSystemProperties(osName: String, osArch: String, block: () -> Unit) {
    val previousName = System.getProperty("os.name")
    val previousArch = System.getProperty("os.arch")
    System.setProperty("os.name", osName)
    System.setProperty("os.arch", osArch)
    try {
      block()
    } finally {
      restoreProperty("os.name", previousName)
      restoreProperty("os.arch", previousArch)
    }
  }

  private fun restoreProperty(key: String, value: String?) {
    if (value == null) {
      System.clearProperty(key)
    } else {
      System.setProperty(key, value)
    }
  }
}
