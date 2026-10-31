@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
    org.jetbrains.kotlin.analysis.api.KaNonPublicApi::class,
    org.jetbrains.kotlin.psi.KtNonPublicApi::class,
)

package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.resolution.KaCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCompoundAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol as callableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.com.intellij.psi.MultiRangeReference
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.references.KtDestructuringDeclarationReference
import org.jetbrains.kotlin.idea.references.KtForLoopInReference
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtPropertyDelegationMethodsReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

internal sealed interface KotlinSemanticTargetResolution {
  data object None : KotlinSemanticTargetResolution
  data class Unique(val symbol: KaSymbol) : KotlinSemanticTargetResolution
  data class Ambiguous(val symbols: List<KaSymbol>) : KotlinSemanticTargetResolution
}

internal fun KaSession.resolveOrdinaryTargetAt(
    file: KtFile,
    offset: Int,
): KotlinSemanticTargetResolution {
  var element: PsiElement? = elementAt(file, offset)
  while (element != null && element !is PsiFile) {
    if (element is KtArrayAccessExpression) {
      val reference = element.reference as? KtReference
      if (reference is MultiRangeReference && reference.isCaretInRanges(offset)) {
        return element.resolveSymbol()?.let(KotlinSemanticTargetResolution::Unique)
            ?: KotlinSemanticTargetResolution.None
      }
    }
    if (element is KtCallExpression) {
      val reference = element.reference as? KtInvokeFunctionReference
      if (reference != null && reference.isCaretInRanges(offset)) {
        return reference.resolveToSymbols().toTargetResolution()
      }
    }
    if (element is KtOperationReferenceExpression && element.parent is KtBinaryExpression) {
      val binaryExpression = element.parent as KtBinaryExpression
      if (binaryExpression.operationToken in KtTokens.AUGMENTED_ASSIGNMENTS) {
        return resolveCompoundAssignmentTarget(binaryExpression, offset)
      }
    }
    if (element is KtDestructuringDeclarationEntry) {
      val reference = element.reference as? KtDestructuringDeclarationReference
      if (reference != null && reference.isCaretInReferencedName(offset)) {
        return reference.resolveToSymbols()
            .filterIsInstance<KaNamedFunctionSymbol>()
            .toTargetResolution()
      }
    }
    if (element is KtForExpression) {
      val reference = element.reference as? KtForLoopInReference
      if (reference != null && reference.isCaretInReferencedName(offset)) {
        return reference.resolveToSymbols().toTargetResolution()
      }
    }
    if (element is KtPropertyDelegate) {
      val reference = element.reference as? KtPropertyDelegationMethodsReference
      if (reference != null && reference.isCaretInReferencedName(offset)) {
        return reference.resolveToSymbols().toTargetResolution()
      }
    }
    if (element is KtPropertyAccessor) {
      val keywordRange = element.namePlaceholder.textRange
      if (offset >= keywordRange.startOffset && offset < keywordRange.endOffset) {
        return KotlinSemanticTargetResolution.Unique(element.symbol)
      }
    }
    if (element is KtSimpleNameExpression) {
      val reference = element.reference as? KtReference
      if (reference != null && reference.isCaretInReferencedName(offset)) {
        return reference.resolveToSymbols().toTargetResolution()
      }
    }
    if (element is KtNamedDeclaration) {
      val nameRange = element.nameIdentifier?.textRange
      if (nameRange != null && offset >= nameRange.startOffset && offset < nameRange.endOffset) {
        return KotlinSemanticTargetResolution.Unique(element.symbol)
      }
    }
    element = element.parent
  }
  return KotlinSemanticTargetResolution.None
}
internal fun KtReference.isCaretInReferencedName(offset: Int): Boolean {
  val elementRange = element.textRange ?: return false
  val range = rangeInElement
  val absoluteStart = elementRange.startOffset + range.startOffset
  val absoluteEnd = elementRange.startOffset + range.endOffset
  return offset >= absoluteStart && offset < absoluteEnd
}

private fun MultiRangeReference.isCaretInRanges(offset: Int): Boolean {
  val elementRange = element.textRange ?: return false
  return ranges.any { range ->
    val absoluteStart = elementRange.startOffset + range.startOffset
    val absoluteEnd = elementRange.startOffset + range.endOffset
    offset >= absoluteStart && offset < absoluteEnd
  }
}

private fun KaSession.resolveCompoundAssignmentTarget(
    expression: KtBinaryExpression,
    offset: Int,
): KotlinSemanticTargetResolution {
  val reference = expression.operationReference.reference as? KtReference ?: return KotlinSemanticTargetResolution.None
  if (!reference.isCaretInReferencedName(offset)) return KotlinSemanticTargetResolution.None
  val symbol = when (val call = expression.resolveToCall()?.successfulCallOrNull<KaCall>()) {
    is KaCompoundAccessCall -> call.operationCall.callableSymbol
    is KaFunctionCall<*> -> call.callableSymbol
    else -> null
  }
  return listOfNotNull(symbol).toTargetResolution()
}

private fun elementAt(file: KtFile, offset: Int): PsiElement? {
  val safe = offset.coerceIn(0, file.textLength)
  return file.findElementAt(safe) ?: if (safe > 0) file.findElementAt(safe - 1) else null
}

private fun Collection<KaSymbol>.toTargetResolution(): KotlinSemanticTargetResolution = when (size) {
  0 -> KotlinSemanticTargetResolution.None
  1 -> KotlinSemanticTargetResolution.Unique(single())
  else -> KotlinSemanticTargetResolution.Ambiguous(toList())
}