package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetInspectCliTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `inspect ius prints bundles from latest snapshot`() {
    val baseDir = tmp.newFolder("cfg").toPath()
    val profileDir = createProfileDir(baseDir)
    writeSnapshot(
      profileDir.resolve("100.profile"),
      listOf("com.example.old" to "1.0.0")
    )
    writeSnapshot(
      profileDir.resolve("200.profile"),
      listOf("com.example.new" to "2.0.0")
    )
    val configFile = writeConfig(baseDir)

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      val exit = targetInspectIusMain(arrayOf("--config", configFile.toString(), "--json", "--limit", "5"))
      assertEquals(0, exit)
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("com.example.new"), "Expected latest IU in output: $output")
    assertTrue(!output.contains("com.example.old"), "Expected only latest snapshot IUs in output: $output")
  }

  @Test
  fun `inspect diff reports changed bundle versions`() {
    val baseDir = tmp.newFolder("cfg-diff").toPath()
    val profileDir = createProfileDir(baseDir)
    writeSnapshot(
      profileDir.resolve("100.profile"),
      listOf("com.example.bundle" to "1.0.0", "com.example.remove" to "1.0.0")
    )
    writeSnapshot(
      profileDir.resolve("200.profile"),
      listOf("com.example.bundle" to "2.0.0", "com.example.add" to "1.0.0")
    )
    val configFile = writeConfig(baseDir)

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      val exit = targetInspectDiffMain(arrayOf("--config", configFile.toString(), "--json"))
      assertEquals(0, exit)
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("\"id\" : \"com.example.add\""), "Expected added bundle: $output")
    assertTrue(output.contains("\"id\" : \"com.example.remove\""), "Expected removed bundle: $output")
    assertTrue(output.contains("\"id\" : \"com.example.bundle\""), "Expected changed bundle: $output")
    assertTrue(output.contains("\"from\" : \"1.0.0\""), "Expected old version: $output")
    assertTrue(output.contains("\"to\" : \"2.0.0\""), "Expected new version: $output")
  }

  private fun createProfileDir(baseDir: java.nio.file.Path): java.nio.file.Path {
    val profileDir = baseDir
      .resolve("target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.Profile")
    Files.createDirectories(profileDir)
    return profileDir
  }

  private fun writeConfig(baseDir: java.nio.file.Path): java.nio.file.Path {
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(
      configFile,
      """
        target:
          profileId: profile
          p2Path: ./target/p2
      """.trimIndent()
    )
    return configFile
  }

  private fun writeSnapshot(path: java.nio.file.Path, bundles: List<Pair<String, String>>) {
    val artifacts = bundles.joinToString("\n") { (id, version) ->
      "<artifact classifier=\"osgi.bundle\" id=\"$id\" version=\"$version\"/>"
    }
    val content =
      """
        <profile>
          <properties>
            <property name="org.eclipse.equinox.p2.cache" value="file:${tmp.root.absolutePath}/bundle-pool"/>
          </properties>
          <artifacts>
            $artifacts
          </artifacts>
        </profile>
      """.trimIndent()
    Files.writeString(path, content)
  }
}
