package cn.varsa.pde.resolver.compile

object CompilerLevels {
  fun levelFromExecutionEnvironment(ee: String): String? =
    ee.substringAfterLast('-', missingDelimiterValue = ee)
      .removePrefix("1.").let { part ->
        when {
          part.equals("J2SE", ignoreCase = true) -> null
          part.matches(Regex("\\d+(\\.\\d+)?")) -> ee.substringAfterLast('-').removePrefix("1.")
          ee.equals("J2SE-1.5", ignoreCase = true) -> "5"
          else -> null
        }
      }?.let { if (it.contains('.')) it else it }
}
