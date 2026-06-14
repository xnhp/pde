/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.org; Email: contact@knime.org
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 */

package org.knime.targetinstaller.utils;


import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.internal.p2.engine.Profile;
import org.eclipse.equinox.internal.p2.engine.phases.AuthorityChecker;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.tinylog.Logger;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.knime.targetinstaller.utils.ConsoleColors.green;

public class Utils {
    public static void patchTrustedAuthorities(IProvisioningAgent agent, IProfile profile) throws URISyntaxException {
        // this helps with untrusted _authorities_ but not certificates (also checked in #checkCertificates)
        // for user prompot, see CertificateChecker, call IArtifactUIServices.getTrustInfo
        var authChecker = new AuthorityChecker(agent, profile);
        var trustedAuths = authChecker.getPreferenceTrustedAuthorities();
        // probably value from .target file (repository > location)
        // no trailing slash! see /home/ben/eclipse/plugins/org.eclipse.equinox.p2.engine.source_2.10.0.v20240210-0918.jar!/org/eclipse/equinox/internal/p2/engine/phases/AuthorityChecker.java:113
        trustedAuths.add(new URI("https://update.knime.com"));
        trustedAuths.add(new URI("https://jenkins.devops.knime.com"));
        trustedAuths.add(new URI("https://jenkins.knime.com"));
        authChecker.persistTrustedAuthorities(trustedAuths);  // not sure why this is an instance method
    }

    /**
     * Name of the opt-in environment variable (also honoured as the {@code pde.trustAllAuthorities}
     * system property) that, when truthy, makes the installer trust all p2 authorities.
     */
    public static final String TRUST_ALL_AUTHORITIES_ENV = "PDE_TRUST_ALL_AUTHORITIES";

    /** @return {@code true} if trust-all was opted into via env var or system property. */
    public static boolean trustAllAuthoritiesOptedIn() {
        var value = System.getenv(TRUST_ALL_AUTHORITIES_ENV);
        if (value == null) {
            value = System.getProperty("pde.trustAllAuthorities");
        }
        return value != null
                && ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value));
    }

    /**
     * Opt-in escape hatch: trust all p2 authorities so {@link AuthorityChecker#start} returns before it
     * builds a {@link java.net.http.HttpClient}. That client opens an NIO selector whose self-pipe needs a
     * loopback / AF_UNIX socket; in sandboxed environments that connection is blocked and provisioning dies
     * in the Collect phase with "Unable to establish loopback connection". Trusting all authorities skips the
     * certificate gathering entirely.
     * <p>
     * Off by default and intentionally so: it disables remote authority verification. Enable only for trusted
     * or local ({@code file:}) targets, via {@code PDE_TRUST_ALL_AUTHORITIES=true}. A normally-reachable,
     * fully-trusted target does not need it (no untrusted authorities to gather certificates for).
     */
    public static void trustAllAuthoritiesIfOptedIn(IProvisioningAgent agent, IProfile profile) {
        if (!trustAllAuthoritiesOptedIn()) {
            return;
        }
        var status = new AuthorityChecker(agent, profile).setTrustAlways(true);
        if (status != null && !status.isOK()) {
            Logger.warn("Could not persist trustAllAuthorities preference: {}", status.getMessage());
        }
        System.out.printf("%s set: trusting all p2 authorities (remote authority verification disabled)%n",
                TRUST_ALL_AUTHORITIES_ENV);
    }

    public static Path createDirIfNotExist(Path target) {
        if (!target.toFile().exists()) {
            if (!target.toFile().mkdirs()) {
                throw new RuntimeException("Failed to create directory: " + target);
            }
        }
        return target;
    }

    public static void printProfileSummary(IProvisioningAgent agent, String profileId) {
        var registry = agent.getService(IProfileRegistry.class);
        var profile = registry.getProfile(profileId);

        // Query all units in the profile
        IQueryResult<IInstallableUnit> allIUsResult =
                profile.query(QueryUtil.ALL_UNITS, new NullProgressMonitor());

        Set<IInstallableUnit> allIUs = allIUsResult.toSet();
        Logger.info(
                String.format("Currently installed IUs in profile %s: %s%n", profileId, allIUs.size())
        );
//        for (IInstallableUnit iu : allIUs) {
//            System.out.println(" - " + iu.getId() + " " + iu.getVersion());
//        }
    }

    public static IProfile upsertProfile(
            String profileId,
            IProvisioningAgent agent,
            Path installFolder, Path bundlePool) throws ProvisionException {
        var registry = agent.getService(IProfileRegistry.class);
        var existingProfile = registry.getProfile(profileId);
        if (existingProfile != null) {
//            registry.removeProfile(profileId);
            Logger.debug("%s: Profile already exists%n", profileId);
//            updatePropIfChanged(existingProfile, IProfile.PROP_CACHE, bundlePool.toAbsolutePath().toString());
//            updatePropIfChanged(existingProfile, IProfile.PROP_INSTALL_FOLDER, installFolder.toAbsolutePath().toString());
            // weirdly enough, this call is required, Profile#setProperty alone does not work
            // can alternatively call with a hardcoded map here
//            registry.addProfile(profileId, existingProfile.getProperties());
            // TODO this does not quite work yet -- p2 will not be able to read what is already installed
            return existingProfile;
        }

        // see
        // - DirectorApplication#initializeProfile
        // - P2TargetUtils#upsertProfile

        // default (i.e. agent/registry for currently running
        //   osgi instance) would be out/partial-runtime/config/.p2/org.eclipse.equinox.p2.engine/profileRegistry

        // might also be possible to set profile properties at runtime as in
        // https://github.com/deepin-community/equinox-p2/blob/f26e6852e36bdfa6d3689a9462c82701d208b952/bundles/org.eclipse.equinox.p2.tests/src/org/eclipse/equinox/p2/tests/reconciler/dropins/ProfileSynchronizerTest2.java#L52

        Map<String, String> props = Map.of(
                // needed to e.g. resolve ${installFolder} variables in p2.inf in modules
                IProfile.PROP_INSTALL_FOLDER, installFolder.toAbsolutePath().toString(),
                // "org.eclipse.equinox.p2.cache" -> "/home/ben/eclipse-workspace/.metadata/.plugins/org.eclipse.pde.core/.bundle_pool"
                IProfile.PROP_CACHE, bundlePool.toAbsolutePath().toString(),
                IProfile.PROP_INSTALL_FEATURES, Boolean.TRUE.toString(),
                IProfile.PROP_ENVIRONMENTS, "osgi.ws=%s,osgi.os=%s,osgi.arch=%s".formatted(
                        System.getProperty("osgi.ws"), System.getProperty("osgi.os"), System.getProperty("osgi.arch")),
                // TODO see P2TargetUtils#generateNLProperty
                IProfile.PROP_NL, "en_GB",
                // The following are (protected) properties from P2TargetUtils
                // "org.eclipse.pde.core.sequence" -> "1512743964"
                "org.eclipse.pde.core.sequence", Integer.toString(1),
                // TODO see P2TargetUtils#getProvisionMode(ITargetDefinition)
                "org.eclipse.pde.core.provision_mode", "planner",
                "org.eclipse.pde.core.all_environments", "false",
                // getIncludeSource
                "org.eclipse.pde.core.autoIncludeSource", "true",
                // getIncludeConfigurePhase
                "org.eclipse.pde.core.includeConfigure", "false"

        );
        var createdProfile = registry.addProfile(profileId, props);
        System.out.printf("%s: Created%n", profileId);
        return createdProfile;
    }

    public static void deleteProfileIfExists(String profileId, IProvisioningAgent agent, Path p2Path) {
        var registry = agent.getService(IProfileRegistry.class);
        var existingProfile = registry.getProfile(profileId);
        if (existingProfile != null) {
            Logger.info("%s: Removing existing profile before recreation%n", profileId);
            registry.removeProfile(profileId);
        } else {
            Logger.debug("%s: No existing profile to remove%n", profileId);
        }

        var profileFile = p2Path.toAbsolutePath()
                .resolve("org.eclipse.equinox.p2.engine")
                .resolve("profileRegistry")
                .resolve(profileId + ".profile");
        try {
            if (Files.exists(profileFile)) {
                Logger.warn("%s: Profile file %s still exists on disk, deleting%n", profileId, profileFile);
                Files.delete(profileFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete profile file " + profileFile, e);
        }

    }

    private static void updatePropIfChanged(final IProfile profile, final String key, final String newValue) {
        var previousValue = profile.getProperty(key);
        if (!previousValue.equals(newValue)) {
            if (!(profile instanceof Profile)) {
                System.out.println("Can not modify profile properties");
            }
            System.out.printf("%s: Updating profile property %s%n", profile.getProfileId(), key);
            System.out.printf("\t Previous: \t %s%n", previousValue);
            System.out.printf("\t New: \t \t %s%n", newValue);
            ((Profile)profile).setProperty(key, newValue);
        }
    }

    public static class ProvisioningJobProgressMonitor extends NullProgressMonitor implements AutoCloseable {

        private boolean done;

        // Insertion-ordered + synchronized: parallel download threads call subTask(), and the
        // previous ConcurrentHashMap had an unstable iteration order, so rendered lines jumped around.
        private final Map<ProvisioningJobUpdateLineParser.BundleId, ProvisioningJobUpdateLineParser.ProvisioningJobUpdate> active =
                Collections.synchronizedMap(new LinkedHashMap<>());

        // Lines drawn in the previous frame, so we can redraw just that block in place instead of
        // wiping the whole terminal on every update (the old full-screen clear caused the flicker).
        private int lastLineCount = 0;

        private static final Pattern SIZE = Pattern.compile("([0-9.]+) *([kmg]?)b?", Pattern.CASE_INSENSITIVE);

        @Override
        public void subTask(String name) {
            var update = ProvisioningJobUpdateLineParser.parseUpdateLine(name);
            if (update.bundleId() == null || update.downloaded() == null) {
                return;
            }
            synchronized (active) {
                if (isComplete(update)) {
                    active.remove(update.bundleId()); // drop finished downloads so the list shrinks
                } else {
                    active.put(update.bundleId(), update);
                }
                render();
            }
        }

        private static boolean isComplete(ProvisioningJobUpdateLineParser.ProvisioningJobUpdate update) {
            if (update.total() == null) {
                return false;
            }
            if (update.downloaded().equals(update.total())) {
                return true;
            }
            // p2 may report the final tick with mixed units (e.g. "10.23MB of 10.23MB" vs
            // "1,022.56kB of 1.0MB"); compare normalized byte counts so completion is detected.
            long downloaded = parseBytes(update.downloaded());
            long total = parseBytes(update.total());
            return downloaded >= 0 && total > 0 && downloaded >= total;
        }

        private static long parseBytes(String text) {
            if (text == null) {
                return -1;
            }
            var matcher = SIZE.matcher(text.replace(",", "").trim());
            if (!matcher.matches()) {
                return -1;
            }
            double value;
            try {
                value = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
            switch (matcher.group(2).toLowerCase()) {
                case "k": value *= 1_000; break;
                case "m": value *= 1_000_000; break;
                case "g": value *= 1_000_000_000L; break;
                default: break;
            }
            return (long) value;
        }

        public static void clearScreenAnsi() {
            System.out.print("\u001b[2J\u001b[H"); // or "\033[2J\033[H"
            System.out.flush();
        }


        // Redraw the live block in place. Caller must hold the `active` lock.
        private void render() {
            var sb = new StringBuilder();
            if (lastLineCount > 0) {
                sb.append((char) 0x1b).append('[').append(lastLineCount).append('F'); // cursor up N lines, to column 0
            }
            sb.append((char) 0x1b).append("[J"); // erase from cursor to end of screen (clears lines freed by removals)
            int n = 0;
            for (var update : active.values()) {
                sb.append(update.bundleId()).append('\t')
                  .append(update.downloaded()).append(" of ").append(update.total())
                  .append(" at ").append(update.speed()).append('\n');
                n++;
            }
            lastLineCount = n;
            System.out.print(sb);
            System.out.flush();
        }

        @Override
        public void done() {
            this.done = true;
            synchronized (active) {
                active.clear();
                render(); // erase the live block before the final message
                lastLineCount = 0;
            }
            System.out.print(green("done") + "\n");
            System.out.flush();
        }

        @Override
        public void close() throws Exception {
            if (!done) {
                done();
            }
        }

    }

    public static class StdOutProgressMonitor extends NullProgressMonitor implements AutoCloseable {
        private final String label;
        private boolean done;

        @Override
        public void subTask(String name) {
            // different fetches / downloads run in parallel, this would switch
            System.out.print("\r");
            printLabel();
            System.out.print(name);
        }

        public StdOutProgressMonitor(String label) {
            this.label = label;
        }

        private int totalWork;

        @Override
        public void beginTask(String name, int totalWork) {
            this.totalWork = totalWork;
            printLabel();
            System.out.print("starting");
        }

        private void printLabel() {
            System.out.printf("%s: ", label);
//            if (!name.trim().isEmpty()) {
//                System.out.printf(" " + name);
//            }
        }

        @Override
        public void worked(int work) {
            System.out.print("\r");
            printLabel();
            System.out.print(work + " / " + totalWork );
        }

        @Override
        public void done() {
            this.done = true;
            System.out.print("\r");
            printLabel();
            System.out.print(green("done") + "\n");
        }

        @Override
        public void close() throws Exception {
            if (!done) {
                done();
            }
        }
    }

    public record P2Context(
            Path p2Path,
            Path installFolder,
            Path cache
    ) {
        public static P2Context of(Path basePath) {
            var baseDir = Utils.createDirIfNotExist(basePath.resolve(("p2-nodes-workspace")));
            return new P2Context(
                    Utils.createDirIfNotExist(baseDir.resolve(("profiles"))),
                    Utils.createDirIfNotExist(baseDir.resolve(("install-folder"))),
                    Utils.createDirIfNotExist(baseDir.resolve(("cache")))
            );
        }
        public IProvisioningAgent agent() {
            try {
                return Activator.getProvisioningAgent(p2Path().toUri());
            } catch (ProvisionException e) {
                throw new RuntimeException(e);
            }
        }
    }


}
