package cn.varsa.pde.launch

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

data class JdtlsSmokeConfig(
  val launcherJar: Path,
  val configDir: Path,
  val rootDir: Path,
  val dataDir: Path? = null,
  val timeoutMs: Int = 60000,
  val expectProjects: List<String> = emptyList(),
  val implFile: Path? = null,
  val implSymbol: String? = null,
  val implExpected: List<String> = emptyList(),
  val definitionFile: Path? = null,
  val definitionSymbol: String? = null,
  val definitionExpected: List<String> = emptyList(),
  val referencesFile: Path? = null,
  val referencesSymbol: String? = null,
  val referencesExpected: List<String> = emptyList(),
  val referencesIncludeDeclaration: Boolean = false,
  val hierarchyFile: Path? = null,
  val hierarchySymbol: String? = null,
  val hierarchyExpected: List<String> = emptyList(),
  val hierarchyDirection: Int = 0,
  val hierarchyResolve: Int = 1,
  val completionFile: Path? = null,
  val completionSymbol: String? = null,
  val completionExpected: List<String> = emptyList(),
  val diagnosticsFile: Path? = null,
  val diagnosticsMin: Int = 1,
  val sourceAttachmentClassFile: String? = null,
  val sourceAttachmentExpected: String? = null,
  val classpathFile: String? = null,
  val classpathExpected: List<String> = emptyList(),
  val symbolResolveQuery: String? = null,
  val symbolResolveExpected: String? = null,
  val symbolQueries: List<String> = emptyList(),
  val importProjects: Boolean = false,
  val refreshDiagnostics: Boolean = false,
  val vmArgs: List<String> = emptyList()
)

fun runJdtlsSmoke(config: JdtlsSmokeConfig): Int {
  val overallStart = System.nanoTime()
  var mark = overallStart
  val launcherPath = config.launcherJar
  val configPath = config.configDir
  val rootPath = config.rootDir
  val dataPath = config.dataDir ?: rootPath.resolve(".jdtls-data")
  val shutdownMessage = """
    {"jsonrpc":"2.0","id":2,"method":"shutdown","params":{}}
  """.trimIndent()
  val exitMessage = """
    {"jsonrpc":"2.0","method":"exit","params":{}}
  """.trimIndent()
  var process: Process? = null
  var input: BufferedInputStream? = null
  var output: BufferedOutputStream? = null

  try {
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
    val command = listOf("java") + defaultVmArgs + config.vmArgs + listOf(
      "-jar",
      launcherPath.toAbsolutePath().normalize().toString(),
      "-configuration",
      configPath.toAbsolutePath().normalize().toString(),
      "-data",
      dataPath.toAbsolutePath().normalize().toString()
    )

    process = ProcessBuilder(command)
      .directory(rootPath.toFile())
      .redirectErrorStream(false)
      .start()
    mark = profileStep("start JDT LS process", mark)

    val stderrBuffer = StringBuilder()
    val stderrThread = Thread { drainStream(process.errorStream, stderrBuffer, 16384) }
    stderrThread.isDaemon = true
    stderrThread.start()

    val inputStream = BufferedInputStream(process.inputStream)
    val outputStream = BufferedOutputStream(process.outputStream)
    input = inputStream
    output = outputStream
    val input = inputStream
    val output = outputStream
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
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":${process.pid()},"rootUri":"${escapeJson(rootUri)}","capabilities":{"textDocument":{"implementation":{"dynamicRegistration":false}}},"initializationOptions":{"extendedClientCapabilities":{"classFileContentsSupport":true}},"workspaceFolders":[{"uri":"${escapeJson(rootUri)}","name":"workspace"}]}}
    """.trimIndent()
    sendMessage(output, initialize)

    val timeoutMs = config.timeoutMs
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
    mark = profileStep("initialize response", mark)

    val initialized = """
      {"jsonrpc":"2.0","method":"initialized","params":{}}
    """.trimIndent()
    sendMessage(output, initialized)

    var requestId = 3
    if (config.importProjects) {
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.project.import","arguments":[]}}
      """.trimIndent()
      sendMessage(output, exec)
      waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      Thread.sleep(2000)
      mark = profileStep("java.project.import", mark)
    }

    if (config.refreshDiagnostics) {
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.project.refreshDiagnostics","arguments":["${escapeJson(rootUri)}"]}}
      """.trimIndent()
      sendMessage(output, exec)
      waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      Thread.sleep(2000)
      mark = profileStep("java.project.refreshDiagnostics", mark)
    }

    if (config.expectProjects.isNotEmpty()) {
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.project.getAll","arguments":[]}}
      """.trimIndent()
      sendMessage(output, exec)
      val projectResponse = waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      val normalized = projectResponse.lowercase()
      config.expectProjects.forEach { project ->
        if (!normalized.contains(project.lowercase())) {
          fail("Expected project '$project' not found in response: $projectResponse")
        }
      }
      mark = profileStep("java.project.getAll", mark)
    }

    if (config.symbolQueries.isNotEmpty()) {
      config.symbolQueries.forEach { query ->
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

    val classpathFileValue = config.classpathFile
    if (classpathFileValue != null && config.classpathExpected.isNotEmpty()) {
      val fileUri = if (classpathFileValue.startsWith("file:") || classpathFileValue.startsWith("jdt:")) {
        classpathFileValue
      } else {
        Paths.get(classpathFileValue).toAbsolutePath().normalize().toUri().toString()
      }
      val optionsJson = "{\"scope\":\"runtime\"}"
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.project.getClasspaths","arguments":["${escapeJson(fileUri)}","${escapeJson(optionsJson)}"]}}
      """.trimIndent()
      sendMessage(output, exec)
      val response = waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      val normalized = response.lowercase()
      config.classpathExpected.forEach { expected ->
        if (!normalized.contains(expected.lowercase())) {
          fail("Expected classpath entry '$expected' not found in response: $response")
        }
      }
    }

    val implFileValue = config.implFile
    val implSymbolValue = config.implSymbol
    if (implFileValue != null && implSymbolValue != null && config.implExpected.isNotEmpty()) {
      val implPath = implFileValue
      if (!Files.isRegularFile(implPath)) {
        fail("Implementation file not found: $implPath")
      }
      val implText = Files.readString(implPath)
      val position = findWordPositionLast(implText, implSymbolValue)
        ?: fail("Symbol '$implSymbolValue' not found in ${implPath}")
      val docUri = implPath.toAbsolutePath().normalize().toUri().toString()
      val didOpen = """
        {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"${escapeJson(docUri)}","languageId":"java","version":1,"text":${jsonString(implText)}}}}
      """.trimIndent()
      sendMessage(output, didOpen)

      val expectedFiles = config.implExpected.mapNotNull { expected ->
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
        val allMatched = config.implExpected.all { expected -> normalized.contains(expected.lowercase()) }
        if (allMatched) break
        requestId += 1
        Thread.sleep(1000)
      }
      val finalResponse = lastResponse
      if (finalResponse == null) {
        fail("No implementation response received")
      }
      val normalized = finalResponse.lowercase()
      config.implExpected.forEach { expected ->
        if (!normalized.contains(expected.lowercase())) {
          fail("Expected implementation '$expected' not found in response: $finalResponse")
        }
      }
    }

    val defFileValue = config.definitionFile
    val defSymbolValue = config.definitionSymbol
    var definitionLocation: String? = null
    if (defFileValue != null && defSymbolValue != null && config.definitionExpected.isNotEmpty()) {
      val defPath = defFileValue
      if (!Files.isRegularFile(defPath)) {
        fail("Definition file not found: $defPath")
      }
      val defText = Files.readString(defPath)
      val position = findWordPositionLast(defText, defSymbolValue)
        ?: fail("Symbol '$defSymbolValue' not found in ${defPath}")
      val docUri = defPath.toAbsolutePath().normalize().toUri().toString()
      val didOpen = """
        {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"${escapeJson(docUri)}","languageId":"java","version":1,"text":${jsonString(defText)}}}}
      """.trimIndent()
      sendMessage(output, didOpen)

      val expectedFiles = config.definitionExpected.mapNotNull { expected ->
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
          {"jsonrpc":"2.0","id":${requestId},"method":"textDocument/definition","params":{"textDocument":{"uri":"${escapeJson(docUri)}"},"position":{"line":${position.first},"character":${position.second}}}}
        """.trimIndent()
        sendMessage(output, request)
        val response = waitForResponse(queue, requestId, timeoutMs)
        lastResponse = response
        val normalized = response.lowercase()
        val allMatched = config.definitionExpected.all { expected -> normalized.contains(expected.lowercase()) }
        if (allMatched) break
        requestId += 1
        Thread.sleep(1000)
      }
      val finalResponse = lastResponse
      if (finalResponse == null) {
        fail("No definition response received")
      }
      val normalized = finalResponse.lowercase()
      definitionLocation = extractLocationUri(finalResponse)
      config.definitionExpected.forEach { expected ->
        if (!normalized.contains(expected.lowercase())) {
          fail("Expected definition '$expected' not found in response: $finalResponse")
        }
      }
    }

    val refFileValue = config.referencesFile
    val refSymbolValue = config.referencesSymbol
    if (refFileValue != null && refSymbolValue != null && config.referencesExpected.isNotEmpty()) {
      val refPath = refFileValue
      if (!Files.isRegularFile(refPath)) {
        fail("References file not found: $refPath")
      }
      val refText = Files.readString(refPath)
      val position = findWordPositionLast(refText, refSymbolValue)
        ?: fail("Symbol '$refSymbolValue' not found in ${refPath}")
      val docUri = refPath.toAbsolutePath().normalize().toUri().toString()
      val didOpen = """
        {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"${escapeJson(docUri)}","languageId":"java","version":1,"text":${jsonString(refText)}}}}
      """.trimIndent()
      sendMessage(output, didOpen)

      val expectedFiles = config.referencesExpected.mapNotNull { expected ->
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

      val includeDeclaration = if (config.referencesIncludeDeclaration) "true" else "false"
      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
      var lastResponse: String? = null
      while (System.nanoTime() < deadline) {
        val request = """
          {"jsonrpc":"2.0","id":${requestId},"method":"textDocument/references","params":{"textDocument":{"uri":"${escapeJson(docUri)}"},"position":{"line":${position.first},"character":${position.second}},"context":{"includeDeclaration":${includeDeclaration}}}}
        """.trimIndent()
        sendMessage(output, request)
        val response = waitForResponse(queue, requestId, timeoutMs)
        lastResponse = response
        val normalized = response.lowercase()
        val allMatched = config.referencesExpected.all { expected -> normalized.contains(expected.lowercase()) }
        if (allMatched) break
        requestId += 1
        Thread.sleep(1000)
      }
      val finalResponse = lastResponse
      if (finalResponse == null) {
        fail("No references response received")
      }
      val normalized = finalResponse.lowercase()
      config.referencesExpected.forEach { expected ->
        if (!normalized.contains(expected.lowercase())) {
          fail("Expected reference '$expected' not found in response: $finalResponse")
        }
      }
    }

    val hierarchyFileValue = config.hierarchyFile
    val hierarchySymbolValue = config.hierarchySymbol
    if (hierarchyFileValue != null && hierarchySymbolValue != null && config.hierarchyExpected.isNotEmpty()) {
      val hierarchyPath = hierarchyFileValue
      if (!Files.isRegularFile(hierarchyPath)) {
        fail("Hierarchy file not found: $hierarchyPath")
      }
      val hierarchyText = Files.readString(hierarchyPath)
      val position = findWordPositionLast(hierarchyText, hierarchySymbolValue)
        ?: fail("Symbol '$hierarchySymbolValue' not found in ${hierarchyPath}")
      val docUri = hierarchyPath.toAbsolutePath().normalize().toUri().toString()
      val didOpen = """
        {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"${escapeJson(docUri)}","languageId":"java","version":1,"text":${jsonString(hierarchyText)}}}}
      """.trimIndent()
      sendMessage(output, didOpen)

      val paramsJson = "{\"textDocument\":{\"uri\":\"${escapeJson(docUri)}\"},\"position\":{\"line\":${position.first},\"character\":${position.second}}}"
      val directionJson = config.hierarchyDirection.toString()
      val resolveJson = config.hierarchyResolve.toString()
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.navigate.openTypeHierarchy","arguments":["${escapeJson(paramsJson)}","${escapeJson(directionJson)}","${escapeJson(resolveJson)}"]}}
      """.trimIndent()
      sendMessage(output, exec)
      val response = waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      val normalized = response.lowercase()
      config.hierarchyExpected.forEach { expected ->
        if (!normalized.contains(expected.lowercase())) {
          fail("Expected hierarchy entry '$expected' not found in response: $response")
        }
      }
    }

    val completionFileValue = config.completionFile
    val completionSymbolValue = config.completionSymbol
    if (completionFileValue != null && completionSymbolValue != null && config.completionExpected.isNotEmpty()) {
      val completionPath = completionFileValue
      if (!Files.isRegularFile(completionPath)) {
        fail("Completion file not found: $completionPath")
      }
      val completionText = Files.readString(completionPath)
      val position = findPositionLast(completionText, completionSymbolValue)
        ?: fail("Symbol '$completionSymbolValue' not found in ${completionPath}")
      val docUri = completionPath.toAbsolutePath().normalize().toUri().toString()
      val didOpen = """
        {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"${escapeJson(docUri)}","languageId":"java","version":1,"text":${jsonString(completionText)}}}}
      """.trimIndent()
      sendMessage(output, didOpen)

      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
      var lastResponse: String? = null
      while (System.nanoTime() < deadline) {
        val request = """
          {"jsonrpc":"2.0","id":${requestId},"method":"textDocument/completion","params":{"textDocument":{"uri":"${escapeJson(docUri)}"},"position":{"line":${position.first},"character":${position.second}}}}
        """.trimIndent()
        sendMessage(output, request)
        val response = waitForResponse(queue, requestId, timeoutMs)
        lastResponse = response
        val normalized = response.lowercase()
        val allMatched = config.completionExpected.all { expected -> normalized.contains(expected.lowercase()) }
        if (allMatched) break
        requestId += 1
        Thread.sleep(1000)
      }
      val finalResponse = lastResponse
      if (finalResponse == null) {
        fail("No completion response received")
      }
      val normalized = finalResponse.lowercase()
      config.completionExpected.forEach { expected ->
        if (!normalized.contains(expected.lowercase())) {
          fail("Expected completion '$expected' not found in response: $finalResponse")
        }
      }
    }

    val diagnosticsFileValue = config.diagnosticsFile
    if (diagnosticsFileValue != null) {
      val diagnosticsPath = diagnosticsFileValue
      if (!Files.isRegularFile(diagnosticsPath)) {
        fail("Diagnostics file not found: $diagnosticsPath")
      }
      val diagnosticsText = Files.readString(diagnosticsPath)
      val docUri = diagnosticsPath.toAbsolutePath().normalize().toUri().toString()
      val didOpen = """
        {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"${escapeJson(docUri)}","languageId":"java","version":1,"text":${jsonString(diagnosticsText)}}}}
      """.trimIndent()
      sendMessage(output, didOpen)

      val result = waitForDiagnostics(queue, docUri, config.diagnosticsMin, timeoutMs)
      if (!result) {
        fail("Expected at least ${config.diagnosticsMin} diagnostics for $docUri")
      }
    }

    val resolveQuery = config.symbolResolveQuery
    val resolveExpected = config.symbolResolveExpected
    var resolvedLocation: String? = null
    if (resolveQuery != null) {
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/symbol","params":{"query":"${escapeJson(resolveQuery)}"}}
      """.trimIndent()
      sendMessage(output, exec)
      val response = waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      val match = extractFirstSymbol(response)
        ?: fail("No symbol returned for query '$resolveQuery': $response")
      val resolveExec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.project.resolveWorkspaceSymbol","arguments":[${match}]}}
      """.trimIndent()
      sendMessage(output, resolveExec)
      val resolved = waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      resolvedLocation = extractLocationUri(resolved)
      if (resolveExpected != null && !resolved.lowercase().contains(resolveExpected.lowercase())) {
        fail("Expected resolved symbol '$resolveExpected' not found in response: $resolved")
      }
    }

    val classFileUri = config.sourceAttachmentClassFile ?: definitionLocation ?: resolvedLocation
    val expectedSource = config.sourceAttachmentExpected
    if (classFileUri != null && expectedSource != null) {
      val requestJson = "{\"classFileUri\":\"${escapeJson(classFileUri)}\"}"
      val exec = """
        {"jsonrpc":"2.0","id":${requestId},"method":"workspace/executeCommand","params":{"command":"java.project.resolveSourceAttachment","arguments":["${escapeJson(requestJson)}"]}}
      """.trimIndent()
      sendMessage(output, exec)
      val response = waitForResponse(queue, requestId, timeoutMs)
      requestId += 1
      if (!response.lowercase().contains(expectedSource.lowercase())) {
        fail("Expected source attachment '$expectedSource' not found in response: $response")
      }
    }

    sendMessage(output, shutdownMessage)
    queue.poll(5000, TimeUnit.MILLISECONDS)

    sendMessage(output, exitMessage)
    output.flush()
    profileTotal("total", overallStart)
    return 0
  } finally {
    val proc = process ?: return 0
    if (proc.isAlive) {
      try {
        output?.let { stream ->
          sendMessage(stream, shutdownMessage)
          sendMessage(stream, exitMessage)
          stream.flush()
        }
      } catch (_: Exception) {
        // Ignore shutdown failures on cleanup.
      }
    }
    try {
      output?.close()
    } catch (_: Exception) {
      // Ignore close failures on cleanup.
    }
    try {
      input?.close()
    } catch (_: Exception) {
      // Ignore close failures on cleanup.
    }
    if (proc.isAlive) {
      if (!proc.waitFor(5, TimeUnit.SECONDS)) {
        proc.destroy()
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
          proc.destroyForcibly()
        }
      }
    }
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

private fun waitForDiagnostics(queue: ArrayBlockingQueue<String>, uri: String, minCount: Int, timeoutMs: Int): Boolean {
  val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
  while (System.nanoTime() < deadline) {
    val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
    val message = queue.poll(remaining.coerceAtMost(1000), TimeUnit.MILLISECONDS)
    if (message == null) continue
    if (!message.contains("\"method\":\"textDocument/publishDiagnostics\"")) continue
    if (!message.contains(uri)) continue
    if (minCount <= 0) return true
    if (message.contains("\"diagnostics\":[]")) continue
    return true
  }
  return false
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

private fun findPositionLast(text: String, symbol: String): Pair<Int, Int>? {
  val index = text.lastIndexOf(symbol)
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

private fun findWordPositionLast(text: String, symbol: String): Pair<Int, Int>? {
  val regex = Regex("\\b" + Regex.escape(symbol) + "\\b")
  val match = regex.findAll(text).lastOrNull() ?: return null
  val index = match.range.first
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

private fun extractFirstSymbol(response: String): String? {
  val resultIndex = response.indexOf("\"result\"")
  if (resultIndex < 0) return null
  val startArray = response.indexOf('[', resultIndex)
  if (startArray < 0) return null
  val endArray = response.indexOf(']', startArray)
  if (endArray < 0) return null
  val arrayBody = response.substring(startArray + 1, endArray).trim()
  if (arrayBody.isEmpty()) return null
  val firstEnd = findJsonObjectEnd(arrayBody)
  if (firstEnd <= 0) return null
  return arrayBody.substring(0, firstEnd)
}

private fun extractLocationUri(response: String): String? {
  val uriIndex = response.indexOf("\"uri\"")
  if (uriIndex < 0) return null
  val startQuote = response.indexOf('"', uriIndex + 5)
  if (startQuote < 0) return null
  val endQuote = response.indexOf('"', startQuote + 1)
  if (endQuote < 0) return null
  return unescapeJson(response.substring(startQuote + 1, endQuote))
}

private fun unescapeJson(value: String): String {
  val builder = StringBuilder()
  var i = 0
  while (i < value.length) {
    val ch = value[i]
    if (ch != '\\' || i + 1 >= value.length) {
      builder.append(ch)
      i += 1
      continue
    }
    val next = value[i + 1]
    when (next) {
      '\\' -> builder.append('\\')
      '"' -> builder.append('"')
      'n' -> builder.append('\n')
      'r' -> builder.append('\r')
      't' -> builder.append('\t')
      'u' -> {
        if (i + 5 < value.length) {
          val hex = value.substring(i + 2, i + 6)
          val code = hex.toIntOrNull(16)
          if (code != null) {
            builder.append(code.toChar())
            i += 6
            continue
          }
        }
        builder.append(next)
      }
      else -> builder.append(next)
    }
    i += 2
  }
  return builder.toString()
}

private fun findJsonObjectEnd(value: String): Int {
  var depth = 0
  var inString = false
  var escape = false
  for (i in value.indices) {
    val ch = value[i]
    if (escape) {
      escape = false
      continue
    }
    if (ch == '\\') {
      if (inString) escape = true
      continue
    }
    if (ch == '"') {
      inString = !inString
      continue
    }
    if (inString) continue
    if (ch == '{') depth += 1
    if (ch == '}') {
      depth -= 1
      if (depth == 0) return i + 1
    }
  }
  return -1
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

private val PROFILE_ENABLED: Boolean = System.getenv("JDTLS_PROFILE")?.trim()?.let {
  it == "1" || it.equals("true", ignoreCase = true)
} ?: false

private fun profileStep(label: String, startNs: Long): Long {
  if (PROFILE_ENABLED) {
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
    println("[jdtls-smoke] ${label} in ${elapsedMs}ms")
  }
  return System.nanoTime()
}

private fun profileTotal(label: String, startNs: Long) {
  if (!PROFILE_ENABLED) return
  val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
  println("[jdtls-smoke] ${label} in ${elapsedMs}ms")
}
