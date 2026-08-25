package cn.varsa.pde.resolver.cli

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProxySelector
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.nio.channels.UnresolvedAddressException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Logger
import javax.net.ssl.SSLException

/** CLI flag that disables [UpdateSiteReachability.check] for one invocation. */
internal const val SKIP_REACHABILITY_CHECK_FLAG = "skip-reachability-check"
internal const val SKIP_REACHABILITY_CHECK_DESCRIPTION =
  "Do not probe remote update sites before running p2 (use when the check misfires, e.g. behind a captive proxy)"

internal data class UnreachableUpdateSite(val uri: URI, val error: String)

/**
 * Fail-fast probe for the remote p2 update sites a command is about to download from.
 *
 * The common failure is a developer who is not on the VPN: p2 then spends minutes retrying every artifact
 * before giving up with a wall of stack traces. This probe sends one HEAD request per distinct remote
 * repository (in parallel, short timeout) and reports the sites whose host cannot be reached at all.
 *
 * Any HTTP response - including 401/403/404 - counts as reachable: p2 will handle authentication and
 * missing files itself. Only DNS, connect, route and timeout failures count as unreachable. `file:` and
 * other non-HTTP locations are never probed.
 */
internal object UpdateSiteReachability {
  private val logger: Logger = Logger.getLogger("pde-launch-engine")
  private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)

  /** Distinct `http(s)` repositories among [repositories], in first-seen order. */
  fun remoteRepositories(repositories: List<URI>): List<URI> = repositories
    .filter { it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true) }
    .distinct()

  /**
   * Probes the remote repositories among [repositories] in parallel and returns the unreachable ones.
   * Honours the JVM proxy settings ([ProxySelector.getDefault], i.e. `-Dhttps.proxyHost=...`).
   */
  fun probe(repositories: List<URI>, timeout: Duration = DEFAULT_TIMEOUT): List<UnreachableUpdateSite> {
    val remote = remoteRepositories(repositories)
    if (remote.isEmpty()) return emptyList()
    // The probes run on their own pool; the client keeps its default executor. Sharing one pool deadlocks:
    // a blocked probe thread would starve the client's internal tasks that complete the very same request.
    val executor = Executors.newFixedThreadPool(remote.size.coerceAtMost(8))
    try {
      val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(ProxySelector.getDefault())
        .build()
      val futures = remote.map { uri -> uri to executor.submit<String?> { probeOne(client, uri, timeout) } }
      // Safety net above the per-request timeout so a stuck resolver can never hang the CLI.
      val deadline = timeout.multipliedBy(2).plusSeconds(2)
      return futures.mapNotNull { (uri, future) ->
        val error = try {
          future.get(deadline.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
          future.cancel(true)
          "timeout"
        }
        error?.let { UnreachableUpdateSite(uri, it) }
      }
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(1, TimeUnit.SECONDS)
    }
  }

  /** @return `null` when reachable, otherwise a one-line description of the failure. */
  private fun probeOne(client: HttpClient, uri: URI, timeout: Duration): String? {
    val request = HttpRequest.newBuilder(uri)
      .method("HEAD", HttpRequest.BodyPublishers.noBody())
      .timeout(timeout)
      .build()
    return try {
      val response = client.send(request, HttpResponse.BodyHandlers.discarding())
      logger.fine("Update site reachable: $uri (HTTP ${response.statusCode()})")
      null
    } catch (e: SSLException) {
      // TLS problems mean the host answered; certificate/authority handling is p2's job.
      logger.fine("Update site answered with a TLS error, treating as reachable: $uri (${e.message})")
      null
    } catch (e: IOException) {
      describe(e)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      "interrupted"
    }
  }

  private fun describe(e: IOException): String {
    // HttpClient wraps the informative exception around a low-level root (ClosedChannelException,
    // UnresolvedAddressException) and a generic ConnectException around a DNS failure, so classify by the
    // most specific exception anywhere in the chain.
    val chain = generateSequence<Throwable>(e) { it.cause }.toList()
    val label = when {
      chain.any { it is UnknownHostException || it is UnresolvedAddressException } -> "unknown host"
      chain.any { it is HttpConnectTimeoutException } -> "connect timeout"
      chain.any { it is HttpTimeoutException || it is SocketTimeoutException } -> "timeout"
      chain.any { it is NoRouteToHostException } -> "no route to host"
      chain.any { it is ConnectException } -> "connection refused"
      chain.any { it is SocketException } -> "socket error"
      else -> e.javaClass.simpleName
    }
    val detail = chain.firstNotNullOfOrNull { it.message?.takeIf { m -> m.isNotBlank() && m != label } }
    return if (detail != null) "$label: $detail" else label
  }

  /**
   * Runs the probe for [commandLabel] and logs a diagnosis when something is unreachable.
   *
   * @return `true` when the command may proceed (all sites reachable, nothing remote, or [skip]).
   */
  fun check(repositories: List<URI>, skip: Boolean, commandLabel: String): Boolean {
    if (skip) {
      logger.info("Skipping update-site reachability check (--$SKIP_REACHABILITY_CHECK_FLAG).")
      return true
    }
    val remote = remoteRepositories(repositories)
    if (remote.isEmpty()) return true
    logger.info("Checking that ${remote.size} update site(s) are reachable...")
    val unreachable = probe(remote)
    if (unreachable.isEmpty()) return true
    logger.severe(
      buildString {
        appendLine("$commandLabel: ${unreachable.size} of ${remote.size} update site(s) are unreachable:")
        unreachable.forEach { appendLine("  - ${it.uri} (${it.error})") }
        appendLine("Downloads from these sites would fail. Check your network connection and VPN")
        appendLine("(internal KNIME update sites are only reachable from the company network / VPN),")
        append("or pass --$SKIP_REACHABILITY_CHECK_FLAG to run anyway.")
      }
    )
    return false
  }
}
