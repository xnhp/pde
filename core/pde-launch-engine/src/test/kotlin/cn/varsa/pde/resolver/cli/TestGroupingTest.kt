package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.cli.config.LaunchConfig
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.TestEntry
import cn.varsa.pde.resolver.cli.config.WorkspaceBundleConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class TestGroupingTest {

  private fun entry(
    name: String,
    className: String? = "org.example.${name}Test",
    testPluginName: String? = "org.example.host",
    runner: String? = "junit5",
    vmArgs: List<String> = emptyList(),
    dataDir: String? = null,
    debug: Boolean = false
  ) = TestEntry(
    name = name,
    testPluginName = testPluginName,
    className = className,
    runner = runner,
    debug = debug,
    vmArgs = vmArgs,
    dataDir = dataDir
  )

  private fun names(groups: List<List<TestEntry>>): List<List<String?>> = groups.map { group -> group.map { it.name } }

  @Test
  fun `entries sharing a launch shape collapse into one group`() {
    val groups = groupTestEntries(listOf(entry("a"), entry("b"), entry("c")))

    assertEquals(listOf(listOf("a", "b", "c")), names(groups))
  }

  @Test
  fun `differing runner, vm args or directories keep entries apart`() {
    val entries = listOf(
      entry("a"),
      entry("b", runner = "junit4"),
      entry("c", vmArgs = listOf("-Xmx4g")),
      entry("d", dataDir = "./other-data"),
      entry("e", debug = true),
      entry("f", testPluginName = "org.example.other")
    )

    val groups = groupTestEntries(entries)

    assertEquals(listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d"), listOf("e"), listOf("f")), names(groups))
  }

  @Test
  fun `runner casing does not split a group`() {
    val groups = groupTestEntries(listOf(entry("a", runner = "JUnit5"), entry("b", runner = "junit5")))

    assertEquals(listOf(listOf("a", "b")), names(groups))
  }

  @Test
  fun `entries without a class name run on their own`() {
    val entries = listOf(entry("a"), entry("suite", className = null), entry("b"))

    val groups = groupTestEntries(entries)

    assertEquals(listOf(listOf("a", "b"), listOf("suite")), names(groups))
  }

  @Test
  fun `groups keep first-occurrence order`() {
    val entries = listOf(
      entry("a"),
      entry("b", runner = "junit4"),
      entry("c"),
      entry("d", runner = "junit4")
    )

    val groups = groupTestEntries(entries)

    assertEquals(listOf(listOf("a", "c"), listOf("b", "d")), names(groups))
  }

  @Test
  fun `a fragment test plugin groups with its host`() {
    val baseDir = Files.createTempDirectory("pde-test-grouping")
    writeBundle(baseDir.resolve("host"), bsn = "org.example.host")
    writeBundle(baseDir.resolve("tests"), bsn = "org.example.host.tests", fragmentHost = "org.example.host")
    val context = LaunchConfigContext(
      file = baseDir.resolve("pde.yaml"),
      baseDir = baseDir,
      config = LaunchConfig(
        bundles = listOf(
          WorkspaceBundleConfig(path = "host"),
          WorkspaceBundleConfig(path = "tests")
        )
      ),
      workingDir = Paths.get("").toAbsolutePath()
    )

    val fragmentHosts = workspaceFragmentHosts(context)
    assertEquals(mapOf("org.example.host.tests" to "org.example.host"), fragmentHosts)

    val groups = groupTestEntries(
      listOf(entry("a"), entry("b", testPluginName = "org.example.host.tests")),
      fragmentHosts
    )

    assertEquals(listOf(listOf("a", "b")), names(groups))
  }

  @Test
  fun `a group of several classes is passed as classnames`() {
    val context = testContext()

    val programArgs = applyTestEntries(context, listOf(entry("a"), entry("b"))).runtime.programArgs

    assertEquals(
      listOf(
        "-testpluginname", "org.example.host",
        "-classnames", "org.example.aTest", "org.example.bTest",
        "-testLoaderClass", "org.eclipse.jdt.internal.junit5.runner.JUnit5TestLoader",
        "-loaderpluginname", "org.eclipse.jdt.junit5.runtime"
      ),
      programArgs
    )
  }

  @Test
  fun `a single entry still uses classname`() {
    val context = testContext()

    val programArgs = applyTestEntries(context, listOf(entry("a"))).runtime.programArgs

    assertEquals(
      listOf(
        "-testpluginname", "org.example.host",
        "-classname", "org.example.aTest",
        "-testLoaderClass", "org.eclipse.jdt.internal.junit5.runner.JUnit5TestLoader",
        "-loaderpluginname", "org.eclipse.jdt.junit5.runtime"
      ),
      programArgs
    )
  }

  @Test
  fun `strategy comes from the config unless a flag overrides it`() {
    assertEquals(TestStrategy.ISOLATED, resolveTestStrategy(null, merged = false, isolated = false))
    assertEquals(TestStrategy.ISOLATED, resolveTestStrategy("isolated", merged = false, isolated = false))
    assertEquals(TestStrategy.MERGED, resolveTestStrategy("merged", merged = false, isolated = false))
    assertEquals(TestStrategy.MERGED, resolveTestStrategy("MERGED", merged = false, isolated = false))
    assertEquals(TestStrategy.ISOLATED, resolveTestStrategy("merged", merged = false, isolated = true))
    assertEquals(TestStrategy.MERGED, resolveTestStrategy("isolated", merged = true, isolated = false))
    assertEquals(TestStrategy.ISOLATED, resolveTestStrategy("nonsense", merged = false, isolated = false))
  }

  private fun testContext() = LaunchConfigContext(
    file = Paths.get("pde.yaml"),
    baseDir = Paths.get("").toAbsolutePath(),
    config = LaunchConfig(),
    workingDir = Paths.get("").toAbsolutePath()
  )

  private fun writeBundle(dir: Path, bsn: String, fragmentHost: String? = null) {
    dir.resolve("META-INF").createDirectories()
    val hostLine = fragmentHost?.let { "\nFragment-Host: $it" }.orEmpty()
    dir.resolve("META-INF").resolve("MANIFEST.MF").writeText(
      "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: $bsn\nBundle-Version: 1.0.0$hostLine\n"
    )
  }
}
