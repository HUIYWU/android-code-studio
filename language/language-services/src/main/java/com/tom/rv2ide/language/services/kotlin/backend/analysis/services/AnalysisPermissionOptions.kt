package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions

internal class AnalysisPermissionOptions : KotlinAnalysisPermissionOptions {
  override val defaultIsAnalysisAllowedOnEdt: Boolean = false
  override val defaultIsAnalysisAllowedInWriteAction: Boolean = true
}