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
                // A persisted workspace can be reused across many days of ongoing development on the
                // underlying checkout (branch switches, pulls, new files) without Eclipse ever being told
                // the filesystem changed underneath it -- auto-build is deliberately off (see above), so
                // nothing implicitly re-syncs it either. Without this, PDE API Tools builds its API
                // description from a stale resource/Java model and reports spurious "no longer an API" /
                // "no longer used" problems for content that changed after the project was first created.
                logger.info("Refreshing existing project $projectName to pick up on-disk changes")
                project.refreshLocal(IResource.DEPTH_INFINITE, monitor)
            } else {
                createProject(project, projectSpec, input.targetClasspath, knownBsns, monitor)
            }
        }

        workspace.save(true, monitor)
        logger.info("Workspace setup complete: ${input.projects.size} projects")
    }

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

        // `dependencies` includes ALL of this bundle's dependency BSNs (workspace projects and
        // target-platform bundles alike) for build-order purposes; only ones with a corresponding
        // workspace project actually become referenced projects here.
        val referencedProjectNames = spec.dependencies.filter { it in knownBsns }.map { depBsn -> projectName(depBsn) }
        if (referencedProjectNames.isNotEmpty()) {
            desc.referencedProjects = referencedProjectNames.map { ResourcesPlugin.getWorkspace().root.getProject(it) }.toTypedArray()
        }

        project.create(desc, monitor)
        project.open(IResource.NONE, monitor)

        val javaProject = JavaCore.create(project)

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

        javaProject.setRawClasspath(classpathEntries.toTypedArray(), monitor)

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
