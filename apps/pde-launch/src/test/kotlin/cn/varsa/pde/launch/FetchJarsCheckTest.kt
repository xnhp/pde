package cn.varsa.pde.launch

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchJarsCheckTest {
  @Test
  fun `findEmptyFetchJarsLibDirs flags lib folder with no jars`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-empty")
    Files.createDirectories(bundleDir.resolve("lib/fetch_jars"))

    val emptyLibDirs = findEmptyFetchJarsLibDirs(bundleDir)

    assertEquals(listOf(bundleDir.resolve("lib")), emptyLibDirs)
  }

  @Test
  fun `findEmptyFetchJarsLibDirs ignores lib folder that already has jars`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-populated")
    Files.createDirectories(bundleDir.resolve("lib/fetch_jars"))
    Files.writeString(bundleDir.resolve("lib/some-dep.jar"), "")

    assertTrue(findEmptyFetchJarsLibDirs(bundleDir).isEmpty())
  }

  @Test
  fun `findEmptyFetchJarsLibDirs handles nested lib subfolders`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-nested")
    Files.createDirectories(bundleDir.resolve("lib/mysql8/fetch_jars"))
    Files.createDirectories(bundleDir.resolve("lib/postgresql/fetch_jars"))
    Files.writeString(bundleDir.resolve("lib/postgresql/postgresql.jar"), "")

    val emptyLibDirs = findEmptyFetchJarsLibDirs(bundleDir)

    assertEquals(listOf(bundleDir.resolve("lib/mysql8")), emptyLibDirs)
  }

  @Test
  fun `findEmptyFetchJarsLibDirs returns empty when no fetch_jars folder present`() {
    val bundleDir = Files.createTempDirectory("fetch-jars-none")
    Files.createDirectories(bundleDir.resolve("src"))

    assertTrue(findEmptyFetchJarsLibDirs(bundleDir).isEmpty())
  }
}
