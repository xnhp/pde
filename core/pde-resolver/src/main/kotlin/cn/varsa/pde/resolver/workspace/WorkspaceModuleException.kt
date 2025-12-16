package cn.varsa.pde.resolver.workspace

/**
 * Signals that a configured workspace module cannot be loaded (e.g., missing directory or manifest).
 */
class WorkspaceModuleException(message: String) : RuntimeException(message)
