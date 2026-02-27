package cn.varsa.pde.launch

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdtlsInitTest {
  @Test
  fun `jdtls-init writes project and classpath`() {
    val baseDir = Files.createTempDirectory("jdtls-init")
    val repoDir = baseDir.resolve("knime-gateway")
    val bundleDir = repoDir.resolve("org.knime.gateway.api.tests")
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src"))
    Files.createDirectories(bundleDir.resolve(".settings"))

    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.knime.gateway.api.tests
        Bundle-Version: 1.0.0
        Fragment-Host: org.knime.gateway.api
      """.trimIndent()
    )
    Files.writeString(
      bundleDir.resolve("build.properties"),
      """
        source..=src/
        output..=bin/
      """.trimIndent()
    )
    Files.writeString(
      bundleDir.resolve(".settings/org.eclipse.jdt.core.prefs"),
      """
        org.eclipse.jdt.core.compiler.codegen.targetPlatform=17
        org.eclipse.jdt.core.compiler.compliance=17
      """.trimIndent()
    )

    val configPath = baseDir.resolve("config.yaml")
    Files.writeString(
      configPath,
      configText(
        writeTargetConfig(baseDir),
        listOf(
          "bundlesPerRepo:",
          "  - repo: knime-gateway",
          "    bundles:",
          "      - org.knime.gateway.api.tests"
        )
      )
    )

    val exitCode = JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    assertEquals(0, exitCode)

    val projectFile = bundleDir.resolve(".project")
    val classpathFile = bundleDir.resolve(".classpath")
    assertTrue(Files.exists(projectFile))
    assertTrue(Files.exists(classpathFile))
    assertTrue(Files.exists(issueDir.resolve(".projectile")))

    val projectContents = Files.readString(projectFile)
    assertTrue(projectContents.contains("org.eclipse.pde.PluginNature"))

    val classpathContents = Files.readString(classpathFile)
    assertFalse(classpathContents.contains("org.eclipse.pde.core.requiredPlugins"))
    assertTrue(classpathContents.contains("JavaSE-17"))
    assertTrue(classpathContents.contains("attribute name=\"test\""))
  }

  @Test
  fun `jdtls-init respects build properties source and output`() {
    val baseDir = Files.createTempDirectory("jdtls-init-buildprops")
    val repoDir = baseDir.resolve("knime-gateway")
    val bundleDir = repoDir.resolve("org.knime.gateway.impl")
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src").resolve("eclipse"))
    Files.createDirectories(bundleDir.resolve("src").resolve("generated"))

    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.knime.gateway.impl
        Bundle-Version: 1.0.0
      """.trimIndent()
    )
    Files.writeString(
      bundleDir.resolve("build.properties"),
      """
        source..=src/eclipse/,src/generated/
        output..=bin/
      """.trimIndent()
    )

    val configPath = baseDir.resolve("config.yaml")
    Files.writeString(
      configPath,
      configText(
        writeTargetConfig(baseDir),
        listOf(
          "bundlesPerRepo:",
          "  - repo: knime-gateway",
          "    bundles:",
          "      - org.knime.gateway.impl"
        )
      )
    )

    val exitCode = JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    assertEquals(0, exitCode)

    val classpathContents = Files.readString(bundleDir.resolve(".classpath"))
    assertTrue(classpathContents.contains("path=\"src/eclipse\""))
    assertTrue(classpathContents.contains("path=\"src/generated\""))
    assertTrue(classpathContents.contains("output\" path=\"bin\""))
  }

  @Test
  fun `jdtls-init resolves bundle paths from issue dir when includes come from shared config`() {
    val rootDir = Files.createTempDirectory("jdtls-init-issue-dir")
    val issueDir = rootDir.resolve("issue-123")
    val sharedDir = rootDir.resolve("shared")
    Files.createDirectories(issueDir)
    Files.createDirectories(sharedDir)

    val repoDir = issueDir.resolve("knime-gateway")
    val bundleDir = repoDir.resolve("org.knime.gateway.api")
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src"))

    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.knime.gateway.api
        Bundle-Version: 1.0.0
      """.trimIndent()
    )

    val includePath = sharedDir.resolve("include.yaml")
    Files.writeString(
      includePath,
      """
        bundlesPerRepo:
          - repo: knime-gateway
            bundles:
              - org.knime.gateway.api
      """.trimIndent()
    )

    val configPath = issueDir.resolve("config.yaml")
    Files.writeString(
      configPath,
      configText(
        writeTargetConfig(issueDir),
        listOf(
          "includes:",
          "  - ../shared/include.yaml"
        )
      )
    )

    val exitCode = JdtlsInitCommand.main(
      arrayOf(
        "--issue-dir",
        issueDir.toString(),
        "--config",
        configPath.toString()
      )
    )
    assertEquals(0, exitCode)

    val projectFile = bundleDir.resolve(".project")
    val classpathFile = bundleDir.resolve(".classpath")
    assertTrue(Files.exists(projectFile))
    assertTrue(Files.exists(classpathFile))
  }

  @Test
  fun `jdtls-init overwrites existing files`() {
    val baseDir = Files.createTempDirectory("jdtls-init-force")
    val repoDir = baseDir.resolve("knime-gateway")
    val bundleDir = repoDir.resolve("org.knime.gateway.api")
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src"))

    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.knime.gateway.api
        Bundle-Version: 1.0.0
      """.trimIndent()
    )

    val projectFile = bundleDir.resolve(".project")
    val classpathFile = bundleDir.resolve(".classpath")
    Files.writeString(projectFile, "<projectDescription>custom</projectDescription>")
    Files.writeString(classpathFile, "<classpath>custom</classpath>")

    val configPath = baseDir.resolve("config.yaml")
    Files.writeString(
      configPath,
      configText(
        writeTargetConfig(baseDir),
        listOf(
          "bundlesPerRepo:",
          "  - repo: knime-gateway",
          "    bundles:",
          "      - org.knime.gateway.api"
        )
      )
    )

    val exitCode = JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    assertEquals(0, exitCode)

    val projectContents = Files.readString(projectFile)
    val classpathContents = Files.readString(classpathFile)
    assertTrue(projectContents.contains("org.eclipse.jdt.core.javanature"))
    assertTrue(classpathContents.contains("JavaSE-21"))
  }

  @Test
  fun `jdtls-init defaults to Java 21 when prefs missing`() {
    val baseDir = Files.createTempDirectory("jdtls-init-default-jre")
    val repoDir = baseDir.resolve("knime-gateway")
    val bundleDir = repoDir.resolve("org.knime.gateway.json")
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src"))

    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.knime.gateway.json
        Bundle-Version: 1.0.0
      """.trimIndent()
    )

    val configPath = baseDir.resolve("config.yaml")
    Files.writeString(
      configPath,
      configText(
        writeTargetConfig(baseDir),
        listOf(
          "bundlesPerRepo:",
          "  - repo: knime-gateway",
          "    bundles:",
          "      - org.knime.gateway.json"
        )
      )
    )

    val exitCode = JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    assertEquals(0, exitCode)

    val classpathContents = Files.readString(bundleDir.resolve(".classpath"))
    assertTrue(classpathContents.contains("JavaSE-21"))
  }

  @Test
  fun `jdtls-init uses target profile bundles`() {
    val baseDir = Files.createTempDirectory("jdtls-init-bundle-pool")
    val bundleDir = baseDir.resolve("workspace").resolve("org.example.client")
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src"))

    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.example.client
        Bundle-Version: 1.0.0
        Require-Bundle: org.example.dep
      """.trimIndent() + "\n"
    )

    val bundlePool = baseDir.resolve("target").resolve("bundle-pool")
    val targetBundle = bundlePool.resolve("plugins").resolve("org.example.dep_1.0.0")
    Files.createDirectories(targetBundle.resolve("META-INF"))
    Files.writeString(
      targetBundle.resolve("META-INF/MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.example.dep
        Bundle-Version: 1.0.0
      """.trimIndent() + "\n"
    )

    val configPath = baseDir.resolve("config.yaml")
    val targetConfig = writeTargetConfig(
      baseDir,
      profileId = "jdtls-test",
      artifacts = listOf("org.example.dep" to "1.0.0")
    )
    Files.writeString(
      configPath,
      configText(
        targetConfig,
        listOf(
          "bundlesPerRepo:",
          "  - repo: ${bundleDir.parent.toAbsolutePath()}",
          "    bundles:",
          "      - ${bundleDir.fileName}"
        )
      )
    )

    val exitCode = JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    assertEquals(0, exitCode)

    val classpathContents = Files.readString(bundleDir.resolve(".classpath"))
    assertTrue(
      classpathContents.contains(targetBundle.toAbsolutePath().toString()),
      "Expected target bundle path in classpath: $classpathContents"
    )
  }

  @Test
  fun `jdtls-init writes projectConfigurations output`() {
    val baseDir = Files.createTempDirectory("jdtls-init-project-config")
    val repoDir = baseDir.resolve("knime-gateway")
    val bundleDir = repoDir.resolve("org.knime.gateway.api")
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src"))

    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.knime.gateway.api
        Bundle-Version: 1.0.0
      """.trimIndent()
    )

    val configPath = baseDir.resolve("config.yaml")
    Files.writeString(
      configPath,
      configText(
        writeTargetConfig(baseDir),
        listOf(
          "bundlesPerRepo:",
          "  - repo: knime-gateway",
          "    bundles:",
          "      - org.knime.gateway.api"
        )
      )
    )

    val outputPath = baseDir.resolve("project-configurations.json")
    val exitCode = JdtlsInitCommand.main(
      arrayOf(
        "--config", configPath.toString(),
        "--project-configurations-out", outputPath.toString()
      )
    )
    assertEquals(0, exitCode)
    assertTrue(Files.exists(outputPath))

    val contents = Files.readString(outputPath)
    val expectedRoot = baseDir.toAbsolutePath().normalize()
    val expectedRootUri = expectedRoot.toUri().toString()
    val expectedUri = bundleDir.resolve(".project").toAbsolutePath().normalize().toUri().toString()
    assertTrue(contents.contains("\"rootPaths\""))
    assertTrue(contents.contains(expectedRoot.toString()))
    assertTrue(contents.contains("\"workspaceFolders\""))
    assertTrue(contents.contains(expectedRootUri))
    assertTrue(contents.contains("\"projectConfigurations\""))
    assertTrue(contents.contains(expectedUri))
  }

  @Test
  fun `jdtls-init defaults projectConfigurations output when run from issue root`() {
    val baseDir = Files.createTempDirectory("jdtls-init-default-project-config")
    val repoDir = baseDir.resolve("knime-gateway")
    val bundleDir = repoDir.resolve("org.knime.gateway.api")
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src"))

    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.knime.gateway.api
        Bundle-Version: 1.0.0
      """.trimIndent()
    )

    val configPath = baseDir.resolve("config.yaml")
    Files.writeString(
      configPath,
      configText(
        writeTargetConfig(baseDir),
        listOf(
          "bundlesPerRepo:",
          "  - repo: knime-gateway",
          "    bundles:",
          "      - org.knime.gateway.api"
        )
      )
    )

    val exitCode = withWorkingDirectory(baseDir) {
      JdtlsInitCommand.main(emptyArray())
    }
    assertEquals(0, exitCode)

    val outputPath = baseDir.resolve(".jdtls-data").resolve("projectConfigurations.json")
    assertTrue(Files.exists(outputPath))
    assertTrue(Files.exists(baseDir.resolve(".projectile")))

    val contents = Files.readString(outputPath)
    val expectedRoot = baseDir.toAbsolutePath().normalize()
    val expectedRootUri = expectedRoot.toUri().toString()
    val expectedUri = bundleDir.resolve(".project").toAbsolutePath().normalize().toUri().toString()
    assertTrue(contents.contains(expectedRoot.toString()))
    assertTrue(contents.contains(expectedRootUri))
    assertTrue(contents.contains(expectedUri))
  }

  @Test
  fun `jdtls-init fails without target config`() {
    val baseDir = Files.createTempDirectory("jdtls-init-missing-target")
    val repoDir = baseDir.resolve("knime-gateway")
    val bundleDir = repoDir.resolve("org.knime.gateway.api")
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src"))

    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: org.knime.gateway.api
        Bundle-Version: 1.0.0
      """.trimIndent()
    )

    val configPath = baseDir.resolve("config.yaml")
    Files.writeString(
      configPath,
      listOf(
        "bundlesPerRepo:",
        "  - repo: knime-gateway",
        "    bundles:",
        "      - org.knime.gateway.api"
      ).joinToString("\n")
    )

    val (exitCode, stderr) = captureStderr {
      JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    }
    assertEquals(1, exitCode)
    assertTrue(
      stderr.contains("Target configuration is required"),
      "Expected missing target error, got: $stderr"
    )
  }

  private fun writeTargetConfig(
    baseDir: Path,
    profileId: String = "profile",
    artifacts: List<Pair<String, String>> = emptyList()
  ): String {
    val p2Path = baseDir.resolve("target").resolve("p2")
    val registryDir = p2Path.resolve("org.eclipse.equinox.p2.engine/profileRegistry")
    val profileDir = registryDir.resolve("$profileId.profile")
    Files.createDirectories(profileDir)

    val bundlePool = baseDir.resolve("target").resolve("bundle-pool")
    Files.createDirectories(bundlePool.resolve("plugins"))

    val profileFile = profileDir.resolve("1.profile")
    val artifactsXml = artifacts.joinToString("\n") { (id, version) ->
      "        <artifact classifier=\"osgi.bundle\" id=\"$id\" version=\"$version\"/>"
    }
    val profileXml = buildString {
      appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
      appendLine("<profile>")
      appendLine("  <properties>")
      appendLine("    <property name=\"org.eclipse.equinox.p2.cache\" value=\"${bundlePool.toAbsolutePath().toUri()}\"/>")
      appendLine("  </properties>")
      appendLine("  <installableUnits>")
      appendLine("    <unit id=\"$profileId\">")
      appendLine("      <artifacts>")
      if (artifactsXml.isNotBlank()) {
        appendLine(artifactsXml)
      }
      appendLine("      </artifacts>")
      appendLine("    </unit>")
      appendLine("  </installableUnits>")
      appendLine("</profile>")
    }
    Files.writeString(profileFile, profileXml)
    return listOf(
      "target:",
      "  profile-id: $profileId",
      "  p2-path: target/p2"
    ).joinToString("\n")
  }

  private fun configText(targetConfig: String, bodyLines: List<String>): String {
    val lines = mutableListOf<String>()
    lines.addAll(targetConfig.trimEnd().lines())
    lines.addAll(bodyLines)
    return lines.joinToString("\n")
  }

  private fun captureStderr(block: () -> Int): Pair<Int, String> {
    val originalErr = System.err
    val buffer = ByteArrayOutputStream()
    val capturing = PrintStream(buffer, true, StandardCharsets.UTF_8)
    return try {
      System.setErr(capturing)
      val exitCode = block()
      exitCode to buffer.toString(StandardCharsets.UTF_8)
    } finally {
      System.setErr(originalErr)
      capturing.flush()
    }
  }

  private fun <T> withWorkingDirectory(dir: Path, block: () -> T): T {
    val original = System.getProperty("user.dir")
    System.setProperty("user.dir", dir.toAbsolutePath().normalize().toString())
    return try {
      block()
    } finally {
      System.setProperty("user.dir", original)
    }
  }
}
