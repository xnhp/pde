package cn.varsa.pde.launch

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.optional
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private class AddTestException(message: String) : RuntimeException(message)
private const val helperTestClass =
  "org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests"

object AddTestHelperCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde add-test-helper ${maturityTag("usable")}")
    val testClass by parser.argument(
      ArgType.String,
      description = "Fully-qualified test class name"
    )
    val testMethods by parser.argument(
      ArgType.String,
      description = "Optional comma-separated test method names"
    ).optional()
    parser.parse(args)

    return try {
      addTestHelper(testClass, testMethods)
      0
    } catch (ex: AddTestException) {
      System.err.println(ex.message)
      1
    }
  }
}

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

private fun addTestHelper(testClass: String, testMethods: String?) {
  val cwd = currentWorkingDir()
  val configPath = findConfigPath(cwd)
    ?: fail("No launch config found (config.yaml/launch.yaml/pde.yaml).")

  val normalizedTestClass = requireNonBlank(testClass, "Test class must be non-empty")
  val normalizedMethods = testMethods?.trim()?.takeIf { it.isNotBlank() }

  val rootMap = loadConfigYaml(configPath)
  val tests = ensureTestsList(rootMap)

  val vmArgs = mutableListOf<String>()
  vmArgs.add("-Dorg.knime.gateway.testing.helper.test_class=$normalizedTestClass")
  if (normalizedMethods != null) {
    vmArgs.add("-Dorg.knime.gateway.testing.helper.test_method=$normalizedMethods")
  }

  val entry = linkedMapOf<String, Any?>(
    "testpluginname" to "org.knime.gateway.impl",
    "classname" to helperTestClass,
    "vmArgs" to vmArgs
  )
  tests.add(entry)

  writeConfigYaml(configPath, rootMap)
  println("Added test helper entry to ${configPath.fileName}")
}

private fun addTest(pluginName: String, className: String) {
  val cwd = currentWorkingDir()
  val configPath = findConfigPath(cwd)
    ?: fail("No launch config found (config.yaml/launch.yaml/pde.yaml).")

  val normalizedPluginName = requireNonBlank(pluginName, "Plugin name must be non-empty")
  val normalizedClassName = requireNonBlank(className, "Class name must be non-empty")

  val rootMap = loadConfigYaml(configPath)
  val tests = ensureTestsList(rootMap)

  val entry = linkedMapOf<String, Any?>(
    "testpluginname" to normalizedPluginName,
    "classname" to normalizedClassName
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
    "config.yaml",
    "config.yml",
    "launch.yaml",
    "launch.yml",
    "pde.yaml",
    "pde.yml",
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
