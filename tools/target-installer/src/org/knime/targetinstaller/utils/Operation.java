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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.equinox.internal.provisional.p2.director.PlannerStatus;
import org.eclipse.equinox.p2.engine.PhaseSetFactory;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.operations.InstallOperation;
import org.eclipse.equinox.p2.operations.ProfileChangeOperation;
import org.eclipse.equinox.p2.operations.ProfileModificationJob;
import org.eclipse.equinox.p2.operations.UpdateOperation;
import org.eclipse.equinox.p2.planner.IProfileChangeRequest;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.knime.targetinstaller.utils.ConsoleColors.blue;
import static org.knime.targetinstaller.utils.ConsoleColors.green;
import static org.knime.targetinstaller.utils.ConsoleColors.red;
import static org.knime.targetinstaller.utils.Utils.ProvisioningJobProgressMonitor.clearScreenAnsi;


public class Operation {
    private final ProfileChangeOperation operation;
    private final String profile;
    private final boolean doDryRun;
    private final boolean includeConfigurePhase;

    public Operation(String profile, ProfileChangeOperation operation, boolean doDryRun, boolean includeConfigurePhase) {
        this.operation = operation;
        this.profile = profile;
        this.doDryRun = doDryRun;
        this.includeConfigurePhase = includeConfigurePhase;
        operation.setProfileId(this.profile);
    }

    private static void printStatus(IStatus status) {
        printStatus(status, "");
    }

    private static void printStatus(IStatus status, String prefix) {
        if (status instanceof MultiStatus) {
            System.out.println(prefix + status.getMessage());
            if (status.getChildren().length != 0) {
                System.out.println(prefix + "Children:");
                Arrays.stream(status.getChildren()).forEach(childStatus -> printStatus(childStatus, prefix + "\t"));
            }
            return;
        }
        if (status instanceof PlannerStatus plannerStatus) {
            System.out.println(prefix + plannerStatus.getMessage());
            var requestStatus = plannerStatus.getRequestStatus();
            System.out.println(prefix + requestStatus.getExplanationDetails());
            if (!requestStatus.getExplanations().isEmpty()) {
                System.out.println(prefix + "Further explanations: ");
                requestStatus.getExplanations().forEach(x1 -> System.out.println(prefix + "- " +  x1));
            }
            if (!requestStatus.getConflictsWithInstalledRoots().isEmpty()) {
                System.out.println(prefix + "Conflicts with installed roots: ");
                requestStatus.getConflictsWithInstalledRoots().forEach(x -> System.out.println(prefix + "- " + x));
            }
            if (!requestStatus.getConflictsWithAnyRoots().isEmpty()) {
                System.out.println(prefix + "Conflicts with any roots: ");
                requestStatus.getConflictsWithAnyRoots().forEach(x -> System.out.println(prefix + "- " + x));
            }
            if (plannerStatus.getChildren().length != 0) {
                System.out.println(prefix + "Children:");
                Arrays.stream(plannerStatus.getChildren()).forEach(childStatus -> printStatus(childStatus, prefix + "\t"));
            }
            return;
        }
        if (status.isOK()) {
            System.out.println(prefix + "Resolution OK");
            return;
        }
        System.out.println(prefix + "Resolution Status: " + status.getMessage());
    }

    public Optional<Operation> resolve() throws Exception {
        // ProfileChangeRequest installOp#request is non-null with useful info after resolve
        IStatus resolutionStatus;
        try (var monitor = new Utils.StdOutProgressMonitor("Resolving profile change operation operation")) {
            resolutionStatus = operation.resolveModal(monitor);
        }

        var profileChangeRequest = operation.getProfileChangeRequest();
        if (operation instanceof UpdateOperation && profileChangeRequest == null) {
            System.out.println(green("Everything up to date. ") + resolutionStatus.getMessage());
            return Optional.of(this);
        }
        if (
                operation instanceof InstallOperation
                        && profileChangeRequest.getAdditions().isEmpty()
                        && profileChangeRequest.getRemovals().isEmpty()
                        && (profileChangeRequest.getExtraRequirements() == null || profileChangeRequest.getExtraRequirements().isEmpty())
        ) {
            System.out.println(green("Everything up to date. ") + "Nothing to do.");
            return Optional.of(this);
        }

        printProfileChangeRequest(profile, profileChangeRequest);

        if (resolutionStatus.getSeverity() == IStatus.CANCEL || resolutionStatus.getSeverity() == IStatus.ERROR) {
            printStatus(resolutionStatus);
            return Optional.empty();
        }
        return Optional.of(this);
    }

    public void execute() {
        if (doDryRun) {
            System.out.println("Dry run, exiting.");
            return;
        }
        org.eclipse.equinox.p2.operations.ProvisioningJob job;
        try (var monitor = new Utils.StdOutProgressMonitor("Getting provisioning plan")) {
            job = operation.getProvisioningJob(monitor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (job == null) {
            System.out.println("No job, nothing to do");
            return;
        }

        if (!includeConfigurePhase && job instanceof ProfileModificationJob profileJob) {
            // PDE's P2TargetUtils#createPhaseSet() only adds Configure when includeConfigurePhase is true
            // (see eclipse.pde/ui/org.eclipse.pde.core/.../P2TargetUtils.java). We mimic that here by stripping
            // configure/unconfigure from the default phase set the operations API would otherwise use.
            profileJob.setPhaseSet(PhaseSetFactory.createDefaultPhaseSetExcluding(
                    new String[]{PhaseSetFactory.PHASE_CONFIGURE, PhaseSetFactory.PHASE_UNCONFIGURE}));
        }

        try (var monitor = new Utils.ProvisioningJobProgressMonitor()) {
            // TODO print sub-progress
            IStatus provisionStatus = job.runModal(monitor);
            clearScreenAnsi();
            var severity = provisionStatus.getSeverity();
            if (severity == IStatus.ERROR || severity == IStatus.CANCEL) {
                System.err.println("Provisioning failed: " + provisionStatus.getMessage());
                printStatus(provisionStatus);
                return;
            }
            if (severity == IStatus.WARNING) {
                // PDE’s P2TargetUtils simply logs non-OK statuses unless they are error/cancel (see resolveWithPlanner),
                // because touchpoint warnings (e.g. markStarted) are expected during target provisioning.
                System.out.println("Provisioning completed with warnings: " + provisionStatus.getMessage());
                printStatus(provisionStatus);
            } else {
                System.out.println("Successfully executed operation on : " + this.profile);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void printProfileChangeRequest(String profileId, IProfileChangeRequest profileChangeRequest) {
        // TODO this does not print all bundles that will be modified
        //  (one can see that other bundles are fetched, too -- probably does not include dependencies)
        System.out.println("Profile change request for %s (~ changed, + added, - removed)".formatted(profileId));
        var addedIds = profileChangeRequest.getAdditions().stream().map(iu -> iu.getId()).collect(Collectors.toSet());
        var removedIds = profileChangeRequest.getRemovals().stream().map(iu -> iu.getId()).collect(Collectors.toSet());
        var deltas = profileChangeRequest.getAdditions().stream()
                .map(added -> {
                    var matchingRemoved = profileChangeRequest.getRemovals().stream()
                            .filter(removedIU -> added.getId().equals(removedIU.getId()))
                            .findFirst();
                    if (matchingRemoved.isPresent()) {
                        return Optional.of(new Delta(added, matchingRemoved.get()));
                    } else {
                        return Optional.empty();
                    }
                })
                .flatMap(Optional::stream)
                .map(e -> (Delta)e)
                .toList();
        var onlyAdditions = profileChangeRequest.getAdditions().stream()
                .filter(addition -> !removedIds.contains(addition.getId()))
                .toList();
        var onlyRemovals = profileChangeRequest.getRemovals().stream()
                .filter(removal -> !addedIds.contains(removal.getId()))
                .toList();
        deltas.forEach(delta -> {
            System.out.println(blue("~ ") + delta.added().getId());
            System.out.println("     " + delta.removed().getVersion());
            System.out.println("  -> " + delta.added().getVersion());
        });
        onlyAdditions.forEach(addition -> {
            System.out.println(green("+ ") + addition.getId());
            System.out.println("     " + addition.getVersion());
        });
        onlyRemovals.forEach(removal -> {
            System.out.println(red("- ") + removal.getId());
            System.out.println("     " + removal.getVersion());
        });
    }

    record Delta(IInstallableUnit added, IInstallableUnit removed) {

    }
}
