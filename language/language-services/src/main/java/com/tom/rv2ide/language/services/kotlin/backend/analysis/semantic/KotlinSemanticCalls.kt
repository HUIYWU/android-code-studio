@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
)

package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.components.resolveToCallCandidates
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.signatures.KaVariableSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.types.Variance

internal data class SignatureCallCandidate(
    val call: KaFunctionCall<*>,
    val isInBestCandidates: Boolean,
)

internal fun KaSession.resolveSignatureCallCandidates(callElement: KtCallElement): List<SignatureCallCandidate> =
    callElement.resolveToCallCandidates().mapNotNull { candidate ->
      (candidate.candidate as? KaFunctionCall<*>)?.let {
        SignatureCallCandidate(it, candidate.isInBestCandidates)
      }
    }

internal fun KaSession.resolveSingleSignatureCall(callElement: KtCallElement): KaFunctionCall<*>? =
    callElement.resolveToCall()?.successfulFunctionCallOrNull()

internal fun KaSession.signatureDisplayName(callElement: KtCallElement, call: KaFunctionCall<*>): String {
  val symbol = call.signature.symbol
  val receiverType = symbol.receiverParameter?.returnType
  val receiverPrefix = if (receiverType != null) {
    "${receiverType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)}."
  } else {
    ""
  }
  val callee = callElement.calleeExpression?.text?.toString()?.trim().orEmpty()
  if (callee.isNotEmpty()) return receiverPrefix + callee
  return receiverPrefix + (symbol.name?.asString() ?: "")
}

internal fun KaSession.parameterLabel(parameter: KaVariableSignature<KaValueParameterSymbol>): String {
  val declaredType = parameter.returnType
  val isVararg = parameter.symbol.isVararg
  val type = if (isVararg) {
    (declaredType as? KaClassType)?.typeArguments?.singleOrNull()?.type ?: declaredType
  } else {
    declaredType
  }
  val rendered = type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
  val prefix = if (isVararg) "vararg " else ""
  return "$prefix${parameter.symbol.name.asString()}: $rendered"
}

internal fun signatureArgumentIndex(callElement: KtCallElement, offset: Int): Int {
  val arguments = callElement.valueArgumentList?.arguments.orEmpty()
  val current = arguments.firstOrNull { offset <= it.textRange.endOffset }
  return if (current != null) arguments.indexOf(current) else arguments.size
}

internal fun KaSession.computeActiveParameter(
    callElement: KtCallElement,
    call: KaFunctionCall<*>,
    offset: Int,
    argumentIndex: Int,
): Int {
  val parameters = call.signature.valueParameters
  if (parameters.isEmpty()) return 0
  val arguments = callElement.valueArgumentList?.arguments.orEmpty()
  val current = arguments.firstOrNull { offset <= it.textRange.endOffset }
  if (current != null) {
    val expression = current.getArgumentExpression()
    val mappedParameter = expression?.let { call.argumentMapping[it] }
    if (mappedParameter != null) {
      val declaredIndex = parameters.indexOfFirst { it.symbol.name == mappedParameter.symbol.name }
      if (declaredIndex >= 0) return declaredIndex
    }
  }
  if (parameters.last().symbol.isVararg && argumentIndex >= parameters.size) {
    return parameters.lastIndex
  }
  return argumentIndex
}
