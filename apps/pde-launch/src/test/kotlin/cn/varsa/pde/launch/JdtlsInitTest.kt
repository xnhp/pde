package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.EquinoxAppRuntime
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.StreamHandler
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `pde lsp init` generates project files via the same Equinox-based `pde jdt-workspace init`
 * app used by `pde api-baseline check`/`pde jdt-workspace build`. That app's actual
 * `.project`/`.classpath` content generation is covered separately by
 * `WorkspaceSetupServiceTest` (core/pde-resolver), and its own CLI wiring by
 * `WorkspaceSetupCliTest` (core/pde-launch-engine). What's specific to this command —
 * touchProjectile/.vscode settings, discovering the resulting `.project` files, and
 * projectConfigurations output — is exercised below via `JdtlsInitCommand.run`'s DI seam
 * (mirroring `workspaceSetupMain`'s own), which fakes out the Equinox subprocess. The
 * remaining tests below it cover the validation/config-discovery logic that runs *before*
 * the Equinox app would be invoked, which needs no faking at all.
 */
class JdtlsInitTest {
  @Test
  fun `lsp init writes projectile, vscode settings, and projectConfigurations output`() {
    val baseDir = Files.createTempDirectory("lsp-init-happy-path")
    val bundleDir = baseDir.resolve("workspace").resolve("org.example.api")
    writeBundle(bundleDir, "org.example.api")
    createProfileWithFramework(baseDir)
    val configPath = writeTargetConfig(baseDir, listOf(bundleDir))
    val projectConfigFile = baseDir.resolve(".lsp/projectConfigurations.json")

    val exitCode = JdtlsInitCommand.run(
      arrayOf(
        "--config", configPath.toString(),
        "--project-configurations-out", projectConfigFile.toString()
      ),
      equinoxRuntimeResolver = { outputRoot -> fakeEquinoxRuntime(outputRoot) },
      equinoxAppRunner = { _ ->
        // Stands in for the real Equinox app (WorkspaceSetupApplication/WorkspaceSetupService,
        // covered by WorkspaceSetupServiceTest): materializes the same real filesystem artifact
        // this command's own downstream logic (project-file discovery, projectConfigurations
        // output) depends on finding, without actually running Eclipse headless.
        Files.writeString(bundleDir.resolve(".project"), "<projectDescription/>")
        Files.writeString(bundleDir.resolve(".classpath"), "<classpath/>")
        0
      }
    )

    assertEquals(0, exitCode)
    assertTrue(Files.exists(baseDir.resolve(".projectile")), "Expected .projectile to be created")
    val settingsFile = baseDir.resolve(".vscode/settings.json")
    assertTrue(Files.exists(settingsFile), "Expected .vscode/settings.json to be created")
    assertTrue(Files.readString(settingsFile).contains("java.import.maven.enabled"))

    assertTrue(Files.exists(projectConfigFile), "Expected projectConfigurations.json output")
    val projectConfigContents = Files.readString(projectConfigFile)
    val expectedProjectUri = bundleDir.resolve(".project").toAbsolutePath().normalize().toUri().toString()
    assertTrue(
      projectConfigContents.contains(expectedProjectUri),
      "Expected projectConfigurations to reference $expectedProjectUri, got: $projectConfigContents"
    )
  }

  @Test
  fun `lsp init does not overwrite existing vscode settings`() {
    val baseDir = Files.createTempDirectory("lsp-init-existing-settings")
    val bundleDir = baseDir.resolve("workspace").resolve("org.example.api")
    writeBundle(bundleDir, "org.example.api")
    createProfileWithFramework(baseDir)
    val configPath = writeTargetConfig(baseDir, listOf(bundleDir))

    val settingsFile = baseDir.resolve(".vscode/settings.json")
    Files.createDirectories(settingsFile.parent)
    Files.writeString(settingsFile, "{\"custom\":true}")

    val exitCode = JdtlsInitCommand.run(
      arrayOf("--config", configPath.toString()),
      equinoxRuntimeResolver = { outputRoot -> fakeEquinoxRuntime(outputRoot) },
      equinoxAppRunner = { 0 }
    )

    assertEquals(0, exitCode)
    assertTrue(Files.readString(settingsFile).contains("custom"), "Expected existing settings.json to survive")
  }

  @Test
  fun `lsp init fails without bundle entries`() {
    val baseDir = Files.createTempDirectory("lsp-init-no-bundles")
    val configPath = baseDir.resolve("pde.yaml")
    Files.writeString(configPath, "bundles: []\n")

    val (exitCode, stderr) = captureStderr {
      JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    }
    assertEquals(1, exitCode)
    assertTrue(
      stderr.contains("No bundle entries found"),
      "Expected missing-bundles error, got: $stderr"
    )
  }

  @Test
  fun `lsp init fails without target config`() {
    val baseDir = Files.createTempDirectory("lsp-init-missing-target")
    val configPath = baseDir.resolve("pde.yaml")
    Files.writeString(
      configPath,
      listOf(
        "bundles:",
        "  - path: some-bundle"
      ).joinToString("\n")
    )

    val (exitCode, stderr) = captureStderr {
      JdtlsInitCommand.main(arrayOf("--config", configPath.toString()))
    }
    assertEquals(2, exitCode)
    assertTrue(
      stderr.contains("target profile path missing"),
      "Expected missing target-profile error, got: $stderr"
    )
  }

  @Test
  fun `lsp init fails when explicit config is missing`() {
    val baseDir = Files.createTempDirectory("lsp-init-missing-config")
    val missingConfig = baseDir.resolve("nope.yaml")

    val (exitCode, stderr) = captureStderr {
      JdtlsInitCommand.main(arrayOf("--config", missingConfig.toString()))
    }
    assertEquals(1, exitCode)
    assertTrue(
      stderr.contains("Config file not found"),
      "Expected config-not-found error, got: $stderr"
    )
  }

  @Test
  fun `lsp init fails when no config is discovered`() {
    val baseDir = Files.createTempDirectory("lsp-init-no-config")

    val (exitCode, stderr) = captureStderr {
      JdtlsInitCommand.main(arrayOf("--issue-dir", baseDir.toString()))
    }
    assertEquals(1, exitCode)
    assertTrue(
      stderr.contains("No launch config found"),
      "Expected no-config-found error, got: $stderr"
    )
  }

  private fun fakeEquinoxRuntime(outputRoot: Path): EquinoxAppRuntime = EquinoxAppRuntime(
    launcherExecutable = Path.of("/fake/equinox-launcher"),
    configurationDir = outputRoot.resolve("configuration"),
    dataDir = outputRoot.resolve("data")
  )

  private fun writeBundle(bundleDir: Path, bsn: String) {
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.createDirectories(bundleDir.resolve("src"))
    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-SymbolicName: $bsn
        Bundle-Version: 1.0.0
      """.trimIndent()
    )
  }

  private fun createProfileWithFramework(baseDir: Path, profileId: String = "profile") {
    val registry = baseDir.resolve("target/p2/org.eclipse.equinox.p2.engine/profileRegistry/$profileId.Profile")
    Files.createDirectories(registry)
    val pool = baseDir.resolve("target/p2/bundle-pool")
    val plugins = pool.resolve("plugins")
    Files.createDirectories(plugins)
    val mf = Manifest().apply {
      mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
      mainAttributes.putValue("Bundle-ManifestVersion", "2")
      mainAttributes.putValue("Bundle-SymbolicName", "org.eclipse.osgi")
      mainAttributes.putValue("Bundle-Version", "1.0.0")
    }
    JarOutputStream(Files.newOutputStream(plugins.resolve("org.eclipse.osgi_1.0.0.jar")), mf).use { /* empty */ }
    Files.writeString(
      registry.resolve("1.profile"),
      """
        <?xml version="1.0" encoding="UTF-8"?>
        <profile id="$profileId" timestamp="1" version="1.0.0">
          <properties>
            <property name="org.eclipse.equinox.p2.cache" value="${pool.toUri()}"/>
          </properties>
        </profile>
      """.trimIndent()
    )
  }

  private fun writeTargetConfig(baseDir: Path, bundleDirs: List<Path>, profileId: String = "profile"): Path {
    val configPath = baseDir.resolve("pde.yaml")
    val bundlesYaml = bundleDirs.joinToString("\n") { dir -> "  - path: ${dir.toAbsolutePath()}" }
    Files.writeString(
      configPath,
      listOf(
        "target:",
        "  profileId: $profileId",
        "  p2Path: target/p2",
        "bundles:",
        bundlesYaml
      ).joinToString("\n")
    )
    return configPath
  }

  /**
   * `CliLogging.configure()` only tweaks the root logger's *existing* handlers, while
   * `workspaceSetupMain`'s own internal logging setup unconditionally tears down and replaces
   * them with a fresh one bound to whatever `System.err` is at that moment. To capture output
   * reliably regardless of which path a given command run takes (and regardless of whatever
   * stale handler state earlier tests left on the JVM-global root logger), both redirect
   * System.err *and* pre-install a handler bound to the same buffer before invoking the command.
   */
  private fun captureStderr(block: () -> Int): Pair<Int, String> {
    val originalErr = System.err
    val rootLogger = Logger.getLogger("")
    val originalHandlers = rootLogger.handlers.toList()
    originalHandlers.forEach { rootLogger.removeHandler(it) }

    val buffer = ByteArrayOutputStream()
    val printStream = PrintStream(buffer, true, StandardCharsets.UTF_8)
    val handler = object : StreamHandler(printStream, java.util.logging.SimpleFormatter()) {
      override fun publish(record: java.util.logging.LogRecord) {
        super.publish(record)
        flush()
      }
    }
    handler.level = Level.ALL
    rootLogger.addHandler(handler)

    return try {
      System.setErr(printStream)
      val exitCode = block()
      exitCode to buffer.toString(StandardCharsets.UTF_8)
    } finally {
      System.setErr(originalErr)
      rootLogger.removeHandler(handler)
      originalHandlers.forEach { rootLogger.addHandler(it) }
    }
  }
}
