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

import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.query.IQuery;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class Repository {

    public List<IInstallableUnit> queryIUs(
            List<Types.IURef> iuRefs
    ) throws Exception {

        List<IQuery<IInstallableUnit>> referenceQueries = iuRefs.stream().map(
                iuRef -> {
                    Version version = Optional.ofNullable(iuRef.version()).map(Version::create).orElse(null);
                    return QueryUtil.createIUQuery(iuRef.id(), version);
                }
        ).toList();
        // repository might provide more than one IU for a bundle / reference, for example
        /*
        <unit id="com.knime.enterprise.slave" version="0.0.0"/>
            com.knime.enterprise.slave 5.5.0.v202501221042
            com.knime.enterprise.slave 5.5.0.v202501151617
            com.knime.enterprise.slave 5.5.0.v202501101616
         */
        // any of these queries can also be executed directly e.g. for debugging -- these are just optimisations
        var compoundQuery = QueryUtil.createCompoundQuery(referenceQueries, false); // could also query repo manager individually in a loop below.
        var latestQuery = QueryUtil.createLatestQuery(compoundQuery);
//        var grouped = new HashMap<String, ArrayList<IInstallableUnit>>();
//        installableUnits.forEach(iu -> {
//            grouped.computeIfAbsent(iu.getId(), id -> new ArrayList<>());
//            grouped.get(iu.getId()).add(iu);
//        });
//        var latestIUs = grouped.values().stream()
//                .map(ius -> ius.stream()
//                        .sorted((iu1, iu2) -> iu1.compareTo(iu2) * -1)
//                        .findFirst())
//                .flatMap(Optional::stream)
//                .toList();

        IQueryResult<IInstallableUnit> queryResult;
        try (var monitor = new Utils.StdOutProgressMonitor("Querying loaded repositories for %s IUs".formatted(iuRefs.size()))) {
            queryResult = this.getMetadataRepoManager().query(latestQuery, monitor);
        }
        if (queryResult.isEmpty()) {
            System.err.println("No IUs found");
        }
        return queryResult.stream().toList();

        // the below allows to report when there was no IU found for a (single) entry -- but it is much slower
        //List<IInstallableUnit> resultIUs = new ArrayList<>();
//        for (TargetFileParser.IUReference ref : iuRefs) {
//            org.eclipse.equinox.p2.query.IQuery<IInstallableUnit> query;
//            if (ref.version() != null) {
//                // Query by ID and exact Version
//                query = QueryUtil.createIUQuery(ref.id(), Version.create(ref.version()));
//            } else {
//                // Query by ID (any version)
//                query = QueryUtil.createIUQuery(ref.id(), (Version) null);
//            }
//
//            // Query the combined loaded repositories
//            IQueryResult<IInstallableUnit> qResult = metadataRepoManager.query(query, new MyProgressMonitor("Querying metadata repositories"));
//            if (!qResult.isEmpty()) {
//                Set<IInstallableUnit> found = qResult.toUnmodifiableSet();
//                resultIUs.addAll(found);
//            } else {
//                System.err.println("No IU found for: " + ref);
//            }
//        }

//        return resultIUs;
    }

    public IMetadataRepositoryManager getMetadataRepoManager() {
        return metadataRepoManager;
    }

    private final IMetadataRepositoryManager metadataRepoManager;
    private final IArtifactRepositoryManager artifactRepoManager;

    /**
     */
    public Repository(IProvisioningAgent agent) {

        metadataRepoManager = agent.getService(IMetadataRepositoryManager.class);
        artifactRepoManager = agent.getService(IArtifactRepositoryManager.class);

    }

    public void load(Collection<URI> repositoryUris) {
        for (URI repo : repositoryUris) {
            try {
                try (var monitor = new Utils.StdOutProgressMonitor("Loading metadata repository at " + repo)) {
                    metadataRepoManager.loadRepository(repo, monitor);
                }
                try (var monitor = new Utils.StdOutProgressMonitor("Loading artifact repository at " + repo)) {
                    artifactRepoManager.loadRepository(repo, monitor);
                }
            } catch (Exception e) {
                System.err.println("Failed to load repository " + repo + ": " + e.getMessage());
            }
        }
    }
}
