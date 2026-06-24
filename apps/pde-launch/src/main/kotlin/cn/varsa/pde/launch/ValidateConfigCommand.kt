package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import java.nio.file.Path

internal object ValidateConfigCommand {
  fun main(args: Array<String>): Int {
    if (args.size != 1) {
      System.err.println("Usage: pde validate-config <file>")
      return 1
    }

    val path = Path.of(args[0])
    val normalized = path.toAbsolutePath().normalize()
    return try {
      LaunchConfigLoader.load(path)
      println("Config is valid: $normalized")
      0
    } catch (ex: Exception) {
      System.err.println(formatError(normalized, ex))
      1
    }
  }

  private fun formatError(path: Path, ex: Exception): String {
    val message = ex.message?.trim().orEmpty()
    if (message.startsWith("Invalid config ")) return message
    return "Invalid config $path:\n${message.ifBlank { ex::class.java.simpleName }}"
  }
}
