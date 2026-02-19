package cn.varsa.pde.launch

import org.junit.Test
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
      """
        bundlesPerRepo:
          - repo: knime-gateway
            bundles:
              - org.knime.gateway.api.tests
      """.trimIndent()
    )

    val exitCode = JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    assertEquals(0, exitCode)

    val projectFile = bundleDir.resolve(".project")
    val classpathFile = bundleDir.resolve(".classpath")
    assertTrue(Files.exists(projectFile))
    assertTrue(Files.exists(classpathFile))

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
      """
        bundlesPerRepo:
          - repo: knime-gateway
            bundles:
              - org.knime.gateway.impl
      """.trimIndent()
    )

    val exitCode = JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    assertEquals(0, exitCode)

    val classpathContents = Files.readString(bundleDir.resolve(".classpath"))
    assertTrue(classpathContents.contains("path=\"src/eclipse\""))
    assertTrue(classpathContents.contains("path=\"src/generated\""))
    assertTrue(classpathContents.contains("output\" path=\"bin\""))
  }

  @Test
  fun `jdtls-init keeps existing files unless forced`() {
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
      """
        bundlesPerRepo:
          - repo: knime-gateway
            bundles:
              - org.knime.gateway.api
      """.trimIndent()
    )

    val exitCode = JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    assertEquals(0, exitCode)

    assertEquals("<projectDescription>custom</projectDescription>", Files.readString(projectFile))
    assertEquals("<classpath>custom</classpath>", Files.readString(classpathFile))
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
      """
        bundlesPerRepo:
          - repo: knime-gateway
            bundles:
              - org.knime.gateway.json
      """.trimIndent()
    )

    val exitCode = JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    assertEquals(0, exitCode)

    val classpathContents = Files.readString(bundleDir.resolve(".classpath"))
    assertTrue(classpathContents.contains("JavaSE-21"))
  }

  @Test
  fun `jdtls-init uses bundle pool when profile missing`() {
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

    val bundlePool = baseDir.resolve("bundle-pool")
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
    Files.writeString(
      configPath,
      """
        target:
          bundle-pool: ${bundlePool.toAbsolutePath()}
        bundlesPerRepo:
          - repo: ${bundleDir.parent.toAbsolutePath()}
            bundles:
              - ${bundleDir.fileName}
      """.trimIndent()
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
      """
        bundlesPerRepo:
          - repo: knime-gateway
            bundles:
              - org.knime.gateway.api
      """.trimIndent()
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
    val expectedUri = bundleDir.resolve(".project").toAbsolutePath().normalize().toUri().toString()
    assertTrue(contents.contains("\"projectConfigurations\""))
    assertTrue(contents.contains(expectedUri))
  }
}
