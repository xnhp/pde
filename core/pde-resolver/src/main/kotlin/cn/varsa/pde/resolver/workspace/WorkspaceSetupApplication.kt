package cn.varsa.pde.resolver.workspace

import org.eclipse.equinox.app.IApplication
import org.eclipse.equinox.app.IApplicationContext
import org.osgi.framework.Bundle
import org.osgi.framework.FrameworkUtil
import java.nio.file.Path

class WorkspaceSetupApplication : IApplication {
    override fun start(context: IApplicationContext): Any {
        val args = applicationArgs(context)
        val inputPath = parseInputPath(args)
        return try {
            val input = WorkspaceSetupInputJson.read(inputPath)
            val service = WorkspaceSetupService()
            service.setup(input)
            IApplication.EXIT_OK
        } finally {
            stopDebugBundlesBeforeRegistryShutdown()
        }
    }

    override fun stop() = Unit

    companion object {
        const val APPLICATION_ID = "cn.varsa.pde.workspace_setup"

        internal fun parseInputPath(args: Array<String>): Path {
            args.forEach { arg ->
                if (arg.startsWith("--input=")) {
                    return Path.of(arg.substringAfter('='))
                }
            }
            val index = args.indexOfFirst { it == "--input" || it == "-input" }
            require(index >= 0 && index < args.lastIndex) {
                "Missing setup input. Pass --input <path>."
            }
            return Path.of(args[index + 1])
        }

        @Suppress("UNCHECKED_CAST")
        private fun applicationArgs(context: IApplicationContext): Array<String> {
            val arguments = context.arguments[IApplicationContext.APPLICATION_ARGS]
            return arguments as? Array<String> ?: emptyArray()
        }

        private fun stopDebugBundlesBeforeRegistryShutdown() {
            val context = FrameworkUtil.getBundle(WorkspaceSetupApplication::class.java)?.bundleContext ?: return
            listOf(
                "org.eclipse.jdt.debug",
                "org.eclipse.jdt.launching",
                "org.eclipse.debug.core"
            ).forEach { symbolicName ->
                val bundle = context.bundles.firstOrNull { it.symbolicName == symbolicName } ?: return@forEach
                if (bundle.state == Bundle.ACTIVE || bundle.state == Bundle.STARTING) {
                    bundle.stop(Bundle.STOP_TRANSIENT)
                }
            }
        }
    }
}
