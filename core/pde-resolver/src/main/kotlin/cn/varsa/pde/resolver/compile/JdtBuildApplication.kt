package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.workspace.JdtBuildDiagnostic
import cn.varsa.pde.resolver.workspace.JdtBuildInput
import cn.varsa.pde.resolver.workspace.JdtBuildInputJson
import cn.varsa.pde.resolver.workspace.JdtBuildReport
import cn.varsa.pde.resolver.workspace.JdtBuildResult
import cn.varsa.pde.resolver.workspace.JdtBuildResultJson
import org.eclipse.core.resources.IMarker
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IncrementalProjectBuilder
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.CoreException
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
        val projects = workspace.root.projects.filter { it.isOpen }

        logger.info("Starting JDT build: ${projects.size} open projects, fullRebuild=${input.fullRebuild}")

        val buildKind = if (input.fullRebuild) IncrementalProjectBuilder.FULL_BUILD
                        else IncrementalProjectBuilder.INCREMENTAL_BUILD

        val result = try {
            try {
                workspace.build(buildKind, monitor)
                JdtBuildResult.from(projects.size, collectDiagnostics(projects))
            } catch (e: CoreException) {
                logger.severe("JDT build threw: ${e.status}")
                JdtBuildResult.from(projects.size, emptyList(), failure = e.message ?: e.status.message)
            }
        } finally {
            // JavaBuilder persists its incremental state (state.dat) only on a full workspace save;
            // without it every invocation is a full build.
            saveWorkspace(monitor)
        }

        report(result, input)
        return if (result.success) IApplication.EXIT_OK else EXIT_BUILD_FAILED
    }

    private fun saveWorkspace(monitor: NullProgressMonitor) {
        val started = System.nanoTime()
        try {
            ResourcesPlugin.getWorkspace().save(true, monitor)
            logger.info("Saved workspace state in ${(System.nanoTime() - started) / 1_000_000} ms")
        } catch (e: CoreException) {
            logger.severe("Saving workspace state failed (next build will be a full build): ${e.status}")
        }
    }

    private fun report(result: JdtBuildResult, input: JdtBuildInput) {
        JdtBuildReport.renderStdout(result).forEach(::println)
        System.out.flush()
        input.resultPath?.let { JdtBuildResultJson.write(result, Path.of(it)) }
    }

    override fun stop() = Unit

    companion object {
        const val APPLICATION_ID = "cn.varsa.pde.jdt_build"
        // Eclipse's launcher uses an Integer return value from IApplication#start as the process exit code.
        internal val EXIT_BUILD_FAILED: Any = 1

        internal fun collectDiagnostics(projects: List<IProject>): List<JdtBuildDiagnostic> =
            projects.flatMap { project ->
                project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)
                    .mapNotNull { toDiagnostic(project, it) }
            }

        private fun toDiagnostic(project: IProject, marker: IMarker): JdtBuildDiagnostic? {
            val severity = when (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)) {
                IMarker.SEVERITY_ERROR -> JdtBuildDiagnostic.SEVERITY_ERROR
                IMarker.SEVERITY_WARNING -> JdtBuildDiagnostic.SEVERITY_WARNING
                else -> return null
            }
            val resource = marker.resource
            val path = resource.location?.toOSString() ?: resource.fullPath.toString()
            val line = marker.getAttribute(IMarker.LINE_NUMBER, -1).takeIf { it > 0 }
            return JdtBuildDiagnostic(
                severity = severity,
                project = project.name,
                path = path,
                line = line,
                message = marker.getAttribute(IMarker.MESSAGE, "")
            )
        }

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
