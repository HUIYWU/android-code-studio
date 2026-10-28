@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
    org.jetbrains.kotlin.analysis.api.KaNonPublicApi::class,
    org.jetbrains.kotlin.psi.KtNonPublicApi::class,
)

package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
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

private fun elementAt(file: KtFile, offset: Int): PsiElement? {
  val safe = offset.coerceIn(0, file.textLength)
  return file.findElementAt(safe) ?: if (safe > 0) file.findElementAt(safe - 1) else null
}

private fun Collection<KaSymbol>.toTargetResolution(): KotlinSemanticTargetResolution = when (size) {
  0 -> KotlinSemanticTargetResolution.None
  1 -> KotlinSemanticTargetResolution.Unique(single())
  else -> KotlinSemanticTargetResolution.Ambiguous(toList())
}