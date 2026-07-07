package cn.varsa.pde.resolver.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import javax.tools.ToolProvider
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

class DirectApiAnalyzerHarnessTest {
  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test
  fun `reports removed public method from generated bundle jars`() {
    val baseline = bundleJar(
      bsn = "org.example.api",
      version = "1.0.0",
      sources = mapOf(
        "org/example/api/Example.java" to """
          package org.example.api;
          public class Example {
            public void kept() {}
            public void removed() {}
          }
        """.trimIndent()
      )
    )
    val current = bundleJar(
      bsn = "org.example.api",
      version = "1.1.0",
      sources = mapOf(
        "org/example/api/Example.java" to """
          package org.example.api;
          public class Example {
            public void kept() {}
          }
        """.trimIndent()
      )
    )

    val report = analyzeThroughEquinox(current = current, baseline = baseline)

    assertTrue(report.problems.toString(), report.problems.any { problem ->
      problem.category == "compatibility" &&
        problem.messageArguments.any { it.contains("removed") || it.contains("Example") }
    })
    assertEquals(report, ApiAnalysisReportJson.read(temp.root.toPath().resolve("report.json")))
  }

  @Test
  fun `emits zero problems for equivalent generated bundle jars`() {
    val sources = mapOf(
      "org/example/api/Example.java" to """
        package org.example.api;
        public class Example {
          public void kept() {}
        }
      """.trimIndent()
    )
    val baseline = bundleJar("org.example.same", "1.0.0", sources)
    val current = bundleJar("org.example.same", "1.0.0", sources)

    val report = analyzeThroughEquinox(current = current, baseline = baseline)

    assertEquals(emptyList<ApiAnalysisProblem>(), report.problems)
  }

  @Test
  fun `uses dependency artifact while resolving current bundle`() {
    val dependency = bundleJar(
      bsn = "org.example.dep",
      version = "1.0.0",
      sources = mapOf(
        "org/example/dep/Dep.java" to """
          package org.example.dep;
          public class Dep {}
        """.trimIndent()
      )
    )
    val sources = mapOf(
      "org/example/api/UsesDep.java" to """
        package org.example.api;
        import org.example.dep.Dep;
        public class UsesDep {
          public Dep dep() { return null; }
        }
      """.trimIndent()
    )
    val baseline = bundleJar("org.example.requires", "1.0.0", sources, requireBundle = "org.example.dep", classpath = listOf(dependency))
    val current = bundleJar("org.example.requires", "1.1.0", sources, requireBundle = "org.example.dep", classpath = listOf(dependency))

    val report = analyzeThroughEquinox(current = current, baseline = baseline, dependencies = listOf(dependency))

    assertTrue(report.problems.none { it.category == "component-resolution" })
  }

  private fun analyzeThroughEquinox(
    current: AnalyzerBundleArtifact,
    baseline: AnalyzerBundleArtifact,
    dependencies: List<AnalyzerBundleArtifact> = emptyList()
  ): ApiAnalysisReport {
    val runtime = assembleRuntime()
    val reportPath = temp.root.toPath().resolve("report.json")
    val inputPath = temp.root.toPath().resolve("analyzer-input.json")
    inputPath.writeText(
      DirectApiAnalyzerInputJson.write(
        DirectApiAnalyzerInput(
          currentBundle = current,
          dependencyArtifacts = dependencies,
          baselineArtifacts = listOf(baseline),
          outputReportPath = reportPath
        )
      )
    )

    val process = ProcessBuilder(
      javaExecutable().toString(),
      "-jar", runtime.launcher.toString(),
      "-application", DirectApiAnalyzerApplication.APPLICATION_ID,
      "-data", runtime.workspace.toString(),
      "-configuration", runtime.config.toString(),
      "-consoleLog",
      "--input", inputPath.toString()
    )
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertTrue("Analyzer process timed out. Output:\n$output", process.waitFor(60, TimeUnit.SECONDS))
    assertEquals("Analyzer process failed. Output:\n$output", 0, process.exitValue())
    assertTrue("Analyzer did not write report. Output:\n$output", reportPath.exists())
    return ApiAnalysisReportJson.read(reportPath)
  }

  private fun assembleRuntime(): RuntimePaths {
    val runtimeArchive = analyzerRuntimeArchive()
    assumeTrue(
      "Set PDE_API_ANALYZER_RUNTIME_ARCHIVE or -Dpde.apiAnalyzer.runtime.archive to run the Equinox analyzer harness test",
      runtimeArchive != null
    )

    val root = Files.createTempDirectory(temp.root.toPath(), "analyzer-runtime-")
    extractZip(runtimeArchive!!, root)
    val plugins = root.resolve("plugins")
    require(plugins.isDirectory()) { "Analyzer runtime archive does not contain a plugins/ directory: $runtimeArchive" }
    createAnalyzerApplicationBundle(plugins.resolve("cn.varsa.pde_1.0.0"))

    ensureConfiguration(root)
    val launcher = findBundle(plugins, "org.eclipse.equinox.launcher")
      ?: error("Analyzer runtime archive does not contain org.eclipse.equinox.launcher: $runtimeArchive")
    return RuntimePaths(root.resolve("config"), root.resolve("workspace"), launcher)
  }

  private fun createAnalyzerApplicationBundle(appDir: Path): Path {
    val classesRoot = Path.of(DirectApiAnalyzerApplication::class.java.protectionDomain.codeSource.location.toURI())
    require(classesRoot.isDirectory()) { "Expected test analyzer classes to be loaded from a directory: $classesRoot" }
    copyDirectory(classesRoot, appDir)

    appDir.resolve("META-INF").createDirectories()
    val manifest = Manifest().apply {
      mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
      mainAttributes.putValue("Bundle-ManifestVersion", "2")
      mainAttributes.putValue("Bundle-SymbolicName", "cn.varsa.pde;singleton:=true")
      mainAttributes.putValue("Bundle-Version", "1.0.0")
      mainAttributes.putValue("Bundle-Name", "pde API Analyzer Test Application")
      mainAttributes.putValue("Bundle-RequiredExecutionEnvironment", "JavaSE-21")
      mainAttributes.putValue("Bundle-ClassPath", (listOf(".") + embeddedApplicationLibraries().map { "lib/${it.name}" }).joinToString(","))
      mainAttributes.putValue(
        "Require-Bundle",
        listOf(
          "org.eclipse.equinox.app",
          "org.eclipse.core.runtime",
          "org.eclipse.core.resources",
          "org.eclipse.core.filesystem",
          "org.eclipse.core.filebuffers",
          "org.eclipse.core.variables",
          "org.eclipse.text",
          "org.eclipse.jdt.core",
          "org.eclipse.jdt.launching",
          "org.eclipse.pde.api.tools"
        ).joinToString(",")
      )
    }
    appDir.resolve("META-INF/MANIFEST.MF").outputStream().use(manifest::write)
    appDir.resolve("plugin.xml").writeText(
      """
        <?xml version="1.0" encoding="UTF-8"?>
        <plugin>
          <extension id="api_analyzer" point="org.eclipse.core.runtime.applications">
            <application>
              <run class="cn.varsa.pde.resolver.api.DirectApiAnalyzerApplication"/>
            </application>
          </extension>
        </plugin>
      """.trimIndent()
    )
    val libDir = appDir.resolve("lib").createDirectories()
    embeddedApplicationLibraries().forEach { jar ->
      jar.copyTo(libDir.resolve(jar.name), overwrite = true)
    }
    return appDir
  }

  private fun readBundleMetadata(path: Path): BundleMetadata? {
    val manifest = if (path.isDirectory()) {
      path.resolve("META-INF/MANIFEST.MF").takeIf { it.isRegularFile() }?.inputStream()?.use(::Manifest)
    } else {
      runCatching { JarFile(path.toFile()).use { it.manifest } }.getOrNull()
    } ?: return null
    val attrs = manifest.mainAttributes
    val bsn = attrs.getValue("Bundle-SymbolicName")?.substringBefore(';')?.trim().orEmpty()
    val version = attrs.getValue("Bundle-Version")?.trim().orEmpty()
    if (bsn.isBlank() || version.isBlank()) return null
    return BundleMetadata(bsn, version)
  }

  private fun copyDirectory(source: Path, target: Path) {
    Files.walk(source).use { stream ->
      stream.forEach { path ->
        val destination = target.resolve(path.relativeTo(source).toString())
        if (path.isDirectory()) {
          destination.createDirectories()
        } else {
          destination.parent.createDirectories()
          path.copyTo(destination, overwrite = true)
        }
      }
    }
  }

  private fun runtimeClasspathJars(): List<Path> = System.getProperty("java.class.path")
    .split(File.pathSeparator)
    .mapNotNull { entry -> entry.takeIf(String::isNotBlank)?.let(Path::of) }
    .filter { it.isRegularFile() && it.name.endsWith(".jar") }

  private fun embeddedApplicationLibraries(): List<Path> = runtimeClasspathJars()
    .filter(::isEmbeddedApplicationLibrary)
    .distinctBy { it.name }

  private fun isEmbeddedApplicationLibrary(path: Path): Boolean {
    val metadata = readBundleMetadata(path)
    if (metadata == null) {
      return path.name.startsWith("kotlin-") || path.name.contains("jackson-")
    }
    return metadata.bsn in privateApplicationLibraryIds
  }

  private val privateApplicationLibraryIds = setOf(
    "com.fasterxml.jackson.core.jackson-annotations",
    "com.fasterxml.jackson.core.jackson-core",
    "com.fasterxml.jackson.core.jackson-databind",
    "com.fasterxml.jackson.datatype.jackson-datatype-jdk8",
    "com.fasterxml.jackson.datatype.jackson-datatype-jsr310",
    "com.fasterxml.jackson.module.jackson-module-kotlin",
    "org.jetbrains.kotlin.stdlib",
    "org.jetbrains.kotlin.stdlib.jdk7",
    "org.jetbrains.kotlin.stdlib.jdk8"
  )

  private fun analyzerRuntimeArchive(): Path? =
    System.getProperty("pde.apiAnalyzer.runtime.archive")
      ?.takeIf(String::isNotBlank)
      ?.let(Path::of)
      ?: System.getenv("PDE_API_ANALYZER_RUNTIME_ARCHIVE")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)

  private fun extractZip(zip: Path, destination: Path) {
    ZipInputStream(zip.inputStream()).use { input ->
      generateSequence { input.nextEntry }.forEach { entry ->
        val output = destination.resolve(entry.name).normalize()
        require(output.startsWith(destination)) { "Runtime zip entry escapes destination: ${entry.name}" }
        if (entry.isDirectory) {
          output.createDirectories()
        } else {
          output.parent.createDirectories()
          output.outputStream().use { input.copyTo(it) }
        }
        input.closeEntry()
      }
    }
  }

  private fun ensureConfiguration(runtimeRoot: Path) {
    val configDir = runtimeRoot.resolve("config").createDirectories()
    val bundlesInfo = configDir.resolve("org.eclipse.equinox.simpleconfigurator/bundles.info")
    bundlesInfo.parent.createDirectories()
    writeBundlesInfo(bundlesInfo, runtimeRoot.resolve("plugins"))
    writeConfigIni(configDir.resolve("config.ini"), runtimeRoot, bundlesInfo)
    runtimeRoot.resolve("workspace").createDirectories()
  }

  private fun writeConfigIni(configIni: Path, runtimeRoot: Path, bundlesInfo: Path) {
    val framework = findBundle(runtimeRoot.resolve("plugins"), "org.eclipse.osgi")
      ?: throw IOException("Unable to locate org.eclipse.osgi in ${runtimeRoot.resolve("plugins")}")
    val osgiBundles = buildList {
      add("org.eclipse.equinox.simpleconfigurator@1:start")
      if (findBundle(runtimeRoot.resolve("plugins"), "org.apache.felix.scr") != null) {
        add("org.apache.felix.scr@2:start")
      }
    }.joinToString(",")
    configIni.writeText(
      listOf(
        "#Configuration File",
        "eclipse.application=${DirectApiAnalyzerApplication.APPLICATION_ID}",
        "eclipse.p2.data.area=@config.dir/.p2",
        "org.eclipse.equinox.simpleconfigurator.configUrl=${bundlesInfo.toUri()}",
        "org.eclipse.update.reconcile=false",
        "osgi.bundles=$osgiBundles",
        "osgi.bundles.defaultStartLevel=4",
        "osgi.configuration.cascaded=false",
        "osgi.framework=${framework.toUri()}",
        "osgi.install.area=${runtimeRoot.toUri()}"
      ).joinToString(System.lineSeparator()) + System.lineSeparator(),
      StandardCharsets.UTF_8
    )
  }

  private fun writeBundlesInfo(bundlesInfo: Path, plugins: Path) {
    val entries = Files.list(plugins).use { stream ->
      stream
        .map { path -> readBundleMetadata(path)?.let { metadata -> BundleEntry(metadata.bsn, metadata.version, bundleLocation(path)) } }
        .filter { it != null }
        .map { it!! }
        .sorted(Comparator.comparing(BundleEntry::bsn))
        .toList()
    }
    bundlesInfo.writeText(
      (listOf("#version=1") + entries.map { "${it.bsn},${it.version},${it.location},4,true" })
        .joinToString(System.lineSeparator()) + System.lineSeparator(),
      StandardCharsets.UTF_8
    )
  }

  private fun bundleLocation(path: Path): String {
    val uri = path.toUri().toString()
    return if (path.isDirectory() && !uri.endsWith("/")) "$uri/" else uri
  }

  private fun findBundle(plugins: Path, bsn: String): Path? = Files.list(plugins).use { stream ->
    stream.filter { readBundleMetadata(it)?.bsn == bsn }.findFirst().orElse(null)
  }

  private fun javaExecutable(): Path = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")

  private fun bundleJar(
    bsn: String,
    version: String,
    sources: Map<String, String>,
    requireBundle: String? = null,
    classpath: List<AnalyzerBundleArtifact> = emptyList()
  ): AnalyzerBundleArtifact {
    val dir = Files.createTempDirectory(temp.root.toPath(), "${bsn.replace('.', '-')}-${version.replace('.', '-')}-")
    val classes = dir.resolve("classes")
    Files.createDirectories(classes)
    sources.forEach { (relative, content) ->
      val file = dir.resolve("src").resolve(relative)
      Files.createDirectories(file.parent)
      Files.writeString(file, content)
    }
    compileJava(dir.resolve("src"), classes, classpath.map { it.path })
    val jar = dir.resolve("$bsn-$version.jar")
    writeBundleJar(jar, classes, bsn, version, requireBundle)
    return AnalyzerBundleArtifact(bsn, version, jar)
  }

  private fun compileJava(sourceRoot: Path, outputRoot: Path, classpath: List<Path>) {
    val sources = Files.walk(sourceRoot).use { stream ->
      stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.map { it.toString() }.toList()
    }
    val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler is required for analyzer harness tests")
    val args = mutableListOf("-d", outputRoot.toString())
    if (classpath.isNotEmpty()) {
      args += listOf("-classpath", classpath.joinToString(File.pathSeparator) { it.toString() })
    }
    args += sources
    val exit = compiler.run(null, null, null, *args.toTypedArray())
    check(exit == 0) { "javac failed with exit code $exit" }
  }

  private fun writeBundleJar(path: Path, classes: Path, bsn: String, version: String, requireBundle: String?) {
    val manifest = Manifest().apply {
      mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
      mainAttributes.putValue("Bundle-ManifestVersion", "2")
      mainAttributes.putValue("Bundle-SymbolicName", bsn)
      mainAttributes.putValue("Bundle-Version", version)
      mainAttributes.putValue("Bundle-Name", bsn)
      mainAttributes.putValue("Bundle-ClassPath", ".")
      mainAttributes.putValue("Export-Package", "$bsn;version=\"$version\"")
      requireBundle?.let { mainAttributes.putValue("Require-Bundle", it) }
    }
    JarOutputStream(path.outputStream(), manifest).use { jar ->
      Files.walk(classes).use { stream ->
        stream.filter { it.isRegularFile() }.sorted().forEach { file ->
          val entry = classes.relativize(file).toString().replace('\\', '/')
          jar.putNextEntry(JarEntry(entry))
          file.inputStream().use { it.copyTo(jar) }
          jar.closeEntry()
        }
      }
    }
  }

  private data class RuntimePaths(val config: Path, val workspace: Path, val launcher: Path)
  private data class BundleMetadata(val bsn: String, val version: String)
  private data class BundleEntry(val bsn: String, val version: String, val location: String)
}
