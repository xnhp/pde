package cn.varsa.pde.resolver.api

/**
 * Thrown when the analyzer detects that a bundle's analysis ran in a degraded mode (missing or
 * incomplete API description, unresolvable dependencies, or a result whose shape indicates the
 * current API description was not attached). The harness treats it as a hard failure: no report is
 * written and the process exits non-zero.
 */
class DegradedApiAnalysisException(message: String) : RuntimeException(message)

/**
 * Pure (Equinox-free) checks the harness runs around a single component analysis. Kept separate
 * from [DirectApiAnalyzerHarness] so they can be unit-tested without an OSGi runtime.
 */
object ApiAnalysisSanity {
  /** Minimum "type is no longer an API" problems before the result-shape heuristic can fire. */
  const val MIN_NO_LONGER_API_TYPES = 10

  /** Minimum number of filters in .api_filters before the unused-filter ratio is meaningful. */
  const val MIN_FILTER_RULES = 5

  /** Fraction of existing filters that must be reported unused for the heuristic to fire. */
  const val UNUSED_FILTER_RATIO = 0.5

  /**
   * Exported (non x-internal / non x-friends) packages that the current component actually has
   * types for, but whose API description resolves to "not API" (or to nothing at all).
   *
   * A package that is exported but has no types in the component (stale Export-Package) is not
   * reported: PDE API Tools has no API description node to build for it, and it cannot produce
   * the "no longer an API" problems this check guards against.
   */
  fun missingApiPackages(
    exportedApiPackages: Set<String>,
    packagesWithTypes: Set<String>,
    packagesResolvedAsApi: Set<String>
  ): Set<String> = (exportedApiPackages intersect packagesWithTypes) - packagesResolvedAsApi

  /**
   * Result-shape heuristic. A run in which a large fraction of the existing filters are reported
   * unused AND many types are reported "no longer an API" at the same time is the signature of an
   * analysis whose current API description was missing (every exported package treated as
   * private): both symptoms are produced by the same cause and neither is plausible in a real
   * API change of that size. Returns a failure message, or null when the result looks sane.
   */
  fun degradedResultMessage(
    noLongerApiTypeCount: Int,
    unusedFilterCount: Int,
    totalFilterCount: Int
  ): String? {
    if (noLongerApiTypeCount < MIN_NO_LONGER_API_TYPES) return null
    if (totalFilterCount < MIN_FILTER_RULES) return null
    val ratio = unusedFilterCount.toDouble() / totalFilterCount
    if (ratio < UNUSED_FILTER_RATIO) return null
    return "API analysis result looks degraded: $noLongerApiTypeCount type(s) reported as 'no longer an API' " +
      "and $unusedFilterCount of $totalFilterCount existing API problem filter(s) reported unused at once. " +
      "This is the signature of a run whose current API description was not built; not writing a report. " +
      "Pass --no-sanity-check to accept the result anyway."
  }
}
