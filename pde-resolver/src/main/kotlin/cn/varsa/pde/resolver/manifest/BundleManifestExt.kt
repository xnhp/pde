package cn.varsa.pde.resolver.manifest

import cn.varsa.pde.resolver.support.parseVersion
import cn.varsa.pde.resolver.support.parseVersionRange
import cn.varsa.pde.resolver.support.VersionRangeAny
import org.osgi.framework.Version
import org.osgi.framework.VersionRange
import org.osgi.framework.Constants.BUNDLE_VERSION_ATTRIBUTE
import org.osgi.framework.Constants.VERSION_ATTRIBUTE

import cn.varsa.pde.resolver.support.contains

fun BundleManifest.fragmentHostAndVersionRange(): Pair<String, VersionRange>? =
  fragmentHost?.run { key to (value.attribute[BUNDLE_VERSION_ATTRIBUTE].parseVersionRange()) }

val BundleManifest.canonicalName: String
  get() = "${bundleSymbolicName?.key}-${bundleVersion}"

fun BundleManifest.isFragmentHost(
  fragmentHostBSN: String,
  fragmentHostVersion: VersionRange = VersionRangeAny
): Boolean =
  bundleSymbolicName?.key == fragmentHostBSN && bundleVersion in fragmentHostVersion &&
    fragmentHost?.key.isNullOrBlank()

fun BundleManifest.getExportedPackageName(packageName: String): String? =
  exportPackage?.keys?.map { it.substringBefore(".*") }?.firstOrNull { packageName == it }

fun BundleManifest.isPackageImported(
  packageName: String,
  version: Set<Version> = emptySet()
): Boolean =
  importPackage?.filterKeys { packageName == it }?.let { map ->
    if (version.isEmpty()) map.isNotEmpty()
    else map.values.map { it.attribute[VERSION_ATTRIBUTE].parseVersionRange() }
      .any { range -> version.any { it in range } }
  } == true

fun BundleManifest.isBundleRequired(
  symbolicName: String,
  version: Set<Version> = emptySet()
): Boolean =
  requireBundle?.filterKeys { symbolicName == it }?.let { map ->
    if (version.isEmpty()) map.isNotEmpty()
    else map.values.map { it.attribute[BUNDLE_VERSION_ATTRIBUTE].parseVersionRange() }
      .any { range -> version.any { it in range } }
  } == true

fun BundleManifest.requiredBundleAndVersion(): Map<String, VersionRange> =
  requireBundle?.mapValues { (_, attrs) -> attrs.attribute[BUNDLE_VERSION_ATTRIBUTE].parseVersionRange() }
    ?: emptyMap()

fun BundleManifest.reexportRequiredBundleAndVersion(): Map<String, VersionRange> =
  requireBundle?.filter { it.value.directive["visibility"] == "reexport" }
    ?.mapValues { (_, attrs) -> attrs.attribute[BUNDLE_VERSION_ATTRIBUTE].parseVersionRange() } ?: emptyMap()

fun BundleManifest.importedPackageAndVersion(): Map<String, VersionRange> =
  importPackage?.mapValues { (_, attrs) -> attrs.attribute[VERSION_ATTRIBUTE].parseVersionRange() } ?: emptyMap()

fun BundleManifest.exportedPackageAndVersion(): Map<String, Version> =
  exportPackage?.mapKeys { it.key.substringBefore(".*") }
    ?.mapValues { (_, attrs) -> attrs.attribute[VERSION_ATTRIBUTE].parseVersion() } ?: emptyMap()
