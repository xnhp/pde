package cn.varsa.pde.remoterunner

import java.net.InetSocketAddress
import java.net.ServerSocket

class PortAllocator(
  private val host: String,
  private val fixedPort: Int?,
  private val range: IntRange?
) {
  fun bind(): ServerSocket {
    if (fixedPort != null && range != null) {
      error("Specify either --listen-port or --port-range, not both")
    }
    fixedPort?.let { return bindToPort(it) }
    range?.let { return bindToFirstAvailable(it) }
    return bindToPort(0)
  }

  private fun bindToFirstAvailable(range: IntRange): ServerSocket {
    for (port in range) {
      runCatching { return bindToPort(port) }
    }
    error("No available port in range ${range.first}-${range.last}")
  }

  private fun bindToPort(port: Int): ServerSocket {
    require(port in 0..65535) { "Port out of range: $port" }
    return ServerSocket().apply {
      reuseAddress = true
      bind(InetSocketAddress(host, port))
    }
  }
}

fun parsePortRange(spec: String?): IntRange? {
  if (spec.isNullOrBlank()) return null
  val cleaned = spec.trim()
  val parts = cleaned.split("-", limit = 2)
  require(parts.size == 2) { "Port range must be start-end" }
  val start = parts[0].trim().toInt()
  val end = parts[1].trim().toInt()
  require(start in 0..65535 && end in 0..65535) { "Port values out of range" }
  require(start <= end) { "Port range start must be <= end" }
  return start..end
}
