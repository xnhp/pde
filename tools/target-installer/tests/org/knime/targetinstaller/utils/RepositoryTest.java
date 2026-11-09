package org.knime.targetinstaller.utils;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.MetadataFactory;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.query.IQuery;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.junit.Test;

public class RepositoryTest {
    @Test
    public void findsBothExactVersionsAndSelectsLatestForWildcards() throws Exception {
        var older = unit("example", "1.0.0");
        var newer = unit("example", "2.0.0");
        var refs = List.of(new Types.IURef("example", "1.0.0"), new Types.IURef("example", "2.0.0"),
                new Types.IURef("example", "0.0.0"), new Types.IURef("example", null));
        var repository = repository(List.of(older, newer));

        var found = repository.queryIUs(refs);
        assertEquals(List.of(), Repository.unmatchedRefs(refs, found));
        assertEquals(Set.of(older, newer), Set.copyOf(Repository.selectIUs(refs, found)));
        assertEquals(List.of(newer), Repository.selectIUs(refs.subList(2, 4), found));
    }

    @Test
    public void reportsMissingExactVersion() throws Exception {
        var missing = new Types.IURef("example", "1.0.0");
        var refs = List.of(missing, new Types.IURef("example", "2.0.0"));
        var found = repository(List.of(unit("example", "2.0.0"))).queryIUs(refs);
        assertEquals(List.of(missing), Repository.unmatchedRefs(refs, found));
    }

    private static IInstallableUnit unit(String id, String version) {
        var description = new MetadataFactory.InstallableUnitDescription();
        description.setId(id);
        description.setVersion(Version.create(version));
        return MetadataFactory.createInstallableUnit(description);
    }

    private static Repository repository(List<IInstallableUnit> units) {
        var manager = (IMetadataRepositoryManager) Proxy.newProxyInstance(RepositoryTest.class.getClassLoader(),
                new Class<?>[]{IMetadataRepositoryManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("query")) {
                        @SuppressWarnings("unchecked")
                        var query = (IQuery<IInstallableUnit>) args[0];
                        return query.perform(units.iterator());
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var agent = (IProvisioningAgent) Proxy.newProxyInstance(RepositoryTest.class.getClassLoader(),
                new Class<?>[]{IProvisioningAgent.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getService")) {
                        return args[0] == IMetadataRepositoryManager.class ? manager : null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new Repository(agent);
    }
}
