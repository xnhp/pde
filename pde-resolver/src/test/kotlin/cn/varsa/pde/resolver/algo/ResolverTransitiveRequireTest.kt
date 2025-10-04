package cn.varsa.pde.resolver.algo

import cn.varsa.pde.resolver.index.ResolvedBundle
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.*
import org.junit.Test
import org.osgi.framework.Version
import java.nio.file.Paths
import java.util.*

class ResolverTransitiveRequireTest {
  @Test
  fun resolvesTransitiveRequireFromWrappedHeader() {
    // Workspace entry requiring org.knime.base (note space after comma)
    val wsManifest = BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.knime.base.treeensembles2",
        "Bundle-Version" to "5.6.0",
        "Require-Bundle" to "org.knime.core.pmml;bundle-version=\"[5.6.0,6.0.0)\", org.knime.base;bundle-version=\"[5.6.0,6.0.0)\""
      )
    )

    val workspace = listOf(WorkspaceBundleDescriptor(Paths.get("/ws/org.knime.base.treeensembles2"), wsManifest))

    // Target: org.knime.base requires org.knime.core (reexport)
    val baseManifest = BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.knime.base",
        "Bundle-Version" to "5.8.0",
        "Require-Bundle" to "org.knime.core;bundle-version=\"[5.8.0,6.0.0)\";visibility:=reexport"
      )
    )
    val coreManifest = BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.knime.core",
        "Bundle-Version" to "5.8.0",
        "Export-Package" to "org.knime.core"
      )
    )
    val pmmlManifest = BundleManifest.parse(
      mapOf(
        "Bundle-SymbolicName" to "org.knime.core.pmml",
        "Bundle-Version" to "5.6.0"
      )
    )

    fun navOf(vararg pairs: Pair<String, Pair<String, ResolvedBundle>>): Map<String, NavigableMap<Version, ResolvedBundle>> {
      val map = HashMap<String, NavigableMap<Version, ResolvedBundle>>()
      pairs.forEach { (bsn, pv) ->
        val (verText, rb) = pv
        map.computeIfAbsent(bsn) { TreeMap() }[Version.parseVersion(verText)] = rb
      }
      return map
    }

    val baseRb = ResolvedBundle(Paths.get("/tp/plugins/org.knime.base"), baseManifest, true)
    val coreRb = ResolvedBundle(Paths.get("/tp/plugins/org.knime.core"), coreManifest, true)
    val pmmlRb = ResolvedBundle(Paths.get("/tp/plugins/org.knime.core.pmml"), pmmlManifest, true)

    val tpIndex = TargetPlatformIndex(
      navOf(
        "org.knime.base" to ("5.8.0" to baseRb),
        "org.knime.core" to ("5.8.0" to coreRb),
        "org.knime.core.pmml" to ("5.6.0" to pmmlRb)
      )
    )

    val entry = workspace.first()
    val result = Resolver.resolve(tpIndex, workspace, entry, ResolveOptions())

    val bsns = result.bundles.map { it.bsn }.toSet()
    assertTrue("Requires org.knime.base present", bsns.contains("org.knime.base"))
    assertTrue("Transitive org.knime.core present", bsns.contains("org.knime.core"))
    assertTrue("Direct org.knime.core.pmml present", bsns.contains("org.knime.core.pmml"))
  }
}

