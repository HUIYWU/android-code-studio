package com.tom.rv2ide.language.services.kotlin.backend.analysis.input

internal enum class KotlinProjectRefreshAction {
  NO_ACTION,
  RUNTIME_REPLACEMENT,
}

internal data class KotlinProjectRefreshPlan(
    val action: KotlinProjectRefreshAction,
    val change: KotlinProjectChange,
)

internal object KotlinProjectRefreshPlanner {
  fun plan(change: KotlinProjectChange): KotlinProjectRefreshPlan {
    val action = if (change.kind == KotlinProjectChangeKind.NONE) {
      KotlinProjectRefreshAction.NO_ACTION
    } else {
      KotlinProjectRefreshAction.RUNTIME_REPLACEMENT
    }
    return KotlinProjectRefreshPlan(action, change)
  }
}