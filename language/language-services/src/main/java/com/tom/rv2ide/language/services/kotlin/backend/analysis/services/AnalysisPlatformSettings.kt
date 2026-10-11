package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import org.jetbrains.kotlin.analysis.api.platform.KotlinDeserializedDeclarationsOrigin
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings

internal class AnalysisPlatformSettings : KotlinPlatformSettings {
  override val deserializedDeclarationsOrigin: KotlinDeserializedDeclarationsOrigin
    get() = KotlinDeserializedDeclarationsOrigin.BINARIES

  override val allowUseSiteLibraryModuleAnalysis: Boolean
    get() = false
}