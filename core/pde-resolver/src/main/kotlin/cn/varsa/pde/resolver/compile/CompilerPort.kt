package cn.varsa.pde.resolver.compile

/**
 * Abstraction for compiling a single bundle.
 */
interface CompilerPort {
  fun compile(spec: CompileSpec): BundleCompileResult
}
