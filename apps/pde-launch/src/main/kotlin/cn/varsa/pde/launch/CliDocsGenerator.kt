package cn.varsa.pde.launch

import cn.varsa.cli.core.CliCommands
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

fun main(args: Array<String>) {
  val outputPath = if (args.isNotEmpty()) Path.of(args[0]) else Path.of("docs/cli-reference.md")
  val repoRoot = if (args.size > 1) Path.of(args[1]) else Path.of(".")

  val root = createPdeCommandLine()
  val commands = CliCommands.discover(root)
  val commitHash = currentCommitHash(repoRoot)
  val generatedDate = LocalDate.now(ZoneOffset.UTC)

  val content = buildString {
    appendLine("# CLI Reference")
    appendLine()
    appendLine("Generated. Do not edit manually.")
    appendLine("Source commit: `$commitHash`")
    appendLine("Generated date: `$generatedDate`")
    appendLine()

    commands.forEach { command ->
      val commandPath = CliCommands.commandPath(command)
      appendLine("## `$commandPath`")
      appendLine()
      appendLine("```text")
      append(renderHelp(command).trimEnd())
      appendLine()
      appendLine("```")
      appendLine()
    }
  }

  Files.createDirectories(outputPath.parent)
  Files.writeString(outputPath, content, StandardCharsets.UTF_8)
}

private fun renderHelp(command: CommandLine): String {
  val output = ByteArrayOutputStream()
  PrintWriter(output, true, StandardCharsets.UTF_8).use { writer ->
    command.usage(writer, CommandLine.Help.Ansi.OFF)
  }
  return output.toString(StandardCharsets.UTF_8)
}

private fun currentCommitHash(repoRoot: Path): String {
  return try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
    if (process.waitFor() == 0 && output.isNotBlank()) output else "unknown"
  } catch (_: Exception) {
    "unknown"
  }
}
