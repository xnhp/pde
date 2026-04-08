package cn.varsa.pde.launch

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object SchemaCommand {
  fun main(args: Array<String>): Int {
    if (args.isNotEmpty()) {
      System.err.println("Usage: pde schema")
      return 1
    }
    println(resolvePdeSchemaPath().toAbsolutePath().normalize())
    return 0
  }

  private fun resolvePdeSchemaPath(): Path {
    val start = Paths.get("").toAbsolutePath().normalize()
    findSchemaPathNear(start)?.let { return it }

    val resource = SchemaCommand::class.java.classLoader.getResource("schema/pde.schema.yaml")
    if (resource != null && resource.protocol == "file") {
      return Paths.get(resource.toURI()).toAbsolutePath().normalize()
    }

    val stream = SchemaCommand::class.java.classLoader.getResourceAsStream("schema/pde.schema.yaml")
      ?: error("Missing schema resource: schema/pde.schema.yaml")
    val stateDir = Paths.get(System.getProperty("user.home"), ".local", "state", "pde", "schema")
    Files.createDirectories(stateDir)
    val target = stateDir.resolve("pde.schema.yaml")
    stream.use { input ->
      Files.newOutputStream(target).use { output -> input.copyTo(output) }
    }
    return target.toAbsolutePath().normalize()
  }

  private fun findSchemaPathNear(start: Path): Path? {
    var current: Path? = start
    while (current != null) {
      val candidates = listOf(
        current.resolve("core/pde-launch-engine/src/main/resources/schema/pde.schema.yaml"),
        current.resolve("core/pde-launch-engine/build/resources/main/schema/pde.schema.yaml")
      )
      val found = candidates.firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
      if (found != null) return found.toAbsolutePath().normalize()
      current = current.parent
    }
    return null
  }
}
