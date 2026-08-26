package cn.varsa.pde.resolver.workspace

import org.eclipse.core.resources.ICommand
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IProjectDescription
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.Path as EclipsePath
import org.eclipse.jdt.core.IClasspathEntry
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.launching.JavaRuntime
import java.util.logging.Logger

class WorkspaceSetupService {
    private val logger = Logger.getLogger(WorkspaceSetupService::class.java.name)

    fun setup(input: WorkspaceSetupInput) {
        val workspace = ResourcesPlugin.getWorkspace()
        val root = workspace.root
        val monitor = NullProgressMonitor()

        // Disable auto-build so that creating .classpath files does not trigger JDT compilation
        // on a background thread — we already have compiled output on disk. Auto-build in
        // Equinox headless also hits a NullPointerException in JDT's ReadManager.readAhead() ->
        // FileUtil.getCharset() because the preferences service (Platform.getPreferencesService())
        // is not initialized.
        val desc = workspace.description
        if (desc.isAutoBuilding) {
            desc.isAutoBuilding = false
            workspace.setDescription(desc)
        }

        val knownBsns = input.projects.map { it.bsn }.toSet()

        for (projectSpec in input.projects) {
            val projectName = projectName(projectSpec.bsn)
            logger.info("Setting up project $projectName for ${projectSpec.bsn} at ${projectSpec.bundlePath}")

            val project = root.getProject(projectName)
            if (project.exists()) {
                project.open(IResource.NONE, monitor)
                updateProject(project, projectSpec, input.targetClasspath, knownBsns, monitor)
            } else {
                createProject(project, projectSpec, input.targetClasspath, knownBsns, monitor)
            }
        }

        workspace.save(true, monitor)
        logger.info("Workspace setup complete: ${input.projects.size} projects")
    }

    /**
     * Re-init of an existing project: bring natures, referenced projects and the `.classpath` in
     * line with the current input. The `.project` builder set is left as is, and files are only
     * rewritten when they differ, so an unchanged re-init leaves timestamps alone.
     */
    private fun updateProject(
        project: IProject,
        spec: WorkspaceProjectSpec,
        targetClasspath: List<String>,
        knownBsns: Set<String>,
        monitor: IProgressMonitor
    ) {
        val referencedProjectNames = referencedProjectNames(spec, knownBsns)
        val desc = project.description
        var descChanged = false
        if (!desc.natureIds.contains(JavaCore.NATURE_ID)) {
            desc.natureIds = desc.natureIds + JavaCore.NATURE_ID
            descChanged = true
        }
        val currentRefs = desc.referencedProjects.map { it.name }
        if (currentRefs != referencedProjectNames) {
            desc.referencedProjects = referencedProjectNames.map { ResourcesPlugin.getWorkspace().root.getProject(it) }.toTypedArray()
            descChanged = true
        }
        if (descChanged) {
            logger.info("Updating .project natures/references for ${project.name}")
            project.setDescription(desc, monitor)
        }

        val javaProject = JavaCore.create(project)
        val entries = buildClasspathEntries(project, spec, targetClasspath, knownBsns, referencedProjectNames)
        if (!javaProject.rawClasspath.contentEquals(entries)) {
            logger.info("Updating .classpath for ${project.name}")
            javaProject.setRawClasspath(entries, monitor)
        }
    }

    // `dependencies` includes ALL of this bundle's dependency BSNs (workspace projects and
    // target-platform bundles alike) for build-order purposes; only ones with a corresponding
    // workspace project actually become referenced projects.
    private fun referencedProjectNames(spec: WorkspaceProjectSpec, knownBsns: Set<String>): List<String> =
        spec.dependencies.filter { it in knownBsns }.map { depBsn -> projectName(depBsn) }

    private fun createProject(
        project: IProject,
        spec: WorkspaceProjectSpec,
        targetClasspath: List<String>,
        knownBsns: Set<String>,
        monitor: IProgressMonitor
    ) {
        val desc = ResourcesPlugin.getWorkspace().newProjectDescription(project.name)
        desc.setLocation(EclipsePath(spec.bundlePath))
        desc.natureIds = arrayOf(JavaCore.NATURE_ID)
        desc.buildSpec = arrayOf(createBuildCommand(desc, "org.eclipse.jdt.core.javabuilder"))

        val referencedProjectNames = referencedProjectNames(spec, knownBsns)
        if (referencedProjectNames.isNotEmpty()) {
            desc.referencedProjects = referencedProjectNames.map { ResourcesPlugin.getWorkspace().root.getProject(it) }.toTypedArray()
        }

        project.create(desc, monitor)
        project.open(IResource.NONE, monitor)

        val javaProject = JavaCore.create(project)
        javaProject.setRawClasspath(buildClasspathEntries(project, spec, targetClasspath, knownBsns, referencedProjectNames), monitor)
        writeBuildProperties(project, spec, monitor)
        applyCompilerPrefs(javaProject, spec)
    }

    private fun buildClasspathEntries(
        project: IProject,
        spec: WorkspaceProjectSpec,
        targetClasspath: List<String>,
        knownBsns: Set<String>,
        referencedProjectNames: List<String>
    ): Array<IClasspathEntry> {
        val classpathEntries = mutableListOf<IClasspathEntry>()

        for (srcRoot in spec.sourceRoots) {
            val sourceFolder = project.getFolder(srcRoot)
            val outputPath = if (spec.outputDirectory.isNotEmpty()) {
                project.getFolder(spec.outputDirectory).fullPath
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
                EclipsePath("/$depProjectName"),
                null, false,
                null, false
            )
        }

        for (jar in targetClasspath) {
            classpathEntries += JavaCore.newLibraryEntry(
                EclipsePath(jar),
                null, null,
                false
            )
        }

        for (entry in spec.classpath) {
            when (entry.kind) {
                "lib" -> classpathEntries += JavaCore.newLibraryEntry(
                    EclipsePath(entry.path),
                    entry.sourcePath?.let { EclipsePath(it) },
                    null,
                    false
                )
                "src" -> {
                    if (entry.path in knownBsns) {
                        classpathEntries += JavaCore.newProjectEntry(
                            EclipsePath("/${projectName(entry.path)}"),
                            null, false,
                            null, false
                        )
                    } else {
                        logger.warning("Unknown source dependency BSN: ${entry.path} for project ${spec.bsn}")
                    }
                }
            }
        }

        return classpathEntries.toTypedArray()
    }

    private fun writeBuildProperties(project: IProject, spec: WorkspaceProjectSpec, monitor: IProgressMonitor) {
        // PDE API Tools' ProjectComponent.createApiTypeContainers() discovers a project's API types
        // for since-tag analysis by reading build.properties (via PluginRegistry.createBuildModel):
        // each source.<jar> entry is resolved with project.findMember() and mapped to that source
        // folder's compiled output folder. Without this file no type containers are built, the
        // analyzed type is reported as REMOVED instead of ADDED, and the since-tag check (which
        // only runs on ADDED elements) never fires. Bundle-ClassPath is assumed to be the default ".".
        if (spec.sourceRoots.isNotEmpty()) {
            val output = spec.outputDirectory.ifEmpty { "bin" }
            val buildProps = buildString {
                append("source.. = ")
                append(spec.sourceRoots.joinToString(",") { "$it/" })
                append("\n")
                append("output.. = $output/\n")
            }
            val buildPropsFile = project.getFile("build.properties")
            if (!buildPropsFile.exists()) {
                buildPropsFile.create(buildProps.byteInputStream(), IResource.NONE, monitor)
            }
        }
    }

    private fun applyCompilerPrefs(javaProject: IJavaProject, spec: WorkspaceProjectSpec) {
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
        /**
         * Visible-mode projects are placed directly at their bundle's real filesystem location
         * ([IProjectDescription.setLocation]), so the BSN alone is a stable, unique Eclipse
         * project name — no path-derived disambiguation needed.
         */
        fun projectName(bsn: String): String = bsn
    }
}
