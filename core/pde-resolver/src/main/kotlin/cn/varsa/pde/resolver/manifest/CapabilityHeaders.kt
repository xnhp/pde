package cn.varsa.pde.resolver.manifest

import org.osgi.framework.Constants.PROVIDE_CAPABILITY
import org.osgi.framework.Constants.REQUIRE_CAPABILITY
import org.osgi.framework.Constants.RESOLUTION_DIRECTIVE
import org.osgi.framework.Constants.RESOLUTION_OPTIONAL
import org.osgi.framework.Version

/**
 * One clause from a Require-Capability/Provide-Capability header. Unlike [Parameters], which
 * keeps at most one [Attrs] per clause name, capability headers routinely repeat the same
 * namespace across multiple clauses (e.g. a bundle providing two different `osgi.extender`
 * capabilities, as `org.apache.aries.spifly.dynamic.bundle` does for
 * `osgi.serviceloader.registrar` and `osgi.serviceloader.processor`), so clauses are kept as a
 * list rather than deduplicated by namespace.
 *
 * Attribute values are typed per the header's `:Type` suffix (`version:Version="1.0"` -> an
 * actual [Version], not the string `"1.0"`) so that [org.osgi.framework.Filter] version
 * comparisons (`version>=1.0.0`) compare numerically rather than lexicographically.
 */
data class CapabilityClause(
  val namespace: String,
  val attribute: Map<String, Any>,
  val directive: Map<String, String>
)

private fun typedAttributeValue(type: String, rawValue: String): Any = when (type) {
  "Version" -> Version.parseVersion(rawValue)
  "Long" -> rawValue.toLong()
  "Double" -> rawValue.toDouble()
  else -> rawValue
}

private fun parseCapabilityClauses(header: String?): List<CapabilityClause> {
  if (header.isNullOrBlank()) return emptyList()
  val qt = QuotedTokenizer(header, ";=,")
  val clauses = mutableListOf<CapabilityClause>()
  var del: Char
  do {
    val namespace = qt.nextToken(",;")?.trim()
    del = qt.separator
    if (namespace == null) break
    if (namespace.isBlank()) continue

    val attribute = mutableMapOf<String, Any>()
    val directive = mutableMapOf<String, String>()
    while (del == ';') {
      // nextToken() returns null without advancing `separator` once the input is exhausted, so an
      // unterminated trailing clause (header ends right after ';') would otherwise leave `del`
      // stuck at ';' forever -- break explicitly instead of relying on `del` changing on its own.
      val adName = qt.nextToken()?.trim() ?: break
      if (qt.separator.also { del = it } != '=') continue
      val adValue = (qt.nextToken() ?: "").trim('"')
      del = qt.separator
      if (adName.isNullOrBlank()) continue
      if (adName.endsWith(':')) {
        directive[adName.dropLast(1)] = adValue
      } else {
        val (name, type) = adName.split(':', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
        attribute[name] = typedAttributeValue(type, adValue)
      }
    }
    clauses += CapabilityClause(namespace, attribute, directive)
  } while (del == ',')
  return clauses
}

/** Mandatory (non-`resolution:=optional`) Require-Capability clauses. */
fun BundleManifest.requireCapabilityClauses(): List<CapabilityClause> =
  parseCapabilityClauses(get(REQUIRE_CAPABILITY)).filterNot { it.directive[RESOLUTION_DIRECTIVE] == RESOLUTION_OPTIONAL }

fun BundleManifest.provideCapabilityClauses(): List<CapabilityClause> =
  parseCapabilityClauses(get(PROVIDE_CAPABILITY))
