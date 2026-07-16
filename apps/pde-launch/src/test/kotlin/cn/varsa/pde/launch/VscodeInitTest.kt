package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.config.LaunchConfig
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchEntry
import cn.varsa.pde.resolver.cli.config.TestEntry
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VscodeInitTest {
  @Test
  fun `writeVscodeTasksAndLaunch writes tasks and launch configs for launches and tests`() {
    val issueDir = Files.createTempDirectory("vscode-init-tasks")
    val configPath = issueDir.resolve("pde.yaml")
    Files.writeString(configPath, "bundles: []\n")

    val config = LaunchConfig(
      launches = listOf(LaunchEntry(name = "runApp")),
      tests = listOf(
        TestEntry(name = "unitTests", debug = true),
        TestEntry(name = null, debug = false)
      )
    )
    val context = LaunchConfigContext(file = configPath, baseDir = issueDir, config = config, workingDir = issueDir)

    val wrote = VscodeInit.writeVscodeTasksAndLaunch(context, issueDir, configPath)
    assertTrue(wrote)

    val tasksJson = Files.readString(issueDir.resolve(".vscode/tasks.json"))
    val absConfigPath = configPath.toAbsolutePath().normalize().toString()

    assertTrue(tasksJson.contains("\"label\": \"runApp\""))
    assertTrue(tasksJson.contains("\"args\": [\"run\", \"${absConfigPath}\", \"runApp\"]"))
    assertTrue(tasksJson.contains("\"label\": \"runApp (debug)\""))
    assertTrue(tasksJson.contains("\"args\": [\"run\", \"${absConfigPath}\", \"runApp\", \"--debug\"]"))
    assertTrue(tasksJson.contains("\"isBackground\": true"))
    assertTrue(tasksJson.contains("Waiting for debugger to attach on port 5005"))

    assertTrue(tasksJson.contains("\"label\": \"test: unitTests\""))
    assertTrue(tasksJson.contains("\"args\": [\"test\", \"${absConfigPath}\", \"unitTests\"]"))
    assertTrue(tasksJson.contains("\"label\": \"test: unitTests (debug)\""))

    // Nameless test entry (second one) falls back to its 1-based index.
    assertTrue(tasksJson.contains("\"label\": \"test: 2\""))
    assertTrue(tasksJson.contains("\"args\": [\"test\", \"${absConfigPath}\", \"2\"]"))
    // No debug variant for the non-debug test entry.
    assertFalse(tasksJson.contains("\"label\": \"test: 2 (debug)\""))

    val launchJson = Files.readString(issueDir.resolve(".vscode/launch.json"))
    assertTrue(launchJson.contains("\"request\": \"attach\""))
    assertTrue(launchJson.contains("\"port\": 5005"))
    assertTrue(launchJson.contains("\"name\": \"runApp (debug)\""))
    assertTrue(launchJson.contains("\"preLaunchTask\": \"runApp (debug)\""))
    assertTrue(launchJson.contains("\"name\": \"test: unitTests (debug)\""))
    assertTrue(launchJson.contains("\"preLaunchTask\": \"test: unitTests (debug)\""))
    // Only one debug config for tests (the debug:true entry).
    assertFalse(launchJson.contains("\"name\": \"test: 2 (debug)\""))
  }

  @Test
  fun `writeVscodeTasksAndLaunch does not clobber existing tasks or launch json`() {
    val issueDir = Files.createTempDirectory("vscode-init-tasks-existing")
    val configPath = issueDir.resolve("pde.yaml")
    Files.writeString(configPath, "bundles: []\n")
    val vscodeDir = issueDir.resolve(".vscode")
    Files.createDirectories(vscodeDir)
    Files.writeString(vscodeDir.resolve("tasks.json"), "{\"version\": \"2.0.0\", \"tasks\": []}")
    Files.writeString(vscodeDir.resolve("launch.json"), "{\"version\": \"0.2.0\", \"configurations\": []}")

    val config = LaunchConfig(launches = listOf(LaunchEntry(name = "runApp")))
    val context = LaunchConfigContext(file = configPath, baseDir = issueDir, config = config, workingDir = issueDir)

    val wrote = VscodeInit.writeVscodeTasksAndLaunch(context, issueDir, configPath)
    assertFalse(wrote)

    assertEquals("{\"version\": \"2.0.0\", \"tasks\": []}", Files.readString(vscodeDir.resolve("tasks.json")))
    assertEquals(
      "{\"version\": \"0.2.0\", \"configurations\": []}",
      Files.readString(vscodeDir.resolve("launch.json"))
    )
  }
}
