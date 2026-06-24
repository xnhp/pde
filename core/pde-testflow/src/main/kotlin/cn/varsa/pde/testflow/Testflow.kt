package cn.varsa.pde.testflow

import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Eclipse application id of the headless KNIME testflow runner. The extension `id="NGTestflowRunner"`
 * is declared in the fragment `org.knime.testing.application`, but a fragment's extensions register
 * under its **host** bundle's namespace (`org.knime.testing`) — so the id is
 * `org.knime.testing.NGTestflowRunner`. Confirmed against the live runtime application registry.
 */
internal const val KNIME_NG_TESTFLOW_RUNNER_APPLICATION = "org.knime.testing.NGTestflowRunner"

/**
 * JVM args every testflow run needs. `-Djava.awt.headless=true` makes KNIME skip view/GUI creation
 * (honored by `KNIMEConstants`/`ViewUtils`) so the run opens no windows / steals no focus; on macOS the
 * NG runner still creates an SWT [Display] for the platform, which requires the Cocoa main thread
 * (`-XstartOnFirstThread`), otherwise SWT crashes the JVM at startup.
 */
internal fun testflowRuntimeVmArgs(osName: String = System.getProperty("os.name").orEmpty()): List<String> =
  buildList {
    val mac = osName.contains("mac", ignoreCase = true)
    if (mac) add("-XstartOnFirstThread")
    add("-Djava.awt.headless=true")
    // macOS: SWT initializes AppKit (so AWT can't fully go headless); run as a background agent so the
    // process shows no dock icon and never steals focus, and any stray AWT window stays in the background.
    if (mac) add("-Dapple.awt.UIElement=true")
  }

/**
 * Force-loads the result-model classes the post-run summary needs, BEFORE launching the framework. The
 * embedded Equinox/OSGi teardown invalidates the app classloader, so a class first referenced only in the
 * post-run summary (e.g. [TestflowSuiteResult]) would fail to load with NoClassDefFoundError (Bug A).
 */
internal fun preloadTestflowResultClasses() {
  check(listOf(TestflowSummary::class.java, TestflowSuiteResult::class.java).size == 2)
}

/**
 * Options for a single KNIME testflow run, mapped onto the headless
 * `org.knime.testing.application.NGTestflowRunner` Eclipse application's program arguments.
 */
internal data class TestflowRunOptions(
  val rootDirs: List<String>,
  val xmlResultDir: String,
  val include: String? = null,
  val timeoutSeconds: Int? = null,
  val loadSaveLoad: Boolean = false,
  val streaming: Boolean = false,
  val views: Boolean = false,
  val dialogs: Boolean = false,
  val checkLogMessages: Boolean = false,
  val ignoreNodeMessages: Boolean = false,
  val deprecated: Boolean = false,
  val workflowVariables: List<String> = emptyList(),
)

/**
 * Translates [TestflowRunOptions] into the program-argument list understood by
 * `NGTestflowRunner` (see TestflowRunnerApplication#extractCommandLineArgs). Flag names mirror
 * that runner exactly; the order is stable to keep the command reproducible and testable.
 */
internal fun buildTestflowProgramArgs(options: TestflowRunOptions): List<String> = buildList {
  options.rootDirs.forEach { add("-root"); add(it) }
  add("-xmlResultDir"); add(options.xmlResultDir)
  options.include?.let { add("-include"); add(it) }
  options.timeoutSeconds?.let { add("-timeout"); add(it.toString()) }
  if (options.loadSaveLoad) add("-loadSaveLoad")
  if (options.streaming) add("-streaming")
  if (options.views) add("-views")
  if (options.dialogs) add("-dialogs")
  if (options.checkLogMessages) add("-logMessages")
  if (options.ignoreNodeMessages) add("-ignoreNodeMessages")
  if (options.deprecated) add("-deprecated")
  options.workflowVariables.forEach { add("-workflow.variable"); add(it) }
}

/**
 * Switches a launch context to the headless testflow runner: selects the [KNIME_NG_TESTFLOW_RUNNER_APPLICATION]
 * application (clearing any product), and appends the runner program args derived from [options].
 * Mirrors how `applyTestEntry` prepares the PDE-JUnit launch.
 */
internal fun applyTestflowRun(
  context: LaunchConfigContext,
  options: TestflowRunOptions,
): LaunchConfigContext = context.copy(
  runtime = context.runtime.copy(
    product = null,
    application = KNIME_NG_TESTFLOW_RUNNER_APPLICATION,
    programArgs = context.runtime.programArgs + buildTestflowProgramArgs(options),
  ),
)

/** Aggregate pass/fail counts parsed from the runner's JUnit-style XML result files. */
internal data class TestflowSummary(val tests: Int, val failures: Int, val errors: Int) {
  /** A run is a success only if at least one testflow ran and none failed or errored. */
  val passed: Boolean get() = tests > 0 && failures == 0 && errors == 0
}

/** Per-suite (one testflow) result parsed from a `<testsuite>` element. */
internal data class TestflowSuiteResult(val name: String, val tests: Int, val failures: Int, val errors: Int) {
  val passed: Boolean get() = failures == 0 && errors == 0
}

/**
 * Parses one [TestflowSuiteResult] per `<testsuite>` in the `*.xml` files the runner wrote anywhere
 * under [resultDir] (it nests them in a tree mirroring each testflow's repository path).
 */
internal fun parseTestflowSuites(resultDir: Path): List<TestflowSuiteResult> {
  if (!Files.isDirectory(resultDir)) return emptyList()
  val suites = mutableListOf<TestflowSuiteResult>()
  val factory = XMLInputFactory.newInstance().apply {
    setProperty(XMLInputFactory.SUPPORT_DTD, false)
    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
  }
  Files.walk(resultDir).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }.forEach { file ->
      Files.newInputStream(file).use { input ->
        val reader = factory.createXMLStreamReader(input)
        try {
          while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "testsuite") {
              suites += TestflowSuiteResult(
                name = reader.getAttributeValue(null, "name") ?: file.fileName.toString(),
                tests = reader.getAttributeValue(null, "tests")?.toIntOrNull() ?: 0,
                failures = reader.getAttributeValue(null, "failures")?.toIntOrNull() ?: 0,
                errors = reader.getAttributeValue(null, "errors")?.toIntOrNull() ?: 0,
              )
            }
          }
        } finally {
          reader.close()
        }
      }
    }
  }
  return suites
}

/**
 * Aggregates the per-suite results into a single pass/fail summary. The runner exits 0 even when
 * testflows fail, so the result files (not the exit code) decide success.
 */
internal fun summarizeTestflowResults(resultDir: Path): TestflowSummary {
  val suites = parseTestflowSuites(resultDir)
  return TestflowSummary(
    tests = suites.sumOf { it.tests },
    failures = suites.sumOf { it.failures },
    errors = suites.sumOf { it.errors },
  )
}

/**
 * Merges every `<testsuite>` the runner wrote under [resultDir] into one JUnit `<testsuites>`
 * document (stripping the per-file XML declarations), for a CI `--report junit-xml:<file>` sink.
 */
internal fun mergeJUnitXml(resultDir: Path): String {
  val blocks = mutableListOf<String>()
  if (Files.isDirectory(resultDir)) {
    Files.walk(resultDir).use { paths ->
      paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }.forEach { file ->
        val body = Files.readString(file).replace(Regex("<\\?xml.*?\\?>", RegexOption.DOT_MATCHES_ALL), "").trim()
        if (body.isNotEmpty()) blocks += body
      }
    }
  }
  return buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("<testsuites>")
    blocks.forEach { appendLine(it) }
    append("</testsuites>")
  }
}

/** Emits TeamCity service messages (one logical test per testflow) for a `--report teamcity` sink. */
internal fun teamCityTestflowMessages(resultDir: Path): List<String> {
  val messages = mutableListOf<String>()
  messages += "##teamcity[testSuiteStarted name='KNIME Testflows']"
  for (suite in parseTestflowSuites(resultDir)) {
    val name = teamCityEscape(suite.name)
    messages += "##teamcity[testStarted name='$name']"
    if (!suite.passed) {
      messages += "##teamcity[testFailed name='$name' message='${suite.failures} failure(s), ${suite.errors} error(s)']"
    }
    messages += "##teamcity[testFinished name='$name']"
  }
  messages += "##teamcity[testSuiteFinished name='KNIME Testflows']"
  return messages
}

private fun teamCityEscape(value: String): String = value
  .replace("|", "||")
  .replace("'", "|'")
  .replace("[", "|[")
  .replace("]", "|]")
  .replace("\n", "|n")
  .replace("\r", "|r")
