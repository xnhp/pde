package cn.varsa.pde.launch

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

private class IssueNewException(message: String) : RuntimeException(message)

object IssueNewCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde issue-new")
    val issueId by parser.argument(ArgType.String, description = "Issue ID")
    parser.parse(args)

    return try {
      createIssueConfig(issueId)
      0
    } catch (ex: IssueNewException) {
      System.err.println(ex.message)
      1
    }
  }
}

private fun createIssueConfig(issueId: String) {
  val normalizedIssueId = requireNonBlank(issueId, "Issue ID must be non-empty")
  val baseDir = issueBaseDir()
  Files.createDirectories(baseDir)

  val templatePath = baseDir.resolve("config.template.yaml")
  if (!Files.exists(templatePath)) {
    fail("config.template.yaml not found in ${baseDir}")
  }

  val jiraIssue = fetchJiraIssue(baseDir, normalizedIssueId)
  val branch = buildBranchName(normalizedIssueId, jiraIssue?.issueType, jiraIssue?.summary)
  val issueDirName = branch.replace('/', '_')
  val issueDir = baseDir.resolve(issueDirName)
  if (Files.exists(issueDir)) {
    fail("Issue directory already exists: ${issueDir}")
  }
  Files.createDirectories(issueDir)

  val rootMap = loadConfigYaml(templatePath)
  rootMap["issueId"] = normalizedIssueId
  rootMap["branch"] = branch

  val destination = issueDir.resolve("config.yaml")
  writeConfigYaml(destination, rootMap)
  println("Created issue config at ${destination}")
}

private fun issueBaseDir(): Path = Paths.get(System.getProperty("user.home"), "Desktop", "issues")

private data class JiraIssue(val summary: String?, val issueType: String?)

private fun fetchJiraIssue(baseDir: Path, issueId: String): JiraIssue? {
  val auth = loadJiraAuth(baseDir) ?: return null
  val client = HttpClient.newHttpClient()
  val request = HttpRequest.newBuilder()
    .uri(URI.create("${auth.url}/rest/api/3/issue/${issueId}?fields=summary,issuetype"))
    .header("Authorization", auth.authHeader)
    .header("Accept", "application/json")
    .GET()
    .build()
  return try {
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() / 100 != 2) {
      warn("Failed to fetch Jira issue ${issueId}: ${response.statusCode()} ${response.body()}")
      null
    } else {
      val body = response.body()
      JiraIssue(
        summary = extractJsonString(body, SUMMARY_REGEX),
        issueType = extractJsonString(body, ISSUETYPE_REGEX)
      )
    }
  } catch (ex: Exception) {
    warn("Failed to fetch Jira issue ${issueId}: ${ex.message}")
    null
  }
}

private data class JiraAuth(val url: String, val authHeader: String)

private fun loadJiraAuth(baseDir: Path): JiraAuth? {
  val envPath = baseDir.resolve(".env")
  val env = loadEnvFile(envPath)
  val url = env["JIRA_URL"]
  val email = env["JIRA_EMAIL"]
  val token = env["JIRA_API_TOKEN"]
  if (url.isNullOrBlank() || email.isNullOrBlank() || token.isNullOrBlank()) {
    warn("Jira credentials not found in ${envPath}")
    return null
  }
  val authHeader = jiraAuthorizationHeader(email, token)
  return JiraAuth(url.trim().trimEnd('/'), authHeader)
}

private fun loadEnvFile(path: Path): Map<String, String> {
  if (!Files.exists(path)) return emptyMap()
  val env = mutableMapOf<String, String>()
  Files.readAllLines(path).forEach { line ->
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
    val parts = trimmed.split('=', limit = 2)
    if (parts.size != 2) return@forEach
    val key = parts[0].trim()
    var value = parts[1].trim()
    if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
      value = value.substring(1, value.length - 1)
    }
    env[key] = value
  }
  return env
}

private fun jiraAuthorizationHeader(email: String, token: String): String {
  val raw = "${email}:${token}"
  val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
  return "Basic ${encoded}"
}

private val SUMMARY_REGEX = Regex("\"summary\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"]*)*)\"")
private val ISSUETYPE_REGEX = Regex(
  "\"issuetype\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"]*)*)\"",
  RegexOption.DOT_MATCHES_ALL
)

private fun extractJsonString(input: String, regex: Regex): String? {
  val match = regex.find(input) ?: return null
  return unescapeJsonString(match.groupValues[1])
}

private fun unescapeJsonString(value: String): String {
  val result = StringBuilder(value.length)
  var i = 0
  while (i < value.length) {
    val ch = value[i]
    if (ch == '\\' && i + 1 < value.length) {
      val next = value[i + 1]
      when (next) {
        '"' -> result.append('"')
        '\\' -> result.append('\\')
        '/' -> result.append('/')
        'b' -> result.append('\b')
        'f' -> result.append('\u000C')
        'n' -> result.append('\n')
        'r' -> result.append('\r')
        't' -> result.append('\t')
        'u' -> {
          if (i + 5 < value.length) {
            val hex = value.substring(i + 2, i + 6)
            result.append(hex.toIntOrNull(16)?.toChar() ?: '?')
            i += 4
          }
        }
        else -> result.append(next)
      }
      i += 2
      continue
    }
    result.append(ch)
    i += 1
  }
  return result.toString()
}

private fun buildBranchName(issueId: String, issueType: String?, summary: String?): String {
  val prefix = branchPrefixForIssueType(issueType)
  val summarySlug = summary?.let { slugify(it) }
  val base = if (summarySlug.isNullOrBlank()) issueId else "${issueId}-${summarySlug}"
  return "${prefix}/${base}"
}

private fun branchPrefixForIssueType(issueType: String?): String {
  val normalized = slugify(issueType ?: "")
  return when {
    normalized.isBlank() -> "issue"
    normalized == "enhancement" -> "enh"
    else -> normalized
  }
}

private fun slugify(value: String): String {
  return value
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
}

private fun loadConfigYaml(path: Path): MutableMap<String, Any?> {
  val yaml = Yaml()
  val loaded = Files.newInputStream(path).use { yaml.load<Any?>(it) }
  val root = loaded as? Map<*, *> ?: fail("config.template.yaml must contain a YAML mapping at the root")
  val result = LinkedHashMap<String, Any?>()
  root.forEach { (key, value) ->
    val stringKey = key?.toString()?.trim().orEmpty()
    if (stringKey.isNotBlank()) {
      result[stringKey] = value
    }
  }
  if (result.isEmpty()) {
    fail("config.template.yaml must contain a YAML mapping at the root")
  }
  return result
}

private fun writeConfigYaml(path: Path, rootMap: Map<String, Any?>) {
  val options = DumperOptions().apply {
    defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
    defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
    isPrettyFlow = true
    indent = 2
    indicatorIndent = 0
  }
  val yaml = Yaml(options)
  val content = yaml.dump(rootMap).trimEnd() + "\n"
  Files.createDirectories(path.parent)
  Files.writeString(path, content)
}

private fun requireNonBlank(value: String?, errorMessage: String): String {
  val trimmed = value?.trim().orEmpty()
  if (trimmed.isBlank()) {
    fail(errorMessage)
  }
  return trimmed
}

private fun warn(message: String) {
  System.err.println("Warning: ${message}")
}

private fun fail(message: String): Nothing = throw IssueNewException(message)
