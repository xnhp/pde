package cn.varsa.pde.launch

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

object JdtlsSmokeCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde jdtls-smoke")
    val launcherJar by parser.option(
      ArgType.String,
      fullName = "launcher",
      description = "Path to org.eclipse.equinox.launcher_*.jar"
    )
    val configDir by parser.option(
      ArgType.String,
      fullName = "config",
      description = "Path to JDT LS config dir (e.g., config_linux)"
    )
    val rootDir by parser.option(
      ArgType.String,
      fullName = "root",
      description = "Workspace root directory containing projects"
    )
    val dataDir by parser.option(
      ArgType.String,
      fullName = "data",
      description = "JDT LS data directory (defaults to <root>/.jdtls-data)"
    )
    val timeoutMs by parser.option(
      ArgType.Int,
      fullName = "timeout-ms",
      description = "Timeout for initialize response"
    ).default(60000)
    val expectProjects by parser.option(
      ArgType.String,
      fullName = "expect-project",
      description = "Project name expected from java.project.getAll (repeatable)"
    ).multiple()
    val implFile by parser.option(
      ArgType.String,
      fullName = "impl-file",
      description = "Java interface file to request implementations for"
    )
    val implSymbol by parser.option(
      ArgType.String,
      fullName = "impl-symbol",
      description = "Symbol name within impl-file to query"
    )
    val implExpected by parser.option(
      ArgType.String,
      fullName = "impl-expected",
      description = "Expected implementation (file name or identifier; repeatable)"
    ).multiple()
    val symbolQueries by parser.option(
      ArgType.String,
      fullName = "symbol-query",
      description = "Workspace symbol query to validate (repeatable)"
    ).multiple()
    val importProjects by parser.option(
      ArgType.Boolean,
      fullName = "import-projects",
      description = "Run java.project.import before other checks"
    ).default(false)
    val refreshDiagnostics by parser.option(
      ArgType.Boolean,
      fullName = "refresh-diagnostics",
      description = "Run java.project.refreshDiagnostics after import"
    ).default(false)
    val vmArgs by parser.option(
      ArgType.String,
      fullName = "vm-arg",
      description = "Additional JVM arg (repeatable)"
    ).multiple()
    parser.parse(args)

    val launcherPath = launcherJar?.let { Paths.get(it) }
      ?: fail("--launcher is required")
    val configPath = configDir?.let { Paths.get(it) }
      ?: fail("--config is required")
    val rootPath = rootDir?.let { Paths.get(it) }
      ?: fail("--root is required")
    val dataPath = dataDir?.let { Paths.get(it) }
      ?: rootPath.resolve(".jdtls-data")

    if (!Files.isRegularFile(launcherPath)) fail("Launcher jar not found: $launcherPath")
    if (!Files.isDirectory(configPath)) fail("Config directory not found: $configPath")
    if (!Files.isDirectory(rootPath)) fail("Root directory not found: $rootPath")
    Files.createDirectories(dataPath)

    val defaultVmArgs = listOf(
      "-Declipse.application=org.eclipse.jdt.ls.core.id1",
      "-Dosgi.bundles.defaultStartLevel=4",
      "-Declipse.product=org.eclipse.jdt.ls.core.product",
      "-Dlog.level=ALL",
      "-Xmx1G",
      "--add-modules=ALL-SYSTEM",
      "--add-opens", "java.base/java.util=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
    val command = listOf("java") + defaultVmArgs + vmArgs + listOf(
      "-jar",
      launcherPath.toAbsolutePath().normalize().toString(),
      "-configuration",
      configPath.toAbsolutePath().normalize().toString(),
      "-data",
      dataPath.toAbsolutePath().normalize().toString()
    )

    val process = ProcessBuilder(command)
      .directory(rootPath.toFile())
      .redirectErrorStream(false)
      .start()

    val stderrBuffer = StringBuilder()
    val stderrThread = Thread { drainStream(process.errorStream, stderrBuffer, 16384) }
    stderrThread.isDaemon = true
    stderrThread.start()

    val input = BufferedInputStream(process.inputStream)
    val output = BufferedOutputStream(process.outputStream)
    val queue = ArrayBlockingQueue<String>(16)
    val reader = Thread {
      try {
        readMessages(input, queue)
      } catch (_: java.io.IOException) {
        // Stream closed during shutdown.
      }
    }
    reader.isDaemon = true
    reader.start()

    val rootUri = rootPath.toAbsolutePath().normalize().toUri().toString()
    val initialize = """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":${process.pid()},"rootUri":"${escapeJson(rootUri)}","capabilities":{"textDocument":{"implementation":{"dynamicRegistration":false}}},"workspaceFolders":[{"uri":"${escapeJson(rootUri)}","name":"workspace"}]}}
    """.trimIndent()
    sendMessage(output, initialize)

    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
    var initializeResponse: String? = null
    while (System.nanoTime() < deadline) {
      val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
      val message = queue.poll(remaining.coerceAtMost(1000), TimeUnit.MILLISECONDS)
      if (message == null) continue
      if (message.contains("\"id\":1")) {
        if (message.contains("\"result\"")) {
          initializeResponse = message
          break
        }
        if (message.contains("\"error\"")) {
          fail("Initialize failed: $message")
        }
      }
      // Ignore other notifications (logMessage, telemetry, status) until init completes.
    }
    if (initializeResponse == null) {
      val stderr = stderrBuffer.toString().trim()
      val details = if (stderr.isNotBlank()) "\nJDT LS stderr:\n$stderr" else ""
      fail("No initialize response within ${timeoutMs}ms.$details")
    }

    val initialized = """
      {"jsonrpc":"2.0","method":"initialized","params":{}}
    """.trimIndent()
    sendMessage(output, initialized)

    var requestId = 3
    if (importProjects) {
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.project.import","arguments":[]}}
      """.trimIndent()
      sendMessage(output, exec)
      waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      Thread.sleep(2000)
    }

    if (refreshDiagnostics) {
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.project.refreshDiagnostics","arguments":["${escapeJson(rootUri)}"]}}
      """.trimIndent()
      sendMessage(output, exec)
      waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      Thread.sleep(2000)
    }

    if (expectProjects.isNotEmpty()) {
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.project.getAll","arguments":[]}}
      """.trimIndent()
      sendMessage(output, exec)
      val projectResponse = waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      val normalized = projectResponse.lowercase()
      expectProjects.forEach { project ->
        if (!normalized.contains(project.lowercase())) {
          fail("Expected project '$project' not found in response: $projectResponse")
        }
      }
    }

    if (symbolQueries.isNotEmpty()) {
      symbolQueries.forEach { query ->
        val exec = """
          {"jsonrpc":"2.0","id":${requestId},"method":"workspace/symbol","params":{"query":"${escapeJson(query)}"}}
        """.trimIndent()
        sendMessage(output, exec)
        val response = waitForResponse(queue, requestId, timeoutMs)
        requestId += 1
        if (!response.lowercase().contains(query.lowercase())) {
          fail("Expected symbol '$query' not found in response: $response")
        }
      }
    }

    val implFileValue = implFile
    val implSymbolValue = implSymbol
    if (implFileValue != null && implSymbolValue != null && implExpected.isNotEmpty()) {
      val implPath = Paths.get(implFileValue)
      if (!Files.isRegularFile(implPath)) {
        fail("Implementation file not found: $implPath")
      }
      val implText = Files.readString(implPath)
      val position = findPosition(implText, implSymbolValue)
        ?: fail("Symbol '$implSymbol' not found in ${implPath}")
      val docUri = implPath.toAbsolutePath().normalize().toUri().toString()
      val didOpen = """
        {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"${escapeJson(docUri)}","languageId":"java","version":1,"text":${jsonString(implText)}}}}
      """.trimIndent()
      sendMessage(output, didOpen)

      val expectedFiles = implExpected.mapNotNull { expected ->
        findFileByName(rootPath, expected)
      }
      expectedFiles.forEach { expectedPath ->
        val expectedText = Files.readString(expectedPath)
        val expectedUri = expectedPath.toAbsolutePath().normalize().toUri().toString()
        val openExpected = """
          {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"${escapeJson(expectedUri)}","languageId":"java","version":1,"text":${jsonString(expectedText)}}}}
        """.trimIndent()
        sendMessage(output, openExpected)
      }

      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
      var lastResponse: String? = null
      while (System.nanoTime() < deadline) {
        val request = """
          {"jsonrpc":"2.0","id":${requestId},"method":"textDocument/implementation","params":{"textDocument":{"uri":"${escapeJson(docUri)}"},"position":{"line":${position.first},"character":${position.second}}}}
        """.trimIndent()
        sendMessage(output, request)
        val response = waitForResponse(queue, requestId, timeoutMs)
        lastResponse = response
        val normalized = response.lowercase()
        val allMatched = implExpected.all { expected -> normalized.contains(expected.lowercase()) }
        if (allMatched) break
        requestId += 1
        Thread.sleep(1000)
      }
      val finalResponse = lastResponse
      if (finalResponse == null) {
        fail("No implementation response received")
      }
      val normalized = finalResponse.lowercase()
      implExpected.forEach { expected ->
        if (!normalized.contains(expected.lowercase())) {
          fail("Expected implementation '$expected' not found in response: $finalResponse")
        }
      }
    }

    val shutdown = """
      {"jsonrpc":"2.0","id":2,"method":"shutdown","params":{}}
    """.trimIndent()
    sendMessage(output, shutdown)
    queue.poll(5000, TimeUnit.MILLISECONDS)

    val exit = """
      {"jsonrpc":"2.0","method":"exit","params":{}}
    """.trimIndent()
    sendMessage(output, exit)

    process.destroy()
    return 0
  }
}

private fun sendMessage(output: BufferedOutputStream, payload: String) {
  val bytes = payload.toByteArray(StandardCharsets.UTF_8)
  val header = "Content-Length: ${bytes.size}\r\n\r\n"
  output.write(header.toByteArray(StandardCharsets.UTF_8))
  output.write(bytes)
  output.flush()
}

private fun drainStream(stream: java.io.InputStream, buffer: StringBuilder, limit: Int) {
  stream.bufferedReader().useLines { lines ->
    lines.forEach { line ->
      if (buffer.length + line.length + 1 > limit) {
        val overflow = buffer.length + line.length + 1 - limit
        buffer.delete(0, overflow.coerceAtMost(buffer.length))
      }
      buffer.append(line).append('\n')
    }
  }
}

private fun readMessages(input: BufferedInputStream, queue: ArrayBlockingQueue<String>) {
  while (true) {
    val headers = mutableMapOf<String, String>()
    val line = readLine(input) ?: return
    if (line.isBlank()) continue
    parseHeader(line, headers)
    while (true) {
      val next = readLine(input) ?: return
      if (next.isBlank()) break
      parseHeader(next, headers)
    }
    val length = headers["content-length"]?.toIntOrNull() ?: continue
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
      val count = input.read(body, read, length - read)
      if (count < 0) return
      read += count
    }
    val message = String(body, StandardCharsets.UTF_8)
    queue.offer(message)
  }
}

private fun waitForResponse(queue: ArrayBlockingQueue<String>, id: Int, timeoutMs: Int): String {
  val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
  while (System.nanoTime() < deadline) {
    val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
    val message = queue.poll(remaining.coerceAtMost(1000), TimeUnit.MILLISECONDS)
    if (message == null) continue
    if (message.contains("\"id\":$id")) {
      if (message.contains("\"error\"")) {
        fail("Request $id failed: $message")
      }
      return message
    }
  }
  fail("No response for request $id within ${timeoutMs}ms")
}

private fun findPosition(text: String, symbol: String): Pair<Int, Int>? {
  val index = text.indexOf(symbol)
  if (index < 0) return null
  var line = 0
  var column = 0
  var i = 0
  while (i < index) {
    val ch = text[i]
    if (ch == '\n') {
      line += 1
      column = 0
    } else {
      column += 1
    }
    i += 1
  }
  return line to column
}

private fun jsonString(value: String): String {
  val escaped = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n")
    .replace("\t", "\\t")
  return "\"$escaped\""
}

private fun findFileByName(rootPath: Path, fileName: String): Path? {
  Files.walk(rootPath).use { stream ->
    return stream.filter { path ->
      Files.isRegularFile(path) && path.fileName.toString() == fileName
    }.findFirst().orElse(null)
  }
}

private fun readLine(input: BufferedInputStream): String? {
  val builder = StringBuilder()
  while (true) {
    val value = input.read()
    if (value == -1) return null
    val ch = value.toChar()
    if (ch == '\r') {
      val next = input.read()
      if (next == -1) return null
      if (next.toChar() == '\n') {
        break
      }
    } else {
      builder.append(ch)
    }
  }
  return builder.toString()
}

private fun parseHeader(line: String, headers: MutableMap<String, String>) {
  val index = line.indexOf(':')
  if (index <= 0) return
  val key = line.substring(0, index).trim().lowercase()
  val value = line.substring(index + 1).trim()
  headers[key] = value
}

private fun escapeJson(value: String): String {
  return value.replace("\\", "\\\\").replace("\"", "\\\"")
}

private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
