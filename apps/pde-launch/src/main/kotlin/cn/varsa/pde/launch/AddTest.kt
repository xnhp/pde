package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.maturityTag
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private class AddTestException(message: String) : RuntimeException(message)

object AddTestCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde add-test ${maturityTag("usable")}")
    val pluginName by parser.argument(
      ArgType.String,
      description = "Test plugin name (bundle ID)"
    )
    val className by parser.argument(
      ArgType.String,
      description = "Fully-qualified test class name"
    )
    parser.parse(args)

    return try {
      addTest(pluginName, className)
      0
    } catch (ex: AddTestException) {
      System.err.println(ex.message)
      1
    }
  }
}

private fun addTest(pluginName: String, className: String) {
  val cwd = currentWorkingDir()
  val configPath = findConfigPath(cwd)
    ?: fail("No launch config found (pde.yaml/launch.yaml/pde-launch.yaml).")

  val normalizedPluginName = requireNonBlank(pluginName, "Plugin name must be non-empty")
  val normalizedClassName = requireNonBlank(className, "Class name must be non-empty")

  val rootMap = loadConfigYaml(configPath)
  val tests = ensureTestsList(rootMap)

  val entry = linkedMapOf<String, Any?>(
    "testPluginName" to normalizedPluginName,
    "className" to normalizedClassName
  )
  tests.add(entry)

  writeConfigYaml(configPath, rootMap)
  println("Added test entry to ${configPath.fileName}")
}

@Suppress("UNCHECKED_CAST")
private fun loadConfigYaml(path: Path): MutableMap<String, Any?> {
  val contents = Files.readString(path)
  val yaml = Yaml()
  val loaded = yaml.load<Any?>(contents) ?: fail("${path.fileName} is empty")
  val rootMap = loaded as? Map<*, *>
    ?: fail("${path.fileName} must be a mapping at the root")
  val result = LinkedHashMap<String, Any?>()
  rootMap.forEach { (key, value) ->
    val stringKey = key?.toString() ?: fail("${path.fileName} contains a non-string key")
    result[stringKey] = value
  }
  return result
}

@Suppress("UNCHECKED_CAST")
private fun ensureTestsList(rootMap: MutableMap<String, Any?>): MutableList<Any?> {
  val existing = rootMap["tests"]
  return when (existing) {
    null -> mutableListOf<Any?>().also { rootMap["tests"] = it }
    is MutableList<*> -> existing as MutableList<Any?>
    is List<*> -> existing.toMutableList().also { rootMap["tests"] = it }
    else -> fail("Launch config 'tests' must be a list")
  }
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
  val output = yaml.dump(rootMap).trimEnd() + "\n"
  Files.writeString(path, output)
}

private fun requireNonBlank(value: String, errorMessage: String): String {
  val trimmed = value.trim()
  if (trimmed.isBlank()) {
    fail(errorMessage)
  }
  return trimmed
}

private fun findConfigPath(startDir: Path): Path? {
  val candidates = listOf(
    "pde.yaml",
    "launch.yaml",
    "launch.yml",
    "pde-launch.yaml",
    "pde-launch.yml"
  )
  var current = startDir.toAbsolutePath().normalize()
  while (true) {
    candidates.forEach { name ->
      val path = current.resolve(name)
      if (Files.exists(path) && Files.isRegularFile(path)) return path
    }
    val parent = current.parent ?: return null
    if (parent == current) return null
    current = parent
  }
}

private fun currentWorkingDir(): Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()

private fun fail(message: String): Nothing = throw AddTestException(message)
