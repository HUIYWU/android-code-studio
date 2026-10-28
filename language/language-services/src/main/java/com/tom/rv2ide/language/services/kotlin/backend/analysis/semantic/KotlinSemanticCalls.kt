@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
)

package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.resolveToCallCandidates
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.psi.KtCallElement

internal fun KaSession.resolveSignatureFunctionCalls(callElement: KtCallElement): List<KaFunctionCall<*>> =
    callElement.resolveToCallCandidates()
        .mapNotNull { candidate -> candidate.candidate as? KaFunctionCall<*> }
