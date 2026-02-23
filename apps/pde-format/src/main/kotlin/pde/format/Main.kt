package pde.format

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parseResult = CliParser(args).parse()
    if (parseResult.showHelp) {
        println(CliParser.usage())
        return
    }

    val options = parseResult.options
    if (options.inputFile == null && options.repoDir == null && options.stdinPaths.isEmpty()) {
        return
    }
    val formatter = FormatterCache.getOrCreate(options)
    val runner = FormatterRunner(formatter, options)
    val result = runner.run()

    if (options.mode == Mode.CHECK) {
        exitProcess(if (result.changed) 1 else 0)
    }
}
