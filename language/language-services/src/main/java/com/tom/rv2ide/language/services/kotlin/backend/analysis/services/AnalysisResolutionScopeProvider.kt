package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseResolutionScopeProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.com.intellij.openapi.project.Project

internal class AnalysisResolutionScopeProvider : KaBaseResolutionScopeProvider() {
  companion object {
    fun getInstance(project: Project): AnalysisResolutionScopeProvider =
        KaResolutionScopeProvider.getInstance(project) as AnalysisResolutionScopeProvider
  }

  fun invalidate(module: KaModule) {
    resolutionScopeCache.invalidate(module)
  }

  fun invalidateAll() {
    resolutionScopeCache.invalidateAll()
  }
}