package org.knime.targetinstaller.osgi;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.operations.InstallOperation;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.knime.targetinstaller.utils.Operation;
import org.knime.targetinstaller.utils.ReadProperties;
import org.knime.targetinstaller.utils.Repository;
import org.knime.targetinstaller.utils.TargetFileParser;
import org.knime.targetinstaller.utils.Arguments;
import org.knime.targetinstaller.utils.Utils;
import org.eclipse.equinox.p2.engine.ProvisioningContext;
import java.net.URI;
import org.tinylog.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.knime.targetinstaller.utils.Utils.createDirIfNotExist;
import static org.knime.targetinstaller.utils.Utils.deleteProfileIfExists;
import static org.knime.targetinstaller.utils.Utils.normalizeProfileRegistryPath;
import static org.knime.targetinstaller.utils.Utils.patchTrustedAuthorities;
import static org.knime.targetinstaller.utils.Utils.trustAllAuthoritiesIfOptedIn;
import static org.knime.targetinstaller.utils.Utils.printProfileSummary;
import static org.knime.targetinstaller.utils.Utils.upsertProfile;

public class Application implements IApplication{
    // Process exit codes for the failures that leave no usable profile. Equinox uses an Integer returned from
    // start() as the exit code, while null means 0, which is what every failure used to report.
    static final int EXIT_REPOSITORY_FAILED = 2;
    static final int EXIT_UNITS_NOT_FOUND = 3;
    static final int EXIT_RESOLUTION_FAILED = 4;
    static final int EXIT_PROVISIONING_FAILED = 5;

    /** An install that cannot complete, with the process exit code that reports it. */
    private static final class InstallFailure extends Exception {
        @SuppressWarnings("java:S116") // pde's code does not use the m_ field prefix
        private final int exitCode;

        InstallFailure(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }
    }

    // An InstallFailure's message is the whole report; logging its stack trace would only bury it.
    @SuppressWarnings("java:S1166")
    @Override
    public Object start(final IApplicationContext context) throws Exception {
        Logger.debug("Application.start");

        var rawArguments = (String[]) ((java.util.Map<?, ?>) context.getArguments()).get(IApplicationContext.APPLICATION_ARGS);
        var baseDir = Path.of("").toAbsolutePath().normalize();
        var resolvedConfig = ReadProperties.readFromArgs(rawArguments, baseDir).orElseThrow(() -> new IllegalArgumentException(
                "Missing installer configuration. Pass -profileId, -p2Path, -targetDefinition, -install, -bundlePool."
        ));
        var arguments = new Arguments(context);
        Utils.setInteractiveProgress(arguments.isInteractive());
        Logger.info("Using configuration from program arguments.");
        Logger.info("Using TP definition: " + resolvedConfig.targetDefinition());

        // note: need to be connected to VPN in order to resolve/fetch from KNIME update sites (also public)

        // devving on this will still use the epp plugin, but independently of the ap-with-epp effort and only using
        //  /home/ben/eclipse as target location

        try {
            install(resolvedConfig, arguments);
            return IApplication.EXIT_OK;
        } catch (InstallFailure failure) {
            Logger.error(failure.getMessage());
            return failure.exitCode;
        }
    }

    private static void install(ReadProperties.InstallConfiguration resolvedConfig, Arguments arguments)
            throws Exception {
        var p2Path = createDirIfNotExist(resolvedConfig.p2Path());
        var agent = Activator.getProvisioningAgent(p2Path.toUri());
        var targetDefinition = TargetFileParser.parseTargetFile(resolvedConfig.targetDefinition().toString());

        // Load the repositories and find the requested units before touching the existing profile, so that an
        // unreachable update site or a unit missing from it fails without destroying the last working install.
        var installableUnits = loadRequestedUnits(agent, targetDefinition);

        // TODO resources/profiles/org.eclipse.equinox.p2.engine/.settings also caches repositories
        // e.g. if we earlier had a repo that times out and removed it from the .target file it would still
        // cause this script to stall /timeout
        var profileId = resolvedConfig.profileId();
        deleteProfileIfExists(profileId, agent, p2Path);

        // NOTE a profile might not be easily moved to a different location on the file system.
        //  properties like org.eclipse.equinox.p2.installFolder or .cache would keep the old path.
        //  updating the properties map does not seem to have any effect
        var profile = upsertProfile(
                profileId,
                agent,
                createDirIfNotExist(resolvedConfig.installFolder()), // probably not really important
                createDirIfNotExist(resolvedConfig.bundlePool())
        );

        // see CertificateChecker#checkCertificates > getUnsignedContentPolicy
        // this property skips the entire method
        System.setProperty("eclipse.p2.unsignedPolicy", "allow");
        patchTrustedAuthorities(agent, profile);
        // Opt-in (PDE_TRUST_ALL_AUTHORITIES=true): skip AuthorityChecker's HttpClient-based certificate
        // gathering, which fails in sandboxes that block the NIO selector's loopback/AF_UNIX self-pipe.
        trustAllAuthoritiesIfOptedIn(agent, profile);

        var session = new ProvisioningSession(agent);
        var operation = new InstallOperation(session, installableUnits);
        initProvisioningContext(agent, targetDefinition, operation);

        var resolved = new Operation(profile.getProfileId(), operation, arguments.isDryRun(),
                resolvedConfig.includeConfigurePhase()).resolve();
        if (resolved.isEmpty()) {
            throw new InstallFailure(EXIT_RESOLUTION_FAILED, "Resolving the target failed; see the status above.");
        }
        if (!resolved.get().execute()) {
            throw new InstallFailure(EXIT_PROVISIONING_FAILED, "Provisioning the target failed; see the status above.");
        }
        normalizeProfileRegistryPath(profileId, p2Path);

//        printProfileSummary(agent, profileId);
    }

    /**
     * Loads the target's repositories and returns the units it requests. Fails when a repository cannot be loaded or
     * a requested unit is in none of them, since installing the rest would leave an incomplete target.
     */
    private static List<IInstallableUnit> loadRequestedUnits(IProvisioningAgent agent,
            TargetFileParser.ParsedTargetFile targetDefinition) throws Exception {
        var repository = new Repository(agent);
        var failedRepositories = repository.load(targetDefinition.repoURIs());
        if (!failedRepositories.isEmpty()) {
            throw new InstallFailure(EXIT_REPOSITORY_FAILED,
                    "%d of %d repositories could not be loaded; not installing an incomplete target."
                            .formatted(failedRepositories.size(), targetDefinition.repoURIs().size()));
        }
        var installableUnits = repository.queryIUs(targetDefinition.iuRefs());
        var unmatchedRefs = Repository.unmatchedRefs(targetDefinition.iuRefs(), installableUnits);
        if (!unmatchedRefs.isEmpty()) {
            var listing = unmatchedRefs.stream()
                    .map(ref -> (ref.version() == null) ? ref.id() : (ref.id() + " " + ref.version()))
                    .collect(Collectors.joining("\n- ", "\n- ", ""));
            throw new InstallFailure(EXIT_UNITS_NOT_FOUND,
                    "%d of %d units in the target definition were not found in its repositories:%s"
                            .formatted(unmatchedRefs.size(), targetDefinition.iuRefs().size(), listing));
        }
        if (installableUnits.isEmpty()) {
            throw new InstallFailure(EXIT_UNITS_NOT_FOUND, "The target definition lists no units to install.");
        }
        return Repository.selectIUs(targetDefinition.iuRefs(), installableUnits);
    }

    /**
     * PDE sets both FOLLOW_REPOSITORY_REFERENCES and FOLLOW_ARTIFACT_REPOSITORY_REFERENCES when it resolves a
     * target definition (see P2TargetUtils#upsertProfile / initialize). Without these flags, only the repositories
     * explicitly listed in the .target file are considered and any repository references advertised via p2 metadata
     * (e.g. KNIME's composite repo pointing to the matching Eclipse platform site that hosts the weaving bundles)
     * are silently ignored. That in turn causes the director to fail resolving bundles such as
     * org.eclipse.equinox.weaving.caching*. By initializing the provisioning context just like PDE does we let the
     * director follow the references and fetch the missing IUs from the Eclipse base site automatically.
     */
    private static void initProvisioningContext(IProvisioningAgent agent, TargetFileParser.ParsedTargetFile targetDefinition, InstallOperation operation) {
        var provisioningContext = new ProvisioningContext(agent);
        var repositoryUris = targetDefinition.repoURIs().toArray(URI[]::new);
        provisioningContext.setMetadataRepositories(repositoryUris);
        provisioningContext.setArtifactRepositories(repositoryUris);
        provisioningContext.setProperty(ProvisioningContext.FOLLOW_REPOSITORY_REFERENCES, Boolean.TRUE.toString());
        // ProvisioningContext.FOLLOW_ARTIFACT_REPOSITORY_REFERENCES
        provisioningContext.setProperty("org.eclipse.equinox.p2.director.followArtifactRepositoryReferences", Boolean.TRUE.toString());
        operation.setProvisioningContext(provisioningContext);
    }

    @Override
    public void stop() {

    }
}
