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
import org.jetbrains.kotlin.analysis.api.components.containingDeclaration
import org.jetbrains.kotlin.analysis.api.components.isImplicitReferenceToCompanion
import org.jetbrains.kotlin.analysis.api.components.isMarkedNullable
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.scope
import org.jetbrains.kotlin.analysis.api.components.scopeContext
import org.jetbrains.kotlin.analysis.api.components.compositeScope
import org.jetbrains.kotlin.analysis.api.components.defaultTypeWithStarProjections
import org.jetbrains.kotlin.analysis.api.components.withNullability
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.signatures.KaFunctionSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
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
  val primary = resolveCompletionTargetFrom(offset, element)
  if (primary !is CompletionTarget.Lexical) return primary
  // At a closing delimiter the caret leaf is outside the preceding type/expression subtree.
  val previousLeaf = element?.containingFile?.findElementAt(offset - 1) ?: return primary
  val secondary = resolveCompletionTargetFrom(offset, previousLeaf)
  return if (secondary is CompletionTarget.Lexical) primary else secondary
}

private fun resolveCompletionTargetFrom(offset: Int, element: PsiElement?): CompletionTarget {
  if (element != null && isInsideImportOrPackageDirective(element)) {
    return CompletionTarget.PackageOrImport
  }
  var node: PsiElement? = element
  if (node is PsiWhiteSpace) {
    // A whitespace's sibling may be the enclosing declaration, which would skip the initializer
    // subtree that holds the qualified expression. Start from the previous character leaf instead.
    val previousLeaf = node.containingFile?.findElementAt(offset - 1)
    node = previousLeaf ?: node.prevSibling ?: node
  }
  while (node != null && node !is PsiFile) {
    if (isInsideImportOrPackageDirective(node)) return CompletionTarget.PackageOrImport
    when (node) {
      is KtImportDirective, is KtPackageDirective -> return CompletionTarget.PackageOrImport
      is KtFunctionLiteral -> return adjacentDotReceiver(offset, node) ?: CompletionTarget.Lexical
      is KtTypeReference, is KtUserType -> return CompletionTarget.TypePosition
      is KtQualifiedExpression -> {
        val tokenNode = node.node.findChildByType(node.operationSign)
        val tokenRange = tokenNode?.psi?.textRange
        val selectorEnd = node.selectorExpression?.textRange?.endOffset ?: (tokenRange?.endOffset ?: -1) + 1
        if (tokenRange != null && offset > tokenRange.startOffset && offset <= selectorEnd) {
          return CompletionTarget.Receiver(node.receiverExpression, node is KtSafeQualifiedExpression)
        }
      }
    }
    node = node.parent
  }
  // Fallback for incomplete code where the parser exposes no usable qualified expression.
  return adjacentDotReceiver(offset, element) ?: CompletionTarget.Lexical
}

private fun isInsideImportOrPackageDirective(element: PsiElement): Boolean {
  var parent: PsiElement? = element.parent
  while (parent != null && parent !is PsiFile) {
    if (parent is KtImportDirective || parent is KtPackageDirective) return true
    parent = parent.parent
  }
  return false
}

// Handles both a bare trailing dot and a dot followed by a partially typed selector.
private fun adjacentDotReceiver(offset: Int, element: PsiElement?): CompletionTarget.Receiver? {
  val file = element?.containingFile ?: return null
  val text = file.text
  if (offset <= 1 || offset > text.length) return null
  var selectorStart = offset
  while (selectorStart > 0) {
    val ch = text[selectorStart - 1]
    if (ch.isLetterOrDigit() || ch == '_') selectorStart-- else break
  }
  if (selectorStart <= 0) return null
  val dotIndex = selectorStart - 1
  if (text[dotIndex] != '.') return null
  val safe = dotIndex >= 1 && text[dotIndex - 1] == '?'
  val receiverEnd = if (safe) dotIndex - 1 else dotIndex
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

internal fun KaSession.receiverTypeShortName(receiver: KtExpression, safe: Boolean): String? {
  val type = runCatching { receiver.expressionType }.getOrNull() ?: return null
  val effective = if (safe && type.isMarkedNullable) type.withNullability(false) else type
  return runCatching {
        effective.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
      }
      .getOrNull()
}

internal fun KaSession.receiverCompletionItems(
    prefix: String,
    target: CompletionTarget.Receiver,
    cancelChecker: ICancelChecker,
): List<CompletionItem> {
  val type = runCatching { target.receiver.expressionType }.getOrNull()
  val effectiveType =
      type?.let { if (target.safe && it.isMarkedNullable) it.withNullability(false) else it }
  val nullableDot = effectiveType != null && !target.safe && effectiveType.isMarkedNullable
  val nameFilter: (Name) -> Boolean = { it.asString().startsWith(prefix, ignoreCase = true) }
  val items = ArrayList<CompletionItem>(64)
  var remaining = RECEIVER_CANDIDATE_BUDGET

  fun add(item: CompletionItem?) {
    if (item == null || remaining <= 0) return
    items += item
    remaining--
  }

  val receiverRange = target.receiver.textRange
  val receiverResolution =
      receiverRange?.let { range ->
        val offset = (range.endOffset - 1).coerceAtLeast(range.startOffset)
        resolveOrdinaryTargetAt(target.receiver.containingKtFile, offset)
      }
  val referencedClass =
      (receiverResolution as? KotlinSemanticTargetResolution.Unique)?.symbol as? KaClassSymbol
  val receiverClass =
      if (referencedClass != null && referencedClass.classKind == KaClassKind.COMPANION_OBJECT) {
        val referenceExpression = when (val receiver = target.receiver) {
          is KtNameReferenceExpression -> receiver
          is KtDotQualifiedExpression -> receiver.selectorExpression as? KtNameReferenceExpression
          else -> null
        }
        val reference = referenceExpression?.reference as? KtReference
        if (reference?.isImplicitReferenceToCompanion() == true) {
          referencedClass.containingDeclaration as? KaClassSymbol
        } else {
          referencedClass
        }
      } else {
        referencedClass
      }
  val classifierQualifier =
      receiverClass != null &&
          !receiverClass.classKind.isObject &&
          (target.receiver is KtNameReferenceExpression ||
              target.receiver is KtDotQualifiedExpression)

  if (!classifierQualifier && !nullableDot) {
    if (effectiveType == null) {
      val classSymbol = receiverClass ?: return emptyList()
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
    } else {
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
        val classSymbol = (effectiveType as? KaClassType)?.symbol as? KaClassSymbol
            ?: return emptyList()
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
    }
  }

  if (classifierQualifier && receiverClass != null) {
    if (remaining > 0) {
      for (classifier in receiverClass.memberScope.classifiers(nameFilter)) {
        cancelChecker.abortIfCancelled()
        if (remaining <= 0) break
        add(completionItemFor(classifier, prefix))
      }
    }
    val staticScope = receiverClass.staticMemberScope
    for (symbol in staticScope.callables(nameFilter)) {
      cancelChecker.abortIfCancelled()
      if (remaining <= 0) break
      add(completionItemFor(symbol, prefix))
    }
    if (remaining > 0) {
      for (classifier in staticScope.classifiers(nameFilter)) {
        cancelChecker.abortIfCancelled()
        if (remaining <= 0) break
        add(completionItemFor(classifier, prefix))
      }
    }
  }

  if (receiverClass != null && remaining > 0) {
    val companion = (receiverClass as? KaNamedClassSymbol)?.companionObject
    if (companion != null) {
      val companionScope = companion.memberScope
      for (symbol in companionScope.callables(nameFilter)) {
        cancelChecker.abortIfCancelled()
        if (remaining <= 0) break
        add(completionItemFor(symbol, prefix))
      }
      if (remaining > 0) {
        for (classifier in companionScope.classifiers(nameFilter)) {
          cancelChecker.abortIfCancelled()
          if (remaining <= 0) break
          add(completionItemFor(classifier, prefix))
        }
      }
    }
  }
  if (remaining > 0 && effectiveType != null) {
    val compositeScope = target.receiver.containingKtFile.scopeContext(target.receiver).compositeScope()
    for (symbol in compositeScope.callables(nameFilter)) {
      cancelChecker.abortIfCancelled()
      if (remaining <= 0) break
      val callable = symbol as? KaCallableSymbol ?: continue
      if (callable.isExtension) {
        val extensionReceiverType = callable.receiverParameter?.returnType ?: continue
        if (!isExtensionReceiverApplicable(effectiveType, extensionReceiverType)) continue
        add(completionItemFor(symbol, prefix))
        continue
      }
      if (callable !is KaPropertySymbol && callable !is KaLocalVariableSymbol) continue
      val functionType = runCatching { callable.returnType as? KaFunctionType }.getOrNull() ?: continue
      if (!functionType.hasReceiver) continue
      val functionReceiverType = functionType.receiverType ?: continue
      if (!isExtensionReceiverApplicable(effectiveType, functionReceiverType)) continue
      add(completionItemFor(symbol, prefix, forceCallSnippet = true))
    }
  }
  return items.distinctBy { it.ideLabel to it.completionKind }.take(COMPLETION_BUDGET)
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

private fun KaSession.isExtensionReceiverApplicable(
    receiverType: KaType,
    extensionReceiverType: KaType,
): Boolean {
  val exact = runCatching { receiverType.isSubtypeOf(extensionReceiverType) }.getOrDefault(false)
  if (exact) return true
  if (!extensionReceiverType.containsTypeParameter()) return false
  val classifier = (extensionReceiverType as? KaClassType)?.symbol ?: return false
  val starProjected = runCatching {
    classifier.defaultTypeWithStarProjections.withNullability(extensionReceiverType.isMarkedNullable)
  }.getOrNull() ?: return false
  return runCatching { receiverType.isSubtypeOf(starProjected) }.getOrDefault(false)
}

private fun KaType.containsTypeParameter(): Boolean = when (this) {
  is KaTypeParameterType -> true
  is KaFlexibleType -> lowerBound.containsTypeParameter() || upperBound.containsTypeParameter()
  is KaClassType -> typeArguments.any { projection -> projection.type?.containsTypeParameter() == true }
  else -> false
}