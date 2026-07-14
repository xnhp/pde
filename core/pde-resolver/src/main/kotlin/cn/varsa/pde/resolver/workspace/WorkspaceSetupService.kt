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

        // PDE's WorkspaceModelManager (PluginRegistry.findModel) discovers a project's plugin
        // model by looking for META-INF/MANIFEST.MF at the project-relative path, regardless of
        // project nature. It won't find it nested inside the "_" linked folder, so link it a
        // second time directly at the project root. This is a location "overlap" with the "_"
        // link, but Eclipse only warns (IResourceStatus.OVERLAPPING_LOCATION), it doesn't block
        // creation. Without this, ProjectComponent.getModel() aborts with a CoreException the
        // first time it's needed (e.g. resolving the bundle description), which is exactly when
        // since-tag analysis runs.
        val manifestDir = bundlePath.resolve("META-INF")
        if (java.nio.file.Files.isDirectory(manifestDir)) {
            project.getFolder("META-INF").createLink(manifestDir.toUri(), IResource.NONE, monitor)
        }

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

        // Sibling workspace bundles this bundle depends on. `dependencies`/referencedProjectNames
        // above only control JDT build ORDER; without a project classpath entry too, this
        // project's compiler has no visibility into the dependency's exported types at all (its
        // own classPathEntries only ever cover its own bundle directory, and targetClasspath
        // excludes workspace bundles by design).
        for (depProjectName in referencedProjectNames) {
            classpathEntries += JavaCore.newProjectEntry(
                org.eclipse.core.runtime.Path("/$depProjectName"),
                null, false,
                null, false
            )
        }

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

        // PDE API Tools' ProjectComponent.createApiTypeContainers() discovers a project's API types
        // for since-tag analysis by reading build.properties (via PluginRegistry.createBuildModel):
        // each source.<jar> entry is resolved with project.findMember() and mapped to that source
        // folder's compiled output folder. Our invisible-project layout nests the bundle under the
        // "_" WORKSPACE_LINK folder (like eclipse.jdt.ls), so these paths must be "_"-prefixed to
        // resolve. Without this file no type containers are built, the analyzed type is reported as
        // REMOVED instead of ADDED, and the since-tag check (which only runs on ADDED elements) never
        // fires. Bundle-ClassPath is assumed to be the default ".".
        if (spec.sourceRoots.isNotEmpty()) {
            val output = spec.outputDirectory.ifEmpty { "bin" }
            val buildProps = buildString {
                append("source.. = ")
                append(spec.sourceRoots.joinToString(",") { "_/$it/" })
                append("\n")
                append("output.. = _/$output/\n")
            }
            val buildPropsFile = project.getFile("build.properties")
            if (!buildPropsFile.exists()) {
                buildPropsFile.create(buildProps.byteInputStream(), IResource.NONE, monitor)
            }
        }

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
