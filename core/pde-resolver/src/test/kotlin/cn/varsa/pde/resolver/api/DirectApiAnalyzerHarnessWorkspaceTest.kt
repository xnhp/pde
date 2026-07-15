package cn.varsa.pde.resolver.api

import cn.varsa.pde.resolver.workspace.WorkspaceProjectSpec
import cn.varsa.pde.resolver.workspace.WorkspaceSetupInput
import cn.varsa.pde.resolver.workspace.WorkspaceSetupInputJson
import cn.varsa.pde.resolver.workspace.WorkspaceSetupService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

class DirectApiAnalyzerHarnessWorkspaceTest {
  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test
  fun `bundleComponent created when workspaceProjectName is null`() {
    val sources = mapOf(
      "org/example/api/Example.java" to """
        package org.example.api;
        public class Example {
          public void kept() {}
        }
      """.trimIndent()
    )
    val baseline = bundleJar("org.example.nowsproj", "1.0.0", sources)
    val current = bundleJar("org.example.nowsproj", "1.1.0", sources)

    val report = analyzeBatchThroughEquinox(
      bundles = listOf(current),
      baselines = listOf(baseline),
      outputReportPaths = listOf(temp.root.toPath().resolve("report.json")),
      workspaceDataDir = null
    ).single()

    assertOnlyMinorVersionAdvice(report)
  }

  @Test
  fun `bundleComponent created when workspaceRoot is null`() {
    val sources = mapOf(
      "org/example/api/Example.java" to """
        package org.example.api;
        public class Example {
          public void kept() {}
        }
      """.trimIndent()
    )
    val baseline = bundleJar("org.example.nowsroot", "1.0.0", sources)
    val current = bundleJar("org.example.nowsroot", "1.1.0", sources)

    val report = analyzeBatchThroughEquinox(
      bundles = listOf(current),
      baselines = listOf(baseline),
      outputReportPaths = listOf(temp.root.toPath().resolve("report.json")),
      workspaceDataDir = null
    ).single()

    assertOnlyMinorVersionAdvice(report)
  }

  @Test
  fun `bundleComponent created when project does not exist in workspace`() {
    val sources = mapOf(
      "org/example/api/Example.java" to """
        package org.example.api;
        public class Example {
          public void kept() {}
        }
      """.trimIndent()
    )
    val baseline = bundleJar("org.example.noproject", "1.0.0", sources)
    val current = AnalyzerBundleArtifact(
      bundleSymbolicName = "org.example.noproject",
      version = "1.1.0",
      path = bundleJar("org.example.noproject", "1.1.0", sources).path,
      workspaceProjectName = "nonexistent-project-name"
    )

    val report = analyzeBatchThroughEquinox(
      bundles = listOf(current),
      baselines = listOf(baseline),
      outputReportPaths = listOf(temp.root.toPath().resolve("report.json")),
      workspaceDataDir = temp.root.toPath().resolve("ws-data").also { Files.createDirectories(it) }.toString()
    ).single()

    assertOnlyMinorVersionAdvice(report)
  }

  @Test
  fun `projectComponent reports missing since tag for newly added public method`() {
    val setupArchive = workspaceSetupRuntimeArchive()
    assumeTrue(
      "Set PDE_WORKSPACE_SETUP_RUNTIME_ARCHIVE or -Dpde.workspaceSetup.runtime.archive to run this test",
      setupArchive != null
    )

    val bsn = "org.example.sincetag"
    val baseline = bundleJar(
      bsn,
      "1.0.0",
      mapOf(
        "org/example/api/Example.java" to """
          package org.example.api;
          public class Example {
            /**
             * @since 1.0
             */
            public void kept() {}
          }
        """.trimIndent()
      ),
      exportPackage = "org.example.api;version=\"1.0.0\""
    )

    val currentDir = explodedBundleDir(
      bsn,
      "1.1.0",
      mapOf(
        "org/example/api/Example.java" to """
          package org.example.api;
          public class Example {
            /**
             * @since 1.0
             */
            public void kept() {}

            /**
             * Deliberately missing an @since tag.
             */
            public void added() {}
          }
        """.trimIndent()
      )
    )

    val dataDir = temp.root.toPath().resolve("ws-data").also { Files.createDirectories(it) }
    runWorkspaceSetup(setupArchive!!, dataDir, bsn, "1.1.0", currentDir)
    val projectName = WorkspaceSetupService.projectName(bsn)

    val current = AnalyzerBundleArtifact(
      bundleSymbolicName = bsn,
      version = "1.1.0",
      path = currentDir,
      workspaceProjectName = projectName,
      sourcePath = currentDir
    )

    val report = analyzeBatchThroughEquinox(
      bundles = listOf(current),
      baselines = listOf(baseline),
      outputReportPaths = listOf(temp.root.toPath().resolve("report.json")),
      workspaceDataDir = dataDir.toString()
    ).single()

    val sinceTagProblem = report.problems.singleOrNull { it.category == "since-tags" }
      ?: error("Expected exactly one since-tags problem in ${report.problems}")
    assertEquals(bsn, sinceTagProblem.bundleSymbolicName)
  }

  @Test
  fun `api baseline analysis succeeds with workspaceProjectName equals null`() {
    val sources = mapOf(
      "org/example/api/Example.java" to """
        package org.example.api;
        public class Example {
          public void kept() {}
          public void removed() {}
        }
      """.trimIndent()
    )
    // Export the real code package so its members count as API; without this the removed method is
    // never seen as an API change and no compatibility problem is produced (the default fixture
    // Export-Package uses the BSN as a package name, which does not exist in the jar).
    val baseline = bundleJar("org.example.wsnullreg", "1.0.0", sources, exportPackage = "org.example.api;version=\"1.0.0\"")
    val sourcesV2 = mapOf(
      "org/example/api/Example.java" to """
        package org.example.api;
        public class Example {
          public void kept() {}
        }
      """.trimIndent()
    )
    val current = AnalyzerBundleArtifact(
      bundleSymbolicName = "org.example.wsnullreg",
      version = "1.1.0",
      path = bundleJar("org.example.wsnullreg", "1.1.0", sourcesV2, exportPackage = "org.example.api;version=\"1.1.0\"").path,
      workspaceProjectName = null
    )

    val report = analyzeBatchThroughEquinox(
      bundles = listOf(current),
      baselines = listOf(baseline),
      outputReportPaths = listOf(temp.root.toPath().resolve("report.json")),
      workspaceDataDir = null
    ).single()

    assertRemovedPublicMethodProblem(report, "org.example.wsnullreg")
  }

  private fun analyzeBatchThroughEquinox(
    bundles: List<AnalyzerBundleArtifact>,
    baselines: List<AnalyzerBundleArtifact>,
    outputReportPaths: List<Path>,
    dependencies: List<AnalyzerBundleArtifact> = emptyList(),
    workspaceDataDir: String? = null
  ): List<ApiAnalysisReport> {
    require(bundles.size == outputReportPaths.size) { "Expected one report path per current bundle" }
    val runtime = assembleRuntime()
    val inputPath = temp.root.toPath().resolve("batch-analyzer-input.json")
    inputPath.writeText(
      java.lang.String.format(
        java.util.Locale.ROOT,
        "{\"currentBundles\":[%s],\"dependencyArtifacts\":[%s],\"baselineArtifacts\":[%s]%s}",
        bundles.zip(outputReportPaths).joinToString(",") { (bundle, reportPath) ->
          val sp = bundle.sourcePath?.let { ""","sourcePath":"${it.toAbsolutePath().normalize()}"""" } ?: ""
          """{"currentBundle":{"bundleSymbolicName":"${bundle.bundleSymbolicName}","version":"${bundle.version}","path":"${bundle.path.toAbsolutePath().normalize()}"${if (bundle.workspaceProjectName != null) ""","workspaceProjectName":"${bundle.workspaceProjectName}"""" else ""}$sp},"outputReportPath":"${reportPath.toAbsolutePath().normalize()}"}"""
        },
        dependencies.joinToString(",") { dep ->
          """{"bundleSymbolicName":"${dep.bundleSymbolicName}","version":"${dep.version}","path":"${dep.path.toAbsolutePath().normalize()}"}"""
        },
        baselines.joinToString(",") { baseline ->
          """{"bundleSymbolicName":"${baseline.bundleSymbolicName}","version":"${baseline.version}","path":"${baseline.path.toAbsolutePath().normalize()}"}"""
        },
        if (workspaceDataDir != null) ""","workspaceDataDir":"$workspaceDataDir"""" else ""
      )
    )

    val output = runAnalyzerProcess(runtime, inputPath, workspaceDataDir)
    return outputReportPaths.map { reportPath ->
      assertTrue("Analyzer did not write report $reportPath. Output:\n$output", reportPath.exists())
      ApiAnalysisReportJson.read(reportPath)
    }
  }

  private fun runAnalyzerProcess(runtime: RuntimePaths, inputPath: Path, workspaceDataDir: String? = null): String {
    val process = ProcessBuilder(
      javaExecutable().toString(),
      "-jar", runtime.launcher.toString(),
      "-application", DirectApiAnalyzerApplication.APPLICATION_ID,
      "-data", workspaceDataDir ?: runtime.workspace.toString(),
      "-configuration", runtime.config.toString(),
      "-consoleLog",
      "--input", inputPath.toString()
    )
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertTrue("Analyzer process timed out. Output:\n$output", process.waitFor(60, TimeUnit.SECONDS))
    assertEquals("Analyzer process failed. Output:\n$output", 0, process.exitValue())
    // Known benign Eclipse shutdown artifacts: these FrameworkEvent ERRORs are thrown
    // during OSGi bundle stop when Workspace.close() races with outstanding scheduling rules
    // (endRule without matching beginRule). Same harmless pattern as the already-filtered
    // LaunchManager.shutdown / DebugPlugin.stop shutdown noise.
    val outputWithoutShutdownNoise = output.replace(
      Regex("!MESSAGE FrameworkEvent ERROR.*?endRule without matching beginRule.*?(?=\\n!|\\n\\n|\\z)", setOf(RegexOption.DOT_MATCHES_ALL)),
      ""
    )
    assertTrue("Analyzer process printed an Equinox framework error. Output:\n$outputWithoutShutdownNoise", "FrameworkEvent ERROR" !in outputWithoutShutdownNoise)
    assertTrue("Analyzer process printed the old shutdown exception. Output:\n$output", "LaunchManager.shutdown" !in output)
    assertTrue("Analyzer process printed the old shutdown exception. Output:\n$output", "DebugPlugin.stop" !in output)
    return output
  }

  private fun assertRemovedPublicMethodProblem(report: ApiAnalysisReport, bsn: String) {
    val problem = report.problems.singleOrNull { problem ->
      problem.category == "compatibility" &&
        problem.messageArguments.any { it.contains("removed") || it.contains("Example") }
    } ?: error("Expected one removed public method compatibility problem in ${report.problems}")
    assertTrue("Problem id must be populated", problem.problemId != 0)
    assertEquals(bsn, problem.bundleSymbolicName)
    assertEquals("$bsn:1.0.0", problem.baselineComponentId)
    assertEquals("$bsn:1.1.0", problem.currentComponentId)
  }

  // The three BundleComponent-fallback tests compare byte-identical baseline (1.0.0) and current
  // (1.1.0) sources, so PDE API Tools' default (empty-preferences) version-management check correctly
  // reports one "the minor version should be the same, no new APIs" advisory. That is real tool output
  // for production too (apiAnalyzeMain also passes empty preferences), so the tests assert its presence
  // rather than an empty list -- and assert positively (exactly one version problem, tied to this
  // bundle's 1.0.0->1.1.0 comparison) so a silently empty analyzer can't make them pass vacuously.
  private fun assertOnlyMinorVersionAdvice(report: ApiAnalysisReport) {
    val problem = report.problems.singleOrNull()
      ?: error("Expected exactly one (minor-version advice) problem but got ${report.problems}")
    assertEquals("version", problem.category)
    assertTrue(
      "Expected minor-version-management advice, got: ${problem.message}",
      problem.message?.contains("minor version", ignoreCase = true) == true
    )
    assertEquals("${problem.bundleSymbolicName}:1.0.0", problem.baselineComponentId)
    assertEquals("${problem.bundleSymbolicName}:1.1.0", problem.currentComponentId)
  }

  private fun assembleRuntime(runtimeArchive: Path? = analyzerRuntimeArchive()): RuntimePaths {
    assumeTrue(
      "Set PDE_API_ANALYZER_RUNTIME_ARCHIVE or -Dpde.apiAnalyzer.runtime.archive to run the Equinox analyzer harness test",
      runtimeArchive != null
    )

    val root = Files.createTempDirectory(temp.root.toPath(), "analyzer-runtime-")
    extractZip(runtimeArchive!!, root)
    val plugins = root.resolve("plugins")
    require(plugins.isDirectory()) { "Analyzer runtime archive does not contain a plugins/ directory: $runtimeArchive" }

    ensureConfiguration(root)
    val launcher = findBundle(plugins, "org.eclipse.equinox.launcher")
      ?: error("Analyzer runtime archive does not contain org.eclipse.equinox.launcher: $runtimeArchive")
    return RuntimePaths(root.resolve("config"), root.resolve("workspace"), launcher)
  }

  private fun analyzerRuntimeArchive(): Path? =
    System.getProperty("pde.apiAnalyzer.runtime.archive")
      ?.takeIf(String::isNotBlank)
      ?.let(Path::of)
      ?: System.getenv("PDE_API_ANALYZER_RUNTIME_ARCHIVE")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)

  private fun workspaceSetupRuntimeArchive(): Path? =
    System.getProperty("pde.workspaceSetup.runtime.archive")
      ?.takeIf(String::isNotBlank)
      ?.let(Path::of)
      ?: System.getenv("PDE_WORKSPACE_SETUP_RUNTIME_ARCHIVE")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)

  /**
   * Materializes an on-disk (exploded, not jarred) bundle directory with a real
   * META-INF/MANIFEST.MF and src/ sources, matching the shape of a real workspace bundle
   * (as opposed to [bundleJar]'s in-memory jar — workspace setup needs a real directory to
   * point the project's location at).
   */
  private fun explodedBundleDir(bsn: String, version: String, sources: Map<String, String>): Path {
    val dir = Files.createTempDirectory(temp.root.toPath(), "${bsn.replace('.', '-')}-${version.replace('.', '-')}-exploded-")
    sources.forEach { (relative, content) ->
      val file = dir.resolve("src").resolve(relative)
      Files.createDirectories(file.parent)
      Files.writeString(file, content)
    }
    compileJava(dir.resolve("src"), dir.resolve("bin").createDirectories(), emptyList())
    val manifestDir = dir.resolve("META-INF").createDirectories()
    val exportedPackages = sources.keys
      .map { it.removeSuffix(".java") }
      .map { it.replace('/', '.') }
      .map { it.substringBeforeLast('.') }
      .distinct()
      .joinToString(",") { "$it;version=\"$version\"" }
    manifestDir.resolve("MANIFEST.MF").writeText(
      listOf(
        "Manifest-Version: 1.0",
        "Bundle-ManifestVersion: 2",
        "Bundle-SymbolicName: $bsn",
        "Bundle-Version: $version",
        "Bundle-Name: $bsn",
        "Export-Package: $exportedPackages"
      ).joinToString(System.lineSeparator()) + System.lineSeparator(),
      StandardCharsets.UTF_8
    )
    return dir
  }

  private fun runWorkspaceSetup(runtimeArchive: Path, dataDir: Path, bsn: String, version: String, bundleDir: Path) {
    val runtime = assembleRuntime(runtimeArchive)
    val input = WorkspaceSetupInput(
      projects = listOf(
        WorkspaceProjectSpec(
          bsn = bsn,
          version = version,
          bundlePath = bundleDir.toAbsolutePath().normalize().toString(),
          sourceRoots = listOf("src"),
          outputDirectory = "bin"
        )
      ),
      targetClasspath = emptyList()
    )
    val inputPath = temp.root.toPath().resolve("workspace-setup-input-$bsn.json")
    inputPath.writeText(WorkspaceSetupInputJson.write(input))

    val process = ProcessBuilder(
      javaExecutable().toString(),
      "-jar", runtime.launcher.toString(),
      "-application", "cn.varsa.pde.workspace_setup.workspace_setup",
      "-data", dataDir.toString(),
      "-configuration", runtime.config.toString(),
      "-consoleLog",
      "--input", inputPath.toString()
    )
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertTrue("Workspace setup process timed out. Output:\n$output", process.waitFor(60, TimeUnit.SECONDS))
    assertEquals("Workspace setup process failed. Output:\n$output", 0, process.exitValue())
  }

  private fun bundleJar(
    bsn: String,
    version: String,
    sources: Map<String, String>,
    requireBundle: String? = null,
    exportPackage: String? = null
  ): AnalyzerBundleArtifact {
    val dir = Files.createTempDirectory(temp.root.toPath(), "${bsn.replace('.', '-')}-${version.replace('.', '-')}-")
    val classes = dir.resolve("classes")
    Files.createDirectories(classes)
    sources.forEach { (relative, content) ->
      val file = dir.resolve("src").resolve(relative)
      Files.createDirectories(file.parent)
      Files.writeString(file, content)
    }
    compileJava(dir.resolve("src"), classes, emptyList())
    val effectiveExportPackage = exportPackage ?: "$bsn;version=\"$version\""
    val artifact = dir.resolve("$bsn-$version.jar").also { jar ->
      val mf = java.util.jar.Manifest().apply {
        mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes.putValue("Bundle-ManifestVersion", "2")
        mainAttributes.putValue("Bundle-SymbolicName", bsn)
        mainAttributes.putValue("Bundle-Version", version)
        mainAttributes.putValue("Bundle-Name", bsn)
        mainAttributes.putValue("Bundle-ClassPath", ".")
        mainAttributes.putValue("Export-Package", effectiveExportPackage)
        requireBundle?.let { mainAttributes.putValue("Require-Bundle", it) }
      }
      JarOutputStream(jar.outputStream(), mf).use { jarOut ->
        Files.walk(classes).use { stream ->
          stream.filter { it.isRegularFile() }.sorted().forEach { file ->
            val entry = classes.relativize(file).toString().replace('\\', '/')
            jarOut.putNextEntry(java.util.jar.JarEntry(entry))
            file.inputStream().use { it.copyTo(jarOut) }
            jarOut.closeEntry()
          }
        }
      }
    }
    return AnalyzerBundleArtifact(bsn, version, artifact.toAbsolutePath().normalize())
  }

  private fun compileJava(sourceRoot: Path, outputRoot: Path, classpath: List<Path>) {
    val sources = Files.walk(sourceRoot).use { stream ->
      stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.map { it.toString() }.toList()
    }
    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler is required for analyzer harness tests")
    val args = mutableListOf("-d", outputRoot.toString())
    if (classpath.isNotEmpty()) {
      args += listOf("-classpath", classpath.joinToString(java.io.File.pathSeparator) { it.toString() })
    }
    args += sources
    val exit = compiler.run(null, null, null, *args.toTypedArray())
    check(exit == 0) { "javac failed with exit code $exit" }
  }

  private fun extractZip(zip: Path, destination: Path) {
    java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
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
      ?: throw java.io.IOException("Unable to locate org.eclipse.osgi in ${runtimeRoot.resolve("plugins")}")
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
      java.nio.charset.StandardCharsets.UTF_8
    )
  }

  private fun writeBundlesInfo(bundlesInfo: Path, plugins: Path) {
    val entries = Files.list(plugins).use { stream ->
      stream
        .map { path -> readBundleMetadata(path)?.let { metadata -> BundleEntry(metadata.bsn, metadata.version, bundleLocation(path)) } }
        .filter { it != null }
        .map { it!! }
        .filter { it.bsn != "org.eclipse.equinox.launcher" }
        .sorted(java.util.Comparator.comparing(BundleEntry::bsn))
        .toList()
    }
    bundlesInfo.writeText(
      (listOf("#version=1") + entries.map { "${it.bsn},${it.version},${it.location},4,true" })
        .joinToString(System.lineSeparator()) + System.lineSeparator(),
      java.nio.charset.StandardCharsets.UTF_8
    )
  }

  private fun bundleLocation(path: Path): String {
    val uri = path.toUri().toString()
    return if (path.isDirectory() && !uri.endsWith("/")) "$uri/" else uri
  }

  private fun findBundle(plugins: Path, bsn: String): Path? = Files.list(plugins).use { stream ->
    stream.filter { readBundleMetadata(it)?.bsn == bsn }.findFirst().orElse(null)
  }

  private fun readBundleMetadata(path: Path): BundleMetadata? {
    val manifest = if (path.isDirectory()) {
      path.resolve("META-INF/MANIFEST.MF").takeIf { it.isRegularFile() }?.inputStream()?.use(::Manifest)
    } else {
      runCatching { java.util.jar.JarFile(path.toFile()).use { it.manifest } }.getOrNull()
    } ?: return null
    val attrs = manifest.mainAttributes
    val bsn = attrs.getValue("Bundle-SymbolicName")?.substringBefore(';')?.trim().orEmpty()
    val version = attrs.getValue("Bundle-Version")?.trim().orEmpty()
    if (bsn.isBlank() || version.isBlank()) return null
    return BundleMetadata(bsn, version)
  }

  private fun javaExecutable(): Path = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")

  private data class RuntimePaths(val config: Path, val workspace: Path, val launcher: Path)
  private data class BundleMetadata(val bsn: String, val version: String)
  private data class BundleEntry(val bsn: String, val version: String, val location: String)
}
