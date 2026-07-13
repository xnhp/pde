package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.workspace.JdtBuildInputJson
import org.eclipse.core.resources.IncrementalProjectBuilder
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.equinox.app.IApplication
import org.eclipse.equinox.app.IApplicationContext
import java.nio.file.Path
import java.util.logging.Logger

class JdtBuildApplication : IApplication {
    private val logger = Logger.getLogger(JdtBuildApplication::class.java.name)

    override fun start(context: IApplicationContext): Any {
        val args = applicationArgs(context)
        val inputPath = parseInputPath(args)
        val input = JdtBuildInputJson.read(inputPath)

        val workspace = ResourcesPlugin.getWorkspace()
        val monitor = NullProgressMonitor()

        logger.info("Starting JDT build: ${input.projects.size} projects, fullRebuild=${input.fullRebuild}")

        val buildKind = if (input.fullRebuild) IncrementalProjectBuilder.FULL_BUILD
                        else IncrementalProjectBuilder.INCREMENTAL_BUILD

        workspace.build(buildKind, monitor)

        logger.info("JDT build complete")

        return IApplication.EXIT_OK
    }

    override fun stop() = Unit

    companion object {
        const val APPLICATION_ID = "cn.varsa.pde.jdt_build"

        internal fun parseInputPath(args: Array<String>): Path {
            args.forEach { arg ->
                if (arg.startsWith("--input=")) {
                    return Path.of(arg.substringAfter('='))
                }
            }
            val index = args.indexOfFirst { it == "--input" || it == "-input" }
            require(index >= 0 && index < args.lastIndex) {
                "Missing build input. Pass --input <path>."
            }
            return Path.of(args[index + 1])
        }

        @Suppress("UNCHECKED_CAST")
        private fun applicationArgs(context: IApplicationContext): Array<String> {
            val arguments = context.arguments[IApplicationContext.APPLICATION_ARGS]
            return arguments as? Array<String> ?: emptyArray()
        }
    }
}
