package cn.varsa.pde.resolver.workspace

import org.eclipse.core.resources.ICommand
import org.eclipse.core.resources.IFolder
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IProjectDescription
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.jdt.core.IClasspathEntry
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.launching.JavaRuntime
import java.nio.file.Paths
import java.util.logging.Logger

class WorkspaceSetupService {
    private val logger = Logger.getLogger(WorkspaceSetupService::class.java.name)

    fun setup(input: WorkspaceSetupInput) {
        val workspace = ResourcesPlugin.getWorkspace()
        val root = workspace.root
        val monitor = NullProgressMonitor()

        val bsnToProjectName = input.projects.associate { spec ->
            spec.bsn to invisibleProjectName(spec.bsn, spec.bundlePath)
        }

        for (projectSpec in input.projects) {
            val projectName = invisibleProjectName(projectSpec.bsn, projectSpec.bundlePath)
            logger.info("Setting up project $projectName for ${projectSpec.bsn} at ${projectSpec.bundlePath}")

            val project = root.getProject(projectName)
            if (project.exists()) {
                project.open(IResource.NONE, monitor)
            } else {
                createProject(root, project, projectSpec, input.targetClasspath, bsnToProjectName, monitor)
            }
        }

        workspace.save(true, monitor)
        logger.info("Workspace setup complete: ${input.projects.size} projects")
    }

    private fun createProject(
        root: IWorkspaceRoot,
        project: IProject,
        spec: WorkspaceProjectSpec,
        targetClasspath: List<String>,
        bsnToProjectName: Map<String, String>,
        monitor: IProgressMonitor
    ) {
        val desc = ResourcesPlugin.getWorkspace().newProjectDescription(project.name)
        desc.natureIds = arrayOf(JavaCore.NATURE_ID)
        desc.buildSpec = arrayOf(createBuildCommand(desc, "org.eclipse.jdt.core.javabuilder"))

        val referencedProjectNames = spec.dependencies.mapNotNull { depBsn ->
            bsnToProjectName[depBsn]
        }
        if (referencedProjectNames.isNotEmpty()) {
            desc.referencedProjects = referencedProjectNames.map { root.getProject(it) }.toTypedArray()
        }

        project.create(desc, monitor)
        project.open(IResource.NONE, monitor)

        val bundlePath = Paths.get(spec.bundlePath)
        val linkedFolder = project.getFolder("_")
        linkedFolder.createLink(bundlePath.toUri(), IResource.NONE, monitor)

        val javaProject = JavaCore.create(project)

        val classpathEntries = mutableListOf<IClasspathEntry>()

        for (srcRoot in spec.sourceRoots) {
            val sourceFolder = linkedFolder.getFolder(srcRoot)
            val outputPath = if (spec.outputDirectory.isNotEmpty()) {
                linkedFolder.getFolder(spec.outputDirectory).fullPath
            } else {
                null
            }
            classpathEntries += JavaCore.newSourceEntry(
                sourceFolder.fullPath,
                null, null,
                outputPath
            )
        }

        classpathEntries += JavaRuntime.getDefaultJREContainerEntry()

        for (jar in targetClasspath) {
            classpathEntries += JavaCore.newLibraryEntry(
                org.eclipse.core.runtime.Path(jar),
                null, null,
                false
            )
        }

        for (entry in spec.classpath) {
            when (entry.kind) {
                "lib" -> classpathEntries += JavaCore.newLibraryEntry(
                    org.eclipse.core.runtime.Path(entry.path),
                    entry.sourcePath?.let { org.eclipse.core.runtime.Path(it) },
                    null,
                    false
                )
                "src" -> {
                    val depProjectName = bsnToProjectName[entry.path]
                    if (depProjectName != null) {
                        classpathEntries += JavaCore.newProjectEntry(
                            org.eclipse.core.runtime.Path("/$depProjectName"),
                            null, false,
                            null, false
                        )
                    } else {
                        logger.warning("Unknown source dependency BSN: ${entry.path} for project ${spec.bsn}")
                    }
                }
            }
        }

        javaProject.setRawClasspath(classpathEntries.toTypedArray(), monitor)

        if (spec.compilerPrefs.isNotEmpty()) {
            val options = javaProject.getOptions(false)
            spec.compilerPrefs.forEach { (key, value) ->
                options[key] = value
            }
            javaProject.setOptions(options)
        }
    }

    private fun createBuildCommand(desc: IProjectDescription, builderId: String): ICommand {
        val command = desc.newCommand()
        command.builderName = builderId
        return command
    }

    companion object {
        fun invisibleProjectName(bsn: String, bundlePath: String): String {
            val basename = Paths.get(bundlePath).fileName?.toString() ?: bsn
            val hash = Integer.toHexString(bundlePath.hashCode())
            return "${basename}_${hash}"
        }
    }
}
