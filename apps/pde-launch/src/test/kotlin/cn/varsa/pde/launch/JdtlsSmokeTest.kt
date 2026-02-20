package cn.varsa.pde.launch

import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.test.assertEquals

class JdtlsSmokeTest {
  @Test
  fun `smoke initialize with JDT LS`() {
    val root = envPath("JDTLS_ROOT") ?: createWorkspaceFixture()
    val dataDir = envPath("JDTLS_DATA") ?: root.resolve(".jdtls-data-test")
    val launcher = envPath("JDTLS_LAUNCHER")
    val config = envPath("JDTLS_CONFIG")
    val (resolvedLauncher, resolvedConfig) = if (launcher != null && config != null) {
      launcher to config
    } else {
      val home = ensureJdtlsCached()
      val configDir = home.resolve(selectConfigDir())
      val launcherJar = findLauncherJar(home)
      launcherJar to configDir
    }
    Files.createDirectories(dataDir)

    val runStart = System.nanoTime()
    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = resolvedLauncher,
        configDir = resolvedConfig,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        expectProjects = listOf("demo-project")
      )
    )
    profileLog("runJdtlsSmoke", runStart)
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke implementation request in synthetic workspace`() {
    val root = createWorkspaceWithImplementation()
    val dataDir = root.resolve(".jdtls-data-test")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val interfaceFile = root.resolve("demo-project/src/MyInterface.java")
    val implementationFile = root.resolve("demo-project/src/MyImplementation.java")

    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        importProjects = true,
        implFile = interfaceFile,
        implSymbol = "MyInterface",
        implExpected = listOf(implementationFile.fileName.toString())
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke definition across project dependency`() {
    val root = createWorkspaceWithDependency()
    val dataDir = root.resolve(".jdtls-data-test")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val refFile = root.resolve("bundle-b/src/Consumer.java")
    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        importProjects = true,
        definitionFile = refFile,
        definitionSymbol = "MyApi",
        definitionExpected = listOf("MyApi.java")
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke definition resolves method across project dependency`() {
    val root = createWorkspaceWithDependency()
    val dataDir = root.resolve(".jdtls-data-test")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val refFile = root.resolve("bundle-b/src/Consumer.java")
    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        importProjects = true,
        definitionFile = refFile,
        definitionSymbol = "value",
        definitionExpected = listOf("MyApi.java")
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke references across project dependency`() {
    val root = createWorkspaceWithDependency()
    val dataDir = root.resolve(".jdtls-data-test")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val refFile = root.resolve("bundle-a/src/MyApi.java")
    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        importProjects = true,
        referencesFile = refFile,
        referencesSymbol = "MyApi",
        referencesExpected = listOf("Consumer.java")
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke type hierarchy shows subtype`() {
    val root = createWorkspaceWithHierarchy()
    val dataDir = root.resolve(".jdtls-data-test")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val baseFile = root.resolve("bundle-hierarchy/src/Base.java")
    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        importProjects = true,
        hierarchyFile = baseFile,
        hierarchySymbol = "Base",
        hierarchyExpected = listOf("Derived")
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke completion suggests subtype`() {
    val root = createWorkspaceWithCompletion()
    val dataDir = root.resolve(".jdtls-data-test")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val completionFile = root.resolve("bundle-completion/src/CompletionUser.java")
    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        importProjects = true,
        completionFile = completionFile,
        completionSymbol = "Ba",
        completionExpected = listOf("Base()")
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke diagnostics reports error`() {
    val root = createWorkspaceWithDiagnostics()
    val dataDir = root.resolve(".jdtls-data-test")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val diagnosticsFile = root.resolve("bundle-diagnostics/src/DiagnosticsUser.java")
    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        importProjects = true,
        diagnosticsFile = diagnosticsFile,
        diagnosticsMin = 1
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke definition into target bundle uses sources`() {
    val bundlePool = Paths.get("/home/ben/Desktop/issues/bundle-pool/plugins")
    val targetJar = bundlePool.resolve("org.osgi.util.function_1.2.0.202109301733.jar")
    val sourceJar = bundlePool.resolve("org.osgi.util.function.source_1.2.0.202109301733.jar")
    if (!Files.isRegularFile(targetJar) || !Files.isRegularFile(sourceJar)) {
      throw IllegalStateException("Target/source jars not found under ${bundlePool}")
    }

    val root = createWorkspaceWithTargetSource(targetJar, sourceJar)
    val dataDir = root.resolve(".jdtls-data-test")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val refFile = root.resolve("bundle-target/src/example/TargetUser.java")
    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        importProjects = true,
        classpathFile = refFile.toString(),
        classpathExpected = listOf(targetJar.toAbsolutePath().toString()),
        definitionFile = refFile,
        definitionSymbol = "Consumer",
        definitionExpected = listOf("jdt://"),
        sourceAttachmentExpected = "org.osgi.util.function.source_1.2.0.202109301733.jar"
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke definition into target bundle without sources returns classfile uri`() {
    val bundlePool = Paths.get("/home/ben/Desktop/issues/bundle-pool/plugins")
    val targetJar = bundlePool.resolve("org.osgi.util.function_1.2.0.202109301733.jar")
    if (!Files.isRegularFile(targetJar)) {
      throw IllegalStateException("Target jar not found under ${bundlePool}")
    }

    val root = createWorkspaceWithTargetSource(targetJar, null)
    val dataDir = root.resolve(".jdtls-data-test")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val refFile = root.resolve("bundle-target/src/example/TargetUser.java")
    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = root,
        dataDir = dataDir,
        timeoutMs = 60000,
        importProjects = true,
        definitionFile = refFile,
        definitionSymbol = "Consumer",
        definitionExpected = listOf("jdt://")
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke implementation request in knime-gateway`() {
    assumeRealWorkspaceEnabled("knime-gateway")
    val root = resolveKnimeGatewayRoot()
    assumeTrue("Skipping knime-gateway real-workspace smoke test; workspace not found.", root != null)
    val rootPath = root!!
    val dataDir = resolveExternalDataDir(rootPath, "gateway")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val targetSpec = resolveExistingTargetSpec(
      rootPath,
      listOf(
        rootPath.resolve("org.knime.gateway.api"),
        rootPath.resolve("org.knime.gateway.impl")
      )
    )
    val initExit = JdtlsInitCommand.main(arrayOf("--config", targetSpec.configFile.toString(), "--force"))
    assertEquals(0, initExit)

    val implementationFile = findFile(rootPath, "LocalSpaceProvider.java")
    assumeTrue("Skipping knime-gateway real-workspace smoke test; LocalSpaceProvider.java not found.", implementationFile != null)
    val implementationPath = implementationFile!!

    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = rootPath,
        dataDir = dataDir,
        timeoutMs = 120000,
        importProjects = true,
        symbolQueries = listOf("SpaceProvider", "LocalSpaceProvider"),
        definitionFile = implementationFile,
        definitionSymbol = "EntityFactory",
        definitionExpected = listOf(rootPath.resolve("org.knime.gateway.api").toString()),
        implFile = implementationPath,
        implSymbol = "SpaceProvider",
        implExpected = listOf(implementationPath.fileName.toString())
      )
    )
    assertEquals(0, exitCode)
  }

  @Test
  fun `smoke implementation request in knime-server-client`() {
    assumeRealWorkspaceEnabled("knime-server-client")
    val root = resolveKnimeServerClientRoot()
    assumeTrue("Skipping knime-server-client real-workspace smoke test; workspace not found.", root != null)
    val rootPath = root!!
    val dataDir = resolveExternalDataDir(rootPath, "server-client")
    val (launcher, config) = resolveJdtlsInstallation()
    Files.createDirectories(dataDir)

    val targetSpec = resolveExistingTargetSpec(
      rootPath,
      listOf(
        rootPath.resolve("com.knime.enterprise.client.rest"),
        rootPath.resolve("com.knime.explorer.server")
      )
    )
    val initExit = JdtlsInitCommand.main(arrayOf("--config", targetSpec.configFile.toString(), "--force"))
    assertEquals(0, initExit)

    val implementationFile = rootPath.resolve(
      "com.knime.explorer.server/src/com/knime/explorer/server/rest/RestServerExplorerFileStore.java"
    )
    assumeTrue(
      "Skipping knime-server-client real-workspace smoke test; RestServerExplorerFileStore.java not found.",
      Files.isRegularFile(implementationFile)
    )

    val exitCode = runJdtlsSmoke(
      JdtlsSmokeConfig(
        launcherJar = launcher,
        configDir = config,
        rootDir = rootPath,
        dataDir = dataDir,
        timeoutMs = 120000,
        importProjects = true,
        definitionFile = implementationFile,
        definitionSymbol = "RestServerContent",
        definitionExpected = listOf("com.knime.enterprise.client.rest/src/")
      )
    )
    assertEquals(0, exitCode)
  }
}

private fun envPath(name: String): Path? {
  val value = System.getenv(name)?.trim().orEmpty()
  if (value.isBlank()) return null
  return Paths.get(value)
}

private fun resolveExternalDataDir(root: Path, label: String): Path {
  val override = System.getenv("JDTLS_DATA_ROOT")?.trim().orEmpty()
  val baseDir = if (override.isNotBlank()) {
    Paths.get(override)
  } else {
    Paths.get(System.getProperty("java.io.tmpdir"), "jdtls-smoke")
  }
  val rootName = root.fileName?.toString().orEmpty().ifBlank { "workspace" }
  val dataDir = baseDir.resolve("${rootName}-${label}")
  Files.createDirectories(dataDir)
  return dataDir
}

private fun resolveKnimeGatewayRoot(): Path? {
  val env = envPath("JDTLS_ROOT")
  if (env != null && Files.isDirectory(env)) return env
  val default = Paths.get("/home/ben/Desktop/jdtls-playground/knime-gateway")
  return if (Files.isDirectory(default)) default else null
}

private fun resolveKnimeServerClientRoot(): Path? {
  val default = Paths.get("/home/ben/repos/knime-server-client")
  return if (Files.isDirectory(default)) default else null
}

private fun resolveJdtlsInstallation(): Pair<Path, Path> {
  val launcher = envPath("JDTLS_LAUNCHER")
  val config = envPath("JDTLS_CONFIG")
  if (launcher != null && config != null) return launcher to config
  val home = ensureJdtlsCached()
  return findLauncherJar(home) to home.resolve(selectConfigDir())
}

private fun findFile(root: Path, fileName: String): Path? {
  Files.walk(root).use { stream ->
    return stream.filter { path ->
      Files.isRegularFile(path) && path.name == fileName
    }.findFirst().orElse(null)
  }
}

private fun ensureJdtlsCached(): Path {
  val start = System.nanoTime()
  val artifact = resolveJdtlsArtifact()
  val cacheRoot = Paths.get(System.getProperty("user.home"), ".cache", "jdtls-smoke", artifact.label)
  val marker = cacheRoot.resolve("plugins")
  val configDir = cacheRoot.resolve(selectConfigDir())
  val launcher = findLauncherJarOrNull(cacheRoot)
  if (Files.isDirectory(marker) && Files.isDirectory(configDir) && launcher != null) {
    profileLog("reuse cached JDT LS", start)
    return cacheRoot
  }
  Files.createDirectories(cacheRoot)
  val archive = cacheRoot.resolve(artifact.fileName)
  if (!Files.exists(archive)) {
    val downloadStart = System.nanoTime()
    download(artifact.url, archive)
    profileLog("download JDT LS archive", downloadStart)
  }
  val extractStart = System.nanoTime()
  extractTarGz(archive, cacheRoot)
  profileLog("extract JDT LS archive", extractStart)
  profileLog("ensure JDT LS cached", start)
  return cacheRoot
}

private fun download(url: String, target: Path) {
  URI(url).toURL().openStream().use { input ->
    Files.newOutputStream(target).use { output ->
      input.copyTo(output)
    }
  }
}

private data class JdtlsArtifact(val url: String, val label: String, val fileName: String)

private fun resolveJdtlsArtifact(): JdtlsArtifact {
  val urlOverride = System.getProperty("jdtls.url")?.trim().orEmpty()
    .ifBlank { System.getenv("JDTLS_URL")?.trim().orEmpty() }
  if (urlOverride.isNotBlank()) {
    val fileName = urlOverride.substringAfterLast('/')
    val label = fileName.removeSuffix(".tar.gz")
    return JdtlsArtifact(urlOverride, label, fileName)
  }
  val version = System.getProperty("jdtls.version")?.trim().orEmpty()
    .ifBlank { System.getenv("JDTLS_VERSION")?.trim().orEmpty() }
    .ifBlank { "1.56.0" }
  val build = System.getProperty("jdtls.build")?.trim().orEmpty()
    .ifBlank { System.getenv("JDTLS_BUILD")?.trim().orEmpty() }
    .ifBlank { "202601291528" }
  val fileName = "jdt-language-server-${version}-${build}.tar.gz"
  val url = "https://download.eclipse.org/jdtls/milestones/${version}/${fileName}"
  val label = "jdtls-${version}-${build}"
  return JdtlsArtifact(url, label, fileName)
}

private fun findLauncherJar(home: Path): Path {
  val plugins = home.resolve("plugins")
  val candidates = Files.list(plugins).use { stream ->
    stream.filter { path ->
      val name = path.fileName.toString()
      name.startsWith("org.eclipse.equinox.launcher") && name.endsWith(".jar")
    }.toList()
  }
  if (candidates.isEmpty()) {
    throw IllegalStateException("No launcher jar found in ${plugins}")
  }
  val generic = candidates.firstOrNull { path ->
    val name = path.fileName.toString()
    name.startsWith("org.eclipse.equinox.launcher_") &&
      !name.contains("win32") && !name.contains("gtk.linux") && !name.contains("macosx")
  }
  if (generic != null) return generic

  val arch = System.getProperty("os.arch").lowercase()
  val os = System.getProperty("os.name").lowercase()
  val preferred = candidates.firstOrNull { path ->
    val name = path.fileName.toString().lowercase()
    when {
      os.contains("win") -> name.contains("win32")
      os.contains("mac") -> name.contains("macosx") && (arch.contains("aarch64") || arch.contains("arm64") == name.contains("aarch64"))
      else -> name.contains("gtk.linux") && (arch.contains("aarch64") || arch.contains("arm64") == name.contains("aarch64"))
    }
  }
  return preferred ?: candidates.first()
}

private fun findLauncherJarOrNull(home: Path): Path? {
  return try {
    findLauncherJar(home)
  } catch (_: Exception) {
    null
  }
}

private fun selectConfigDir(): String {
  val os = System.getProperty("os.name").lowercase()
  val arch = System.getProperty("os.arch").lowercase()
  return when {
    os.contains("win") -> "config_win"
    os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "config_mac_arm"
    os.contains("mac") -> "config_mac"
    arch.contains("aarch64") || arch.contains("arm64") -> "config_linux_arm"
    else -> "config_linux"
  }
}

private fun createWorkspaceFixture(): Path {
  val workspace = Files.createTempDirectory("jdtls-workspace")
  Files.createDirectories(workspace.resolve(".metadata"))
  val projectDir = workspace.resolve("demo-project")
  Files.createDirectories(projectDir.resolve("src"))
  Files.writeString(
    projectDir.resolve(".project"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <projectDescription>
        <name>demo-project</name>
        <comment></comment>
        <projects></projects>
        <buildSpec>
          <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments></arguments>
          </buildCommand>
        </buildSpec>
        <natures>
          <nature>org.eclipse.jdt.core.javanature</nature>
        </natures>
      </projectDescription>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve(".classpath"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <classpath>
        <classpathentry kind="src" path="src"/>
        <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
        <classpathentry kind="output" path="bin"/>
      </classpath>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve("src/Hello.java"),
    """
      public class Hello {
        public static void main(String[] args) {
          System.out.println(\"hello\");
        }
      }
    """.trimIndent()
  )
  return workspace
}

private fun createWorkspaceWithImplementation(): Path {
  val workspace = Files.createTempDirectory("jdtls-impl-workspace")
  Files.createDirectories(workspace.resolve(".metadata"))
  val projectDir = workspace.resolve("demo-project")
  Files.createDirectories(projectDir.resolve("src"))
  Files.writeString(
    projectDir.resolve(".project"),
    """
      <?xml version=\"1.0\" encoding=\"UTF-8\"?>
      <projectDescription>
        <name>demo-project</name>
        <comment></comment>
        <projects></projects>
        <buildSpec>
          <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments></arguments>
          </buildCommand>
        </buildSpec>
        <natures>
          <nature>org.eclipse.jdt.core.javanature</nature>
        </natures>
      </projectDescription>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve(".classpath"),
    """
      <?xml version=\"1.0\" encoding=\"UTF-8\"?>
      <classpath>
        <classpathentry kind=\"src\" path=\"src\"/>
        <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER\"/>
        <classpathentry kind=\"output\" path=\"bin\"/>
      </classpath>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve("src/MyInterface.java"),
    """
      public interface MyInterface {
        String id();
      }
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve("src/MyImplementation.java"),
    """
      public class MyImplementation implements MyInterface {
        public String id() { return \"demo\"; }
      }
    """.trimIndent()
  )
  return workspace
}

private fun createWorkspaceWithDependency(): Path {
  val workspace = Files.createTempDirectory("jdtls-dep-workspace")
  Files.createDirectories(workspace.resolve(".metadata"))

  val bundleA = workspace.resolve("bundle-a")
  val bundleB = workspace.resolve("bundle-b")
  Files.createDirectories(bundleA.resolve("src"))
  Files.createDirectories(bundleB.resolve("src"))

  Files.writeString(
    bundleA.resolve(".project"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <projectDescription>
        <name>bundle-a</name>
        <comment></comment>
        <projects></projects>
        <buildSpec>
          <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments></arguments>
          </buildCommand>
        </buildSpec>
        <natures>
          <nature>org.eclipse.jdt.core.javanature</nature>
        </natures>
      </projectDescription>
    """.trimIndent()
  )
  Files.writeString(
    bundleA.resolve(".classpath"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <classpath>
        <classpathentry kind="src" path="src"/>
        <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
        <classpathentry kind="output" path="bin"/>
      </classpath>
    """.trimIndent()
  )
  Files.writeString(
    bundleA.resolve("src/MyApi.java"),
    """
      public interface MyApi {
        String value();
      }
    """.trimIndent()
  )

  Files.writeString(
    bundleB.resolve(".project"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <projectDescription>
        <name>bundle-b</name>
        <comment></comment>
        <projects></projects>
        <buildSpec>
          <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments></arguments>
          </buildCommand>
        </buildSpec>
        <natures>
          <nature>org.eclipse.jdt.core.javanature</nature>
        </natures>
      </projectDescription>
    """.trimIndent()
  )
  Files.writeString(
    bundleB.resolve(".classpath"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <classpath>
        <classpathentry kind="src" path="src"/>
        <classpathentry kind="src" path="/bundle-a"/>
        <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
        <classpathentry kind="output" path="bin"/>
      </classpath>
    """.trimIndent()
  )
  Files.writeString(
    bundleB.resolve("src/Consumer.java"),
    """
      public class Consumer {
        private final MyApi api;
        public Consumer(MyApi api) {
          this.api = api;
        }
        public String read() {
          return api.value();
        }
      }
    """.trimIndent()
  )
  return workspace
}

private fun createWorkspaceWithHierarchy(): Path {
  val workspace = Files.createTempDirectory("jdtls-hierarchy-workspace")
  Files.createDirectories(workspace.resolve(".metadata"))

  val projectDir = workspace.resolve("bundle-hierarchy")
  Files.createDirectories(projectDir.resolve("src"))

  Files.writeString(
    projectDir.resolve(".project"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <projectDescription>
        <name>bundle-hierarchy</name>
        <comment></comment>
        <projects></projects>
        <buildSpec>
          <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments></arguments>
          </buildCommand>
        </buildSpec>
        <natures>
          <nature>org.eclipse.jdt.core.javanature</nature>
        </natures>
      </projectDescription>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve(".classpath"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <classpath>
        <classpathentry kind="src" path="src"/>
        <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
        <classpathentry kind="output" path="bin"/>
      </classpath>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve("src/Base.java"),
    """
      public abstract class Base {
        public abstract String id();
      }
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve("src/Derived.java"),
    """
      public class Derived extends Base {
        @Override
        public String id() { return "derived"; }
      }
    """.trimIndent()
  )
  return workspace
}

private fun createWorkspaceWithCompletion(): Path {
  val workspace = Files.createTempDirectory("jdtls-completion-workspace")
  Files.createDirectories(workspace.resolve(".metadata"))

  val projectDir = workspace.resolve("bundle-completion")
  Files.createDirectories(projectDir.resolve("src"))

  Files.writeString(
    projectDir.resolve(".project"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <projectDescription>
        <name>bundle-completion</name>
        <comment></comment>
        <projects></projects>
        <buildSpec>
          <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments></arguments>
          </buildCommand>
        </buildSpec>
        <natures>
          <nature>org.eclipse.jdt.core.javanature</nature>
        </natures>
      </projectDescription>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve(".classpath"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <classpath>
        <classpathentry kind="src" path="src"/>
        <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
        <classpathentry kind="output" path="bin"/>
      </classpath>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve("src/Base.java"),
    """
      public abstract class Base {
        public abstract String id();
      }
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve("src/Derived.java"),
    """
      public class Derived extends Base {
        @Override
        public String id() { return "derived"; }
      }
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve("src/CompletionUser.java"),
    """
      public class CompletionUser {
        public Base create() {
          return new Ba
        }
      }
    """.trimIndent()
  )
  return workspace
}

private fun createWorkspaceWithDiagnostics(): Path {
  val workspace = Files.createTempDirectory("jdtls-diagnostics-workspace")
  Files.createDirectories(workspace.resolve(".metadata"))

  val projectDir = workspace.resolve("bundle-diagnostics")
  Files.createDirectories(projectDir.resolve("src"))

  Files.writeString(
    projectDir.resolve(".project"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <projectDescription>
        <name>bundle-diagnostics</name>
        <comment></comment>
        <projects></projects>
        <buildSpec>
          <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments></arguments>
          </buildCommand>
        </buildSpec>
        <natures>
          <nature>org.eclipse.jdt.core.javanature</nature>
        </natures>
      </projectDescription>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve(".classpath"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <classpath>
        <classpathentry kind="src" path="src"/>
        <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
        <classpathentry kind="output" path="bin"/>
      </classpath>
    """.trimIndent()
  )
  Files.writeString(
    projectDir.resolve("src/DiagnosticsUser.java"),
    """
      public class DiagnosticsUser {
        public void run() {
          MissingType value = null;
        }
      }
    """.trimIndent()
  )
  return workspace
}

private fun createWorkspaceWithTargetSource(targetJar: Path, sourceJar: Path?): Path {
  val workspace = Files.createTempDirectory("jdtls-target-source-workspace")
  Files.createDirectories(workspace.resolve(".metadata"))

  val bundle = workspace.resolve("bundle-target")
  Files.createDirectories(bundle.resolve("src"))

  Files.writeString(
    bundle.resolve(".project"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <projectDescription>
        <name>bundle-target</name>
        <comment></comment>
        <projects></projects>
        <buildSpec>
          <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments></arguments>
          </buildCommand>
        </buildSpec>
        <natures>
          <nature>org.eclipse.jdt.core.javanature</nature>
        </natures>
      </projectDescription>
    """.trimIndent()
  )
  val sourceAttr = sourceJar?.let { " sourcepath=\"${it.toAbsolutePath()}\"" } ?: ""
  Files.writeString(
    bundle.resolve(".classpath"),
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <classpath>
        <classpathentry kind="src" path="src"/>
        <classpathentry kind="lib" path="${targetJar.toAbsolutePath()}"${sourceAttr}/>
        <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
        <classpathentry kind="output" path="bin"/>
      </classpath>
    """.trimIndent()
  )
  val exampleDir = bundle.resolve("src/example")
  Files.createDirectories(exampleDir)
  Files.writeString(
    exampleDir.resolve("TargetUser.java"),
    """
      package example;

      public class TargetUser {
        private org.osgi.util.function.Consumer consumer;
        public void set(org.osgi.util.function.Consumer consumer) {
          this.consumer = consumer;
        }
      }
    """.trimIndent()
  )
  return workspace
}

private fun copyTargetDefinition(outputDir: Path): Path {
  val resource = JdtlsSmokeTest::class.java.getResourceAsStream("/targets/KNIME-AP-internal.target")
    ?: error("Missing target definition resource")
  val targetFile = outputDir.resolve("KNIME-AP-internal.target")
  resource.use { input ->
    Files.newOutputStream(targetFile).use { output ->
      input.copyTo(output)
    }
  }
  return targetFile
}

private data class TargetInstallSpec(
  val configFile: Path,
  val targetFile: Path,
  val profileId: String,
  val p2Path: Path,
  val bundlePool: Path,
  val installDir: Path
)

private fun resolveExistingTargetSpec(workspaceRoot: Path, modulePaths: List<Path>): TargetInstallSpec {
  val baseDir = System.getenv("JDTLS_TARGET_DIR")?.takeIf { it.isNotBlank() }
    ?.let { Paths.get(it) }
    ?: Paths.get("/tmp/jdtls-target-test")
  Files.createDirectories(baseDir)
  val targetFile = baseDir.resolve("KNIME-AP-internal.target")
  if (!Files.isRegularFile(targetFile)) {
    copyTargetDefinition(baseDir)
  }
  val configFile = baseDir.resolve("config.yaml")
  val bundlePool = System.getenv("JDTLS_BUNDLE_POOL")?.takeIf { it.isNotBlank() }
    ?.let { Paths.get(it) }
    ?: baseDir.resolve("bundle-pool")
  val root = workspaceRoot.toAbsolutePath().normalize()
  val modulesYaml = modulePaths.joinToString("\n") { path ->
    val normalized = path.toAbsolutePath().normalize()
    val relative = if (normalized.startsWith(root)) root.relativize(normalized).toString() else normalized.fileName.toString()
    "            - $relative"
  }
  Files.writeString(
    configFile,
    """
      target:
        definition: ${targetFile.toAbsolutePath()}
        profile-id: jdtls-test
        p2-path: ${baseDir.resolve("p2")}
        bundle-pool: ${bundlePool}
        install: ${baseDir.resolve("install")}
      bundlesPerRepo:
        - repo: ${workspaceRoot.toAbsolutePath()}
          bundles:
${modulesYaml}
    """.trimIndent()
  )
  return TargetInstallSpec(
    configFile = configFile,
    targetFile = targetFile,
    profileId = "jdtls-test",
    p2Path = baseDir.resolve("p2"),
    bundlePool = bundlePool,
    installDir = baseDir.resolve("install")
  )
}

private fun extractTarGz(archive: Path, targetDir: Path) {
  BufferedInputStream(Files.newInputStream(archive)).use { input ->
    val gzip = java.util.zip.GZIPInputStream(input)
    val header = ByteArray(512)
    while (true) {
      val read = readFully(gzip, header)
      if (read < header.size) return
      if (header.all { it == 0.toByte() }) return
      val name = header.copyOfRange(0, 100).toString(StandardCharsets.UTF_8).trim('\u0000')
      val sizeOctal = header.copyOfRange(124, 136).toString(StandardCharsets.UTF_8).trim('\u0000', ' ')
      val size = sizeOctal.toLongOrNull(8) ?: 0
      val typeFlag = header[156].toInt().toChar()
      val entryPath = targetDir.resolve(name).normalize()
      when (typeFlag) {
        '5' -> Files.createDirectories(entryPath)
        else -> {
          entryPath.parent?.let { Files.createDirectories(it) }
          Files.newOutputStream(entryPath).use { output ->
            copyFixed(gzip, output, size)
          }
        }
      }
      val remainder = (512 - (size % 512)) % 512
      if (remainder > 0) {
        skipFully(gzip, remainder)
      }
    }
  }
}

private fun copyFixed(input: java.util.zip.GZIPInputStream, output: OutputStream, size: Long) {
  var remaining = size
  val buffer = ByteArray(8192)
  while (remaining > 0) {
    val read = input.read(buffer, 0, buffer.size.coerceAtMost(remaining.toInt()))
    if (read < 0) break
    output.write(buffer, 0, read)
    remaining -= read
  }
}

private fun readFully(input: java.util.zip.GZIPInputStream, buffer: ByteArray): Int {
  var offset = 0
  while (offset < buffer.size) {
    val read = input.read(buffer, offset, buffer.size - offset)
    if (read < 0) return offset
    offset += read
  }
  return offset
}

private fun skipFully(input: java.util.zip.GZIPInputStream, size: Long) {
  var remaining = size
  while (remaining > 0) {
    val skipped = input.skip(remaining)
    if (skipped <= 0) {
      if (input.read() == -1) return
      remaining -= 1
    } else {
      remaining -= skipped
    }
  }
}

private val PROFILE_ENABLED: Boolean = System.getenv("JDTLS_PROFILE")?.trim()?.let {
  it == "1" || it.equals("true", ignoreCase = true)
} ?: false

private val REAL_WORKSPACE_ENABLED: Boolean =
  isTruthy(System.getProperty("jdtls.real.workspace")) || isTruthy(System.getenv("JDTLS_REAL_WORKSPACE"))

private fun isTruthy(value: String?): Boolean {
  val trimmed = value?.trim().orEmpty()
  return trimmed == "1" || trimmed.equals("true", ignoreCase = true)
}

private fun assumeRealWorkspaceEnabled(label: String) {
  assumeTrue(
    "Skipping ${label} real-workspace smoke test. Set JDTLS_REAL_WORKSPACE=1 or -Djdtls.real.workspace=true to enable.",
    REAL_WORKSPACE_ENABLED
  )
}

private fun profileLog(label: String, startNs: Long) {
  if (!PROFILE_ENABLED) return
  val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
  println("[jdtls-smoke] ${label} in ${elapsedMs}ms")
}
