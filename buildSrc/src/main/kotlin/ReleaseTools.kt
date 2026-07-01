package release

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.ByteArrayOutputStream
import java.time.LocalDate

data class ConventionalCommit(
  val hash: String,
  val type: String?,
  val scope: String?,
  val description: String,
  val breaking: Boolean,
  val rawSubject: String,
  val body: String
)

enum class VersionBump(val priority: Int) {
  NONE(0),
  PATCH(1),
  MINOR(2),
  MAJOR(3);

  companion object {
    fun max(a: VersionBump, b: VersionBump): VersionBump = if (a.priority >= b.priority) a else b
  }
}

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
  override fun compareTo(other: SemVer): Int {
    if (major != other.major) return major.compareTo(other.major)
    if (minor != other.minor) return minor.compareTo(other.minor)
    return patch.compareTo(other.patch)
  }

  fun increment(bump: VersionBump): SemVer = when (bump) {
    VersionBump.NONE -> this
    VersionBump.PATCH -> copy(patch = patch + 1)
    VersionBump.MINOR -> copy(minor = minor + 1, patch = 0)
    VersionBump.MAJOR -> copy(major = major + 1, minor = 0, patch = 0)
  }

  override fun toString(): String = "$major.$minor.$patch"

  companion object {
    private val REGEX = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")

    fun parse(value: String): SemVer? {
      val match = REGEX.find(value.trim()) ?: return null
      return SemVer(
        match.groupValues[1].toInt(),
        match.groupValues[2].toInt(),
        match.groupValues[3].toInt()
      )
    }
  }
}

data class ReleaseInfo(
  val previousVersion: SemVer,
  val recommendedBump: VersionBump,
  val finalVersion: SemVer,
  val commits: List<ConventionalCommit>,
  val nonConventionalSubjects: List<String>,
  val releaseNotes: String
)

private val COMMIT_REGEX = Regex("^(\\w+)(?:\\(([^)]+)\\))?(!?): (.+)$")
private val BREAKING_FOOTER_REGEX = Regex("^BREAKING CHANGE:?", RegexOption.MULTILINE)

private fun normalizeType(type: String): String = type.lowercase()

private fun severityFor(commit: ConventionalCommit): VersionBump {
  return when {
    commit.breaking -> VersionBump.MAJOR
    commit.type == "feat" -> VersionBump.MINOR
    else -> VersionBump.PATCH
  }
}

private fun Project.git(vararg args: String, ignoreErrors: Boolean = false): String {
  val process = ProcessBuilder(listOf("git", *args))
    .directory(rootDir)
    .redirectErrorStream(false)
    .start()

  val stdout = ByteArrayOutputStream()
  val stderr = ByteArrayOutputStream()
  process.inputStream.use { it.copyTo(stdout) }
  process.errorStream.use { it.copyTo(stderr) }
  val exitCode = process.waitFor()

  if (exitCode != 0 && !ignoreErrors) {
    val command = args.joinToString(" ")
    throw GradleException("git $command failed with exit $exitCode: ${stderr.toString().trim()}")
  }

  return stdout.toString().trim()
}

private fun parseCommits(rawLog: String): Pair<List<ConventionalCommit>, List<String>> {
  if (rawLog.isBlank()) return emptyList<ConventionalCommit>() to emptyList()
  val commits = mutableListOf<ConventionalCommit>()
  val unknown = mutableListOf<String>()
  val records = rawLog.split("\u001E").filter { it.isNotBlank() }
  for (record in records) {
    val parts = record.split("\u001F")
    if (parts.size < 3) continue
    val hash = parts[0]
    val subject = parts[1].trim()
    val body = parts[2]
    val match = COMMIT_REGEX.find(subject)
    if (match != null) {
      val type = normalizeType(match.groupValues[1])
      val scope = match.groupValues[2].ifBlank { null }
      val breaking = match.groupValues[3].isNotEmpty() || BREAKING_FOOTER_REGEX.containsMatchIn(body)
      val description = match.groupValues[4].trim()
      commits += ConventionalCommit(hash, type, scope, description, breaking, subject, body)
    } else {
      val breaking = BREAKING_FOOTER_REGEX.containsMatchIn(body)
      commits += ConventionalCommit(hash, null, null, subject, breaking, subject, body)
      unknown += subject
    }
  }
  return commits to unknown
}

private fun sectionNameFor(type: String?): String = when (type) {
  "feat" -> "Features"
  "fix" -> "Bug Fixes"
  "perf" -> "Performance"
  "refactor" -> "Refactoring"
  "docs" -> "Documentation"
  "test" -> "Tests"
  "build", "gradle" -> "Build System"
  "ci" -> "Continuous Integration"
  "chore" -> "Chores"
  "style" -> "Code Style"
  "revert" -> "Reverts"
  else -> "Other"
}

private fun formatCommitLine(commit: ConventionalCommit): String {
  val scopePrefix = commit.scope?.let { "**$it**: " } ?: ""
  val marker = if (commit.breaking) "⚠️  " else ""
  return "$marker$scopePrefix${commit.description} (${commit.hash.take(7)})"
}

private fun buildReleaseNotes(commits: List<ConventionalCommit>): String {
  if (commits.isEmpty()) {
    return "No user-facing changes in this release."
  }

  val builder = StringBuilder()
  val grouped = commits.groupBy { sectionNameFor(it.type) }
  val breaking = commits.filter { it.breaking }

  if (breaking.isNotEmpty()) {
    builder.appendLine("### Breaking Changes")
    breaking.forEach { builder.appendLine("- ${formatCommitLine(it)}") }
    builder.appendLine()
  }

  grouped.toSortedMap().forEach { (section, items) ->
    val filtered = if (section == "Other") items.filter { it.type == null } else items
    if (filtered.isEmpty()) return@forEach
    builder.appendLine("### $section")
    filtered.forEach { builder.appendLine("- ${formatCommitLine(it)}") }
    builder.appendLine()
  }

  return builder.toString().trimEnd()
}

fun Project.computeReleaseInfo(overrideVersion: String?): ReleaseInfo {
  val lastTag = git("tag", "--list", "cli/v*", "ij/v*", "--sort=-v:refname").lineSequence().firstOrNull()?.trim().orEmpty()
  val previousVersion = SemVer.parse(lastTag.substringAfterLast('/').removePrefix("v")) ?: SemVer(0, 0, 0)

  val logRange = if (lastTag.isNotBlank()) "$lastTag..HEAD" else "HEAD"
  val rawLog = git("log", logRange, "--pretty=format:%H\u001F%s\u001F%b\u001E", ignoreErrors = true)
  val (commits, nonConventional) = parseCommits(rawLog)

  val recommendedBump = commits.fold(if (commits.isEmpty()) VersionBump.NONE else VersionBump.PATCH) { acc, commit ->
    VersionBump.max(acc, severityFor(commit))
  }

  val recommendedVersion = previousVersion.increment(recommendedBump)
  val finalVersion = overrideVersion?.let {
    SemVer.parse(it)
      ?: throw IllegalArgumentException("releaseVersion '$it' is not a valid semantic version")
  } ?: recommendedVersion

  if (finalVersion < recommendedVersion) {
    throw IllegalStateException("Provided release version $finalVersion is lower than recommended $recommendedVersion")
  }

  val releaseNotes = buildReleaseNotes(commits)

  return ReleaseInfo(
    previousVersion = previousVersion,
    recommendedBump = recommendedBump,
    finalVersion = finalVersion,
    commits = commits,
    nonConventionalSubjects = nonConventional,
    releaseNotes = releaseNotes
  )
}

fun Project.writeReleaseArtifacts(info: ReleaseInfo) {
  val releaseDir = layout.buildDirectory.dir("release").get().asFile
  releaseDir.mkdirs()
  releaseDir.resolve("version.txt").writeText(info.finalVersion.toString())
  releaseDir.resolve("notes.md").writeText(buildString {
    appendLine("## v${info.finalVersion} - ${LocalDate.now()}")
    appendLine()
    append(info.releaseNotes.ifBlank { "No user-facing changes in this release." })
    appendLine()
    if (info.nonConventionalSubjects.isNotEmpty()) {
      appendLine()
      appendLine("_Unclassified commits_:")
      info.nonConventionalSubjects.forEach { appendLine("- $it") }
    }
  }.trimEnd() + "\n")
}

fun Project.updateChangelogWith(notesFileContent: String) {
  val changelogFile = layout.projectDirectory.file("CHANGELOG.md").asFile
  val entry = notesFileContent.trimEnd() + "\n\n"
  if (!changelogFile.exists()) {
    changelogFile.parentFile?.mkdirs()
    changelogFile.writeText("# Changelog\n\n$entry")
    return
  }

  val existing = changelogFile.readText()
  val header = existing.lineSequence().firstOrNull() ?: "# Changelog"
  val body = existing.removePrefix(header).trimStart('\n')
  val newContent = buildString {
    appendLine(header.trimEnd())
    appendLine()
    append(entry)
    append(body)
    if (!endsWith("\n")) appendLine()
  }
  changelogFile.writeText(newContent)
}

fun Logger.reportReleaseInfo(info: ReleaseInfo) {
  lifecycle("Previous version: ${info.previousVersion}")
  lifecycle("Recommended bump: ${info.recommendedBump}")
  lifecycle("Final version: ${info.finalVersion}")
  if (info.nonConventionalSubjects.isNotEmpty()) {
    warn("Found ${info.nonConventionalSubjects.size} non-conventional commits. They were included in the changelog under 'Other'.")
  }
}
