package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseResolutionScopeProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.com.intellij.openapi.project.Project

@OptIn(KaExperimentalApi::class)
internal class AnalysisResolutionScopeProvider : KaBaseResolutionScopeProvider() {
  companion object {
    fun getInstance(project: Project): AnalysisResolutionScopeProvider =
        KaResolutionScopeProvider.getInstance(project) as AnalysisResolutionScopeProvider
  }

  fun invalidate(module: KaModule) {
    resolutionScopeCache.invalidate(module)
  }

  fun invalidate(modules: Iterable<KaModule>, reason: String) {
    modules.distinct().forEach { module ->
      resolutionScopeCache.invalidate(module)
    }
  }

  fun invalidateAll() {
    resolutionScopeCache.invalidateAll()
  }
}