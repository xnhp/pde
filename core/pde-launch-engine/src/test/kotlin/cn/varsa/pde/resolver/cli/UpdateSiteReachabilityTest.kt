package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import com.sun.net.httpserver.HttpServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateSiteReachabilityTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  private fun closedPortUri(): URI {
    val port = ServerSocket(0).use { it.localPort }
    return URI("http://127.0.0.1:$port/closed/")
  }

  private fun withServer(status: Int, block: (URI) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.sendResponseHeaders(status, -1)
      exchange.close()
    }
    server.start()
    try {
      block(URI("http://127.0.0.1:${server.address.port}/repo/"))
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `remote repositories are filtered and deduplicated`() {
    val remote = UpdateSiteReachability.remoteRepositories(
      listOf(
        URI("https://update.knime.com/analytics-platform/5.4"),
        URI("file:///tmp/local-repo"),
        URI("https://update.knime.com/analytics-platform/5.4"),
        URI("http://mirror.example.org/p2")
      )
    )
    assertEquals(
      listOf(URI("https://update.knime.com/analytics-platform/5.4"), URI("http://mirror.example.org/p2")),
      remote
    )
  }

  @Test
  fun `any http response counts as reachable`() {
    for (status in listOf(200, 401, 403, 404)) {
      withServer(status) { uri ->
        assertEquals(emptyList(), UpdateSiteReachability.probe(listOf(uri)), "status $status")
      }
    }
  }

  @Test
  fun `closed port is reported as unreachable with the failing url`() {
    val closed = closedPortUri()
    withServer(200) { open ->
      val result = UpdateSiteReachability.probe(listOf(open, closed), Duration.ofSeconds(3))
      assertEquals(1, result.size, "got $result")
      assertEquals(closed, result.single().uri)
      assertTrue(result.single().error.contains("connection refused"), result.single().error)
    }
  }

  @Test
  fun `unknown host is reported as unreachable`() {
    val result = UpdateSiteReachability.probe(listOf(URI("https://no-such-host.invalid/p2")), Duration.ofSeconds(5))
    assertEquals(1, result.size, "got $result")
    assertTrue(result.single().error.contains("unknown host"), result.single().error)
  }

  @Test
  fun `check passes for file repositories and when skipped`() {
    assertTrue(UpdateSiteReachability.check(listOf(URI("file:///tmp/repo")), skip = false, commandLabel = "test"))
    assertTrue(UpdateSiteReachability.check(listOf(closedPortUri()), skip = true, commandLabel = "test"))
    assertFalse(UpdateSiteReachability.check(listOf(closedPortUri()), skip = false, commandLabel = "test"))
  }

  @Test
  fun `target install fails early when a target repository is unreachable`() {
    val baseDir = tmp.newFolder("cfg").toPath()
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(
      configFile,
      """
        target:
          installer: target-installer.jar
          definition: example.target
        bundles: []
      """.trimIndent()
    )
    Files.writeString(baseDir.resolve("target-installer.jar"), "stub")
    val closed = closedPortUri()
    Files.writeString(
      baseDir.resolve("example.target"),
      """
        <?xml version="1.0" encoding="UTF-8"?>
        <target name="example">
          <locations>
            <location includeConfigurePhase="true" type="InstallableUnit">
              <repository location="$closed"/>
              <unit id="org.example.feature.group" version="0.0.0"/>
            </location>
          </locations>
        </target>
      """.trimIndent()
    )

    var launched = false
    val exit = targetMain(arrayOf("--config", configFile.toString()), runInstallerLauncher = { _, _, _, _, _ ->
      launched = true
      0
    })
    assertEquals(2, exit)
    assertFalse(launched, "installer must not run when an update site is unreachable")

    val skipped = targetMain(
      arrayOf("--config", configFile.toString(), "--skip-reachability-check"),
      runInstallerLauncher = { _, _, _, _, _ ->
        launched = true
        0
      }
    )
    assertEquals(0, skipped)
    assertTrue(launched)
  }

  @Test
  fun `build inputs expose the effective repositories of the target file`() {
    val baseDir = tmp.newFolder("inputs").toPath()
    val configFile = baseDir.resolve("pde.yaml")
    Files.writeString(configFile, "target:\n  definition: example.target\nbundles: []\n")
    val target = baseDir.resolve("example.target")
    Files.writeString(
      target,
      """
        <target name="x"><locations><location type="InstallableUnit">
          <repository location="https://update.knime.com/analytics-platform/5.4"/>
          <repository location="file:///tmp/repo"/>
        </location></locations></target>
      """.trimIndent()
    )
    val context = LaunchConfigLoader.load(configFile)
    val inputs = buildTargetInstallInputs(context, baseDir.resolve("installer.jar"), target)
    assertEquals(
      listOf(URI("https://update.knime.com/analytics-platform/5.4"), URI("file:///tmp/repo")),
      inputs.repositories
    )
  }
}
