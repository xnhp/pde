package cn.varsa.pde.resolver.index

import cn.varsa.pde.resolver.manifest.BundleManifest
import java.nio.file.Path

data class ResolvedBundle(
  val location: Path,
  val manifest: BundleManifest,
  val isDirectory: Boolean
)

