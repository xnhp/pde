package cn.varsa.pde.resolver.launch

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor

data class WorkspaceInputs(
  val descriptors: List<WorkspaceBundleDescriptor>,
  val devProperties: Map<String, List<String>>
)
