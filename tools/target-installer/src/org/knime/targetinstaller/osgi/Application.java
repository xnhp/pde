package org.knime.targetinstaller.osgi;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.operations.InstallOperation;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.knime.targetinstaller.utils.Operation;
import org.knime.targetinstaller.utils.ReadProperties;
import org.knime.targetinstaller.utils.Repository;
import org.knime.targetinstaller.utils.TargetFileParser;
import org.knime.targetinstaller.utils.Arguments;
import org.eclipse.equinox.p2.engine.ProvisioningContext;
import java.net.URI;
import org.tinylog.Logger;

import java.nio.file.Path;

import static org.knime.targetinstaller.utils.Utils.createDirIfNotExist;
import static org.knime.targetinstaller.utils.Utils.deleteProfileIfExists;
import static org.knime.targetinstaller.utils.Utils.patchTrustedAuthorities;
import static org.knime.targetinstaller.utils.Utils.printProfileSummary;
import static org.knime.targetinstaller.utils.Utils.upsertProfile;

public class Application implements IApplication{
    @Override
    public Object start(final IApplicationContext context) throws Exception {
        Logger.debug("Application.start");

        var rawArguments = (String[]) ((java.util.Map<?, ?>) context.getArguments()).get(IApplicationContext.APPLICATION_ARGS);
        var baseDir = Path.of("").toAbsolutePath().normalize();
        var resolvedConfig = ReadProperties.readFromArgs(rawArguments, baseDir).orElseThrow(() -> new IllegalArgumentException(
                "Missing installer configuration. Pass -profileId, -p2Path, -targetDefinition, -install, -bundlePool."
        ));
        var arguments = new Arguments(context);
        Logger.info("Using configuration from program arguments.");
        Logger.info("Using TP definition: " + resolvedConfig.targetDefinition());

        // note: need to be connected to VPN in order to resolve/fetch from KNIME update sites (also public)

        // devving on this will still use the epp plugin, but independently of the ap-with-epp effort and only using
        //  /home/ben/eclipse as target location

        var profileId = resolvedConfig.profileId();
        var p2Path = resolvedConfig.p2Path();
        var agent = Activator.getProvisioningAgent(p2Path.toUri());
        var targetDefinition = TargetFileParser.parseTargetFile(resolvedConfig.targetDefinition().toString());
        var installFolder = createDirIfNotExist(resolvedConfig.installFolder());
        var bundlePool = createDirIfNotExist(resolvedConfig.bundlePool());

        // TODO resources/profiles/org.eclipse.equinox.p2.engine/.settings also caches repositories
        // e.g. if we earlier had a repo that times out and removed it from the .target file it would still
        // cause this script to stall /timeout
        deleteProfileIfExists(profileId, agent, p2Path);

        // NOTE a profile might not be easily moved to a different location on the file system.
        //  properties like org.eclipse.equinox.p2.installFolder or .cache would keep the old path.
        //  updating the properties map does not seem to have any effect
        var profile = upsertProfile(
                profileId,
                agent,
                installFolder, // probably not really important
                bundlePool
        );

        // see CertificateChecker#checkCertificates > getUnsignedContentPolicy
        // this property skips the entire method
        System.setProperty("eclipse.p2.unsignedPolicy", "allow");
        patchTrustedAuthorities(agent, profile);

        var repository = new Repository(agent);
        repository.load(targetDefinition.repoURIs());

        var installableUnits = repository.queryIUs(targetDefinition.iuRefs());
        if (installableUnits.isEmpty()) {
            System.err.println("No IUs were found to install. Exiting.");
            return null;
        }

        var session = new ProvisioningSession(agent);
        var operation = new InstallOperation(session, installableUnits);
        initProvisioningContext(agent, targetDefinition, operation);

        new Operation(profile.getProfileId(), operation, arguments.isDryRun(), targetDefinition.includeConfigurePhase())
                .resolve()
                .ifPresent(Operation::execute);

//        printProfileSummary(agent, profileId);

        return null;
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
