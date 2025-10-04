package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.idea.pde.partial.common.support.*
import cn.varsa.idea.pde.partial.plugin.cache.*
import cn.varsa.idea.pde.partial.plugin.config.*
import cn.varsa.idea.pde.partial.plugin.domain.*
import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.i18n.*
import cn.varsa.idea.pde.partial.plugin.openapi.resolver.*
import cn.varsa.idea.pde.partial.plugin.support.*
import cn.varsa.pde.resolver.manifest.*
import cn.varsa.pde.resolver.support.contains
import com.intellij.openapi.module.*
import com.intellij.openapi.roots.*

class PdeModuleFragmentLibraryResolver : ManifestLibraryResolver {
  override val displayName: String = EclipsePDEPartialBundles.message("resolver.pde.moduleFragment")

  override fun resolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val cacheService = BundleManifestCacheService.getInstance(area.project)
    val hostAndRange = cacheService.getManifest(area)?.fragmentHostAndVersionRange()

    area.updateModel { model ->
      area.project.allPDEModules(area).filter {
        cacheService.getManifest(it)?.run {
          bundleSymbolicName?.key == hostAndRange?.first && bundleVersion in hostAndRange?.second
        } == true
      }.forEach { model.findModuleOrderEntry(it) ?: model.addModuleOrderEntry(it) }
    }
  }

  override fun postResolve(area: Module) {
    PDEFacet.getInstance(area) ?: return

    val project = area.project
    val cacheService = BundleManifestCacheService.getInstance(project)
    val tpService = PluginTargetIndexService.getInstance(project)

    val manifest = cacheService.getManifest(area) ?: return

    // Build workspace descriptors and module lookup by BSN
    val allPdeModules = project.allPDEModules(area)
    allPdeModules.mapNotNull { m ->
      val man = cacheService.getManifest(m)
      val bsn = man?.bundleSymbolicName?.key
      if (bsn != null) bsn to m else null
    }.toMap()

    // Determine fragment host manifest (workspace preferred)
    val hostManifestAndPath = manifest.fragmentHostAndVersionRange()?.let { (hostBsn, hostRange) ->
      val hostModule = allPdeModules.find { m ->
        val man = cacheService.getManifest(m)
        val bsn = man?.bundleSymbolicName?.key
        val ver = man?.bundleVersion
        bsn == hostBsn && (ver in hostRange)
      }
      if (hostModule != null) {
        val man = cacheService.getManifest(hostModule)!!
        val path = hostModule.moduleRootManager.contentRoots.firstOrNull()?.path
        Triple(hostBsn, man, path?.let { java.nio.file.Paths.get(it) })
      } else {
        val hostBundle = tpService.getBundlesByBSN(hostBsn, hostRange)
        val man = hostBundle?.manifest
        val path = hostBundle?.location
        if (man != null && path != null) Triple(hostBsn, man, path) else null
      }
    }

    // Prepare resolver inputs (workspace + target index)
    val workspace = allPdeModules.mapNotNull { m ->
      val man = cacheService.getManifest(m) ?: return@mapNotNull null
      val path = m.moduleRootManager.contentRoots.firstOrNull()?.path?.let { java.nio.file.Paths.get(it) }
        ?: return@mapNotNull null
      cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor(path, man)
    }
    val roots = TargetDefinitionService.getInstance(project).locations
      .mapNotNull { it.location.takeIf(String::isNotBlank) }
      .map { java.nio.file.Paths.get(it) }
    val targetIndex = cn.varsa.pde.resolver.index.TargetPlatformCache.buildWithCache(roots, null)

    val options = cn.varsa.pde.resolver.algo.ResolveOptions(
      whitelistPrefixes = PreferenceService.getInstance(project).libraryWhitelist,
      preferWorkspace = true,
      includeHostsForFragments = false
    )

    val hostAndName = hostManifestAndPath?.let { (_, hostMan, _) ->
      hostMan to hostMan.canonicalName
    }

    area.updateModel { model ->
      val orderEntries = model.orderEntries.toMutableList()
      val orderEntriesMap = orderEntries.associateBy { it.presentableName }

      // Use core resolver to compute host dependencies order (libraries only)
      val dependencyOrder = hostManifestAndPath?.let { (_, hostMan, hostPath) ->
        val entry = cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor(hostPath!!, hostMan)
        val result = cn.varsa.pde.resolver.algo.Resolver.resolve(targetIndex, workspace, entry, options)
        val names = result.bundles.filter { !it.isWorkspace }.map { rb ->
          val dash = "${rb.bsn}-${rb.version}"
          val at = "$ProjectLibraryNamePrefix${rb.bsn}${BundleDefinition.canonicalNameSeparator}${rb.version}"
          listOf(dash, "$ProjectLibraryNamePrefix$dash", at)
        }.flatten()
        names.mapNotNull { n -> orderEntriesMap[n] }
      }
      // Build fragment->host mapping for present library order entries
      val libEntries = orderEntries.filterIsInstance<LibraryOrderEntry>()
        .mapNotNull { it.library?.name?.let { n -> it to n } }
        .filter { it.second.startsWith(ProjectLibraryNamePrefix) }

      data class LibKey(val entry: LibraryOrderEntry, val bsn: String, val ver: org.osgi.framework.Version)
      fun parseLib(name: String): Pair<String, org.osgi.framework.Version>? {
        val tail = name.substringAfterLast(ProjectLibraryNamePrefix)
        val bsn = tail.substringBeforeLast(BundleDefinition.canonicalNameSeparator)
        val verText = tail.substringAfterLast(BundleDefinition.canonicalNameSeparator)
        val ver = try { org.osgi.framework.Version.parseVersion(verText) } catch (_: Exception) { return null }
        return bsn to ver
      }
      val presentLibs: List<LibKey> = libEntries.mapNotNull { (entry, name) ->
        val (bsn, ver) = parseLib(name) ?: return@mapNotNull null
        LibKey(entry, bsn, ver)
      }
      val libsByBsn: Map<String, java.util.NavigableMap<org.osgi.framework.Version, LibraryOrderEntry>> =
        presentLibs.groupBy { it.bsn }.mapValues { (_, list) ->
          val nav = java.util.TreeMap<org.osgi.framework.Version, LibraryOrderEntry>()
          list.forEach { nav[it.ver] = it.entry }
          nav
        }

      val fragment2HostOrder: Map<OrderEntry, OrderEntry> = presentLibs.mapNotNull { key ->
        val bcn = key.bsn + BundleDefinition.canonicalNameSeparator + key.ver
        val bundle = tpService.getBundleByBCN(bcn)
        val hostPair = bundle?.manifest?.fragmentHostAndVersionRange() ?: return@mapNotNull null
        val hostNav = libsByBsn[hostPair.first] ?: return@mapNotNull null
        val hostEntry = hostNav.descendingMap().entries.firstOrNull { hostPair.second.includes(it.key) }?.value
          ?: hostNav.lastEntry()?.value
        if (hostEntry != null) key.entry to hostEntry else null
      }.toMap()

      val libraryIndex = orderEntries.indexOfLast {
        it is JdkOrderEntry || it is ModuleSourceOrderEntry || it.presentableName.startsWith(
          KotlinOrderEntryName
        ) || it.presentableName.equalAny(
          ModuleLibraryName, "$ProjectLibraryNamePrefix${hostAndName?.second}"
        ) || it.presentableName == hostAndName?.second
      } + 1
      val arrangeOrderEntries = orderEntries.apply {
        dependencyOrder?.also {
          removeAll(it)
          addAll(libraryIndex, it)
        }

        fragment2HostOrder.forEach { (fragment, host) ->
          remove(fragment)
          add(indexOf(host), fragment)
        }
      }.toTypedArray()
      model.rearrangeOrderEntries(arrangeOrderEntries)
    }
  }
}
