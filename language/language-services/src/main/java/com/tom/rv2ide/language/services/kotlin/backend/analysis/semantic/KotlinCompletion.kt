@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
)

package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.isMarkedNullable
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.scope
import org.jetbrains.kotlin.analysis.api.components.withNullability
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.signatures.KaFunctionSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.types.Variance

private const val RECEIVER_CANDIDATE_BUDGET = 200
private const val COMPLETION_BUDGET = 50

internal sealed interface CompletionTarget {
  data class Receiver(val receiver: KtExpression, val safe: Boolean) : CompletionTarget
  data object Lexical : CompletionTarget
  data object TypePosition : CompletionTarget
  data object PackageOrImport : CompletionTarget
  data object None : CompletionTarget
}

internal fun resolveCompletionTarget(offset: Int, element: PsiElement?): CompletionTarget {
  var node: PsiElement? = element
  if (node is PsiWhiteSpace) node = node.prevSibling ?: node
  while (node != null && node !is PsiFile) {
    when (node) {
      is KtImportDirective, is KtPackageDirective -> return CompletionTarget.PackageOrImport
      is KtFunctionLiteral -> return adjacentDotReceiver(offset, node) ?: CompletionTarget.Lexical
      is KtTypeReference, is KtUserType -> return CompletionTarget.TypePosition
      is KtQualifiedExpression -> {
        val tokenNode = node.node.findChildByType(node.operationSign)
        val tokenRange = tokenNode?.psi?.textRange
        if (tokenRange != null && offset > tokenRange.startOffset) {
          val selectorEnd = node.selectorExpression?.textRange?.endOffset ?: (tokenRange.endOffset + 1)
          if (offset <= selectorEnd) {
            return CompletionTarget.Receiver(node.receiverExpression, node is KtSafeQualifiedExpression)
          }
        }
      }
    }
    node = node.parent
  }
  return adjacentDotReceiver(offset, element) ?: CompletionTarget.Lexical
}

private fun adjacentDotReceiver(offset: Int, element: PsiElement?): CompletionTarget.Receiver? {
  val file = element?.containingFile ?: return null
  val text = file.text
  if (offset <= 1 || offset > text.length) return null
  if (text[offset - 1] != '.') return null
  val safe = offset >= 2 && text[offset - 2] == '?'
  val receiverEnd = if (safe) offset - 2 else offset - 1
  if (receiverEnd <= 0) return null
  val anchor = file.findElementAt(receiverEnd - 1) ?: return null
  val receiver = generateSequence(anchor) { it.parent }
      .takeWhile { it !is PsiFile }
      .mapNotNull { it as? KtExpression }
      .filter { it.textRange?.endOffset == receiverEnd }
      .toList()
      .lastOrNull()
      ?: return null
  return CompletionTarget.Receiver(receiver, safe)
}

internal fun KaSession.receiverCompletionItems(
    prefix: String,
    target: CompletionTarget.Receiver,
    cancelChecker: ICancelChecker,
): List<CompletionItem> {
  val type = runCatching { target.receiver.expressionType }.getOrNull() ?: return emptyList()
  val effectiveType = if (target.safe && type.isMarkedNullable) type.withNullability(false) else type
  if (!target.safe && effectiveType.isMarkedNullable) return emptyList()
  val nameFilter: (Name) -> Boolean = { it.asString().startsWith(prefix, ignoreCase = true) }
  val items = ArrayList<CompletionItem>(64)
  var remaining = RECEIVER_CANDIDATE_BUDGET

  fun add(item: CompletionItem?) {
    if (item == null || remaining <= 0) return
    items += item
    remaining--
  }

  val typeScope = effectiveType.scope
  if (typeScope != null) {
    for (signature in typeScope.getCallableSignatures(nameFilter)) {
      cancelChecker.abortIfCancelled()
      if (remaining <= 0) break
      add(completionItemFor(signature, prefix))
    }
    if (remaining > 0) {
      for (classifier in typeScope.getClassifierSymbols(nameFilter)) {
        cancelChecker.abortIfCancelled()
        if (remaining <= 0) break
        add(completionItemFor(classifier, prefix))
      }
    }
  } else {
    val classSymbol = (effectiveType as? KaClassType)?.symbol as? KaClassSymbol ?: return emptyList()
    val memberScope = classSymbol.memberScope
    for (symbol in memberScope.callables(nameFilter)) {
      cancelChecker.abortIfCancelled()
      if (remaining <= 0) break
      add(completionItemFor(symbol, prefix))
    }
    if (remaining > 0) {
      for (classifier in memberScope.classifiers(nameFilter)) {
        cancelChecker.abortIfCancelled()
        if (remaining <= 0) break
        add(completionItemFor(classifier, prefix))
      }
    }
  }
  return items.distinctBy { it.ideLabel }.take(COMPLETION_BUDGET)
}

private fun KaSession.completionItemFor(signature: KaCallableSignature<*>, prefix: String): CompletionItem? {
  val detail = when (signature) {
    is KaFunctionSignature<*> -> {
      val name = symbolName(signature.symbol) ?: return null
      signature.valueParameters.joinToString(", ", prefix = "$name(", postfix = ")") { parameter ->
        "${parameter.symbol.name.asString()}: ${parameter.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)}"
      }
    }
    else -> signature.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
  }
  return completionItemFor(signature.symbol, prefix, detail)
}