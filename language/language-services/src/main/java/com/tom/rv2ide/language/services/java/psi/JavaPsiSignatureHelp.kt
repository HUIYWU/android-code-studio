package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.lsp.models.ParameterInformation
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureInformation
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType
import org.jetbrains.kotlin.com.intellij.psi.PsiCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiCompiledElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiResolveHelper
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor
import org.jetbrains.kotlin.com.intellij.psi.PsiType
import org.jetbrains.kotlin.com.intellij.psi.impl.light.LightMethodBuilder
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.JavaResolveUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.kotlin.psi.KtDeclaration

internal object JavaPsiSignatureHelp {
  fun find(
      file: PsiJavaFile,
      offset: Int,
      cancelChecker: ICancelChecker,
      kotlinSignature: (KtDeclaration, Int?, Int) -> SignatureHelp?,
  ): SignatureHelp? {
    val call = callAt(file, offset, cancelChecker) ?: return null
    val arguments = call.argumentList ?: return null
    val argumentIndex = argumentIndex(arguments, offset, cancelChecker)
    cancelChecker.abortIfCancelled()
    val resolved = call.resolveMethodGenerics()
    val resolvedMethod = if (resolved.isValidResult) resolved.element as? PsiMethod else null
    val resolveHelper = PsiResolveHelper.getInstance(file.project)
    val candidates = resolveHelper.getReferencedMethodCandidates(call, false)
    val entries = if (candidates.isEmpty()) {
      implicitConstructor(call, resolveHelper)?.let { constructor ->
        listOf(entry(constructor, PsiSubstitutor.EMPTY, false, cancelChecker))
      }.orEmpty()
    } else {
      candidates.mapNotNull { candidate ->
        cancelChecker.abortIfCancelled()
        val method = candidate.element as? PsiMethod ?: return@mapNotNull null
        if (!candidate.isAccessible || !candidate.isStaticsScopeCorrect) return@mapNotNull null
        val selected = resolvedMethod != null && method.isEquivalentTo(resolvedMethod)
        val substitutor = if (selected) resolved.substitutor else candidate.substitutor
        entry(method, substitutor, selected, cancelChecker)
      }.sortedByDescending { it.selected }
    }
    val active = if (entries.isEmpty()) -1 else activeEntry(entries, arguments, argumentIndex, cancelChecker)
    val selected = entries.getOrNull(active)
    val method = selected?.method ?: resolvedMethod
    val declaration = method?.navigationElement as? KtDeclaration
        ?: (call as? PsiNewExpression)?.classOrAnonymousClassReference?.advancedResolve(false)
            ?.takeIf { it.isValidResult }?.element?.navigationElement as? KtDeclaration
    if (declaration != null) {
      cancelChecker.abortIfCancelled()
      val parameterCount = selected?.parameters?.size ?: method?.parameterList?.parametersCount
      val kotlin = kotlinSignature(declaration, parameterCount, argumentIndex)
      cancelChecker.abortIfCancelled()
      if (kotlin != null) return kotlin
    }
    selected ?: return null
    val activeParameter = when {
      selected.parameters.isEmpty() -> 0
      selected.method.isVarArgs -> argumentIndex.coerceAtMost(selected.parameters.lastIndex)
      else -> argumentIndex
    }
    return SignatureHelp(entries.map { it.toSignature(cancelChecker) }, active, activeParameter)
  }

  private fun activeEntry(
      entries: List<Entry>,
      arguments: PsiExpressionList,
      argumentIndex: Int,
      cancelChecker: ICancelChecker,
  ): Int {
    val resolved = entries.indexOfFirst { it.selected && it.acceptsPosition(argumentIndex) }
    if (resolved >= 0 || entries.size == 1) return resolved.coerceAtLeast(0)
    val argumentTypes = arguments.expressions.map {
      cancelChecker.abortIfCancelled()
      it.type
    }
    val compatible = entries.indexOfFirst { it.accepts(argumentIndex, argumentTypes, cancelChecker) }
    return if (compatible >= 0) compatible else
      entries.indexOfFirst { it.acceptsPosition(argumentIndex) }.coerceAtLeast(0)
  }

  private fun implicitConstructor(call: PsiCallExpression, resolveHelper: PsiResolveHelper): PsiMethod? {
    val owner = when (call) {
      is PsiNewExpression -> {
        val target = call.classOrAnonymousClassReference?.advancedResolve(false) ?: return null
        if (!target.isAccessible) return null
        target.element as? PsiClass
      }
      is PsiMethodCallExpression -> {
        val keyword = call.methodExpression.referenceNameElement as? PsiKeyword ?: return null
        when (keyword.tokenType) {
          JavaTokenType.THIS_KEYWORD -> JavaResolveUtil.getContextClass(call)
          JavaTokenType.SUPER_KEYWORD -> JavaResolveUtil.getContextClass(call)?.superClass
          else -> null
        }
      }
      else -> null
    } ?: return null
    if (owner is PsiCompiledElement || owner.containingFile !is PsiJavaFile ||
        owner.isInterface || owner.isEnum || owner.isRecord || owner.constructors.isNotEmpty()) {
      return null
    }
    val name = owner.name ?: return null
    val constructor = LightMethodBuilder(owner.manager, owner.language, name)
        .setContainingClass(owner)
        .setConstructor(true)
    when {
      owner.hasModifierProperty(PsiModifier.PUBLIC) -> constructor.addModifier(PsiModifier.PUBLIC)
      owner.hasModifierProperty(PsiModifier.PROTECTED) -> constructor.addModifier(PsiModifier.PROTECTED)
      owner.hasModifierProperty(PsiModifier.PRIVATE) -> constructor.addModifier(PsiModifier.PRIVATE)
    }
    val accessClass = if (call is PsiNewExpression) call.anonymousClass ?: owner else null
    return constructor.takeIf { resolveHelper.isAccessible(it, call, accessClass) }
  }

  private fun callAt(
      file: PsiJavaFile,
      offset: Int,
      cancelChecker: ICancelChecker,
  ): PsiCallExpression? {
    if (offset <= 0 || offset > file.textLength) return null
    var element: PsiElement? = file.findElementAt(offset - 1)
    while (element != null) {
      cancelChecker.abortIfCancelled()
      if (element is PsiLambdaExpression || element is PsiClass || element is PsiMethod) return null
      if (element is PsiMethodCallExpression || element is PsiNewExpression) {
        val call = element as PsiCallExpression
        val range = call.argumentList?.textRange
        if (range != null && offset > range.startOffset && offset <= range.endOffset) return call
      }
      element = element.parent
    }
    return null
  }

  private fun argumentIndex(
      arguments: PsiExpressionList,
      offset: Int,
      cancelChecker: ICancelChecker,
  ): Int {
    var index = 0
    var child = arguments.firstChild
    while (child != null && child.textRange.startOffset < offset) {
      cancelChecker.abortIfCancelled()
      if (child is PsiJavaToken && child.tokenType == JavaTokenType.COMMA) index++
      child = child.nextSibling
    }
    return index
  }

  private fun entry(
      method: PsiMethod,
      substitutor: PsiSubstitutor,
      selected: Boolean,
      cancelChecker: ICancelChecker,
  ): Entry {
    val parameters = method.parameterList.parameters
    val types = parameters.map { parameter ->
      cancelChecker.abortIfCancelled()
      val type = parameter.type
      substitutor.substitute(type) ?: type
    }
    return Entry(method, parameters, types, selected)
  }

  private class Entry(
      val method: PsiMethod,
      val parameters: Array<PsiParameter>,
      private val types: List<PsiType>,
      val selected: Boolean,
  ) {
    fun toSignature(cancelChecker: ICancelChecker): SignatureInformation {
      cancelChecker.abortIfCancelled()
      val labels = parameters.mapIndexed { index, parameter ->
        cancelChecker.abortIfCancelled()
        val type = types[index]
        val label = if (parameter.isVarArgs && type is PsiArrayType) {
          "${type.componentType.presentableText}..."
        } else {
          type.presentableText
        }
        ParameterInformation((label + " " + parameter.name.orEmpty()).trimEnd(), MarkupContent())
      }
      val name = if (method.isConstructor) method.containingClass?.name ?: method.name else method.name
      val label = labels.joinToString(", ", "$name(", ")") { it.label }
      val documentation = MarkupContent(JavaPsiHover.documentation(method), MarkupKind.MARKDOWN)
      return SignatureInformation(label, documentation, labels)
    }

    fun acceptsPosition(index: Int): Boolean =
        index < types.size || method.isVarArgs || index == 0 && types.isEmpty()

    fun accepts(index: Int, arguments: List<PsiType?>, cancelChecker: ICancelChecker): Boolean {
      if (!acceptsPosition(index) || !method.isVarArgs && arguments.size > types.size) return false
      return arguments.indices.all { argumentIndex ->
        cancelChecker.abortIfCancelled()
        val type = arguments[argumentIndex] ?: return@all true
        val parameter = types.getOrNull(argumentIndex)
            ?: types.lastOrNull()?.takeIf { method.isVarArgs } ?: return@all false
        val component = if (method.isVarArgs && argumentIndex >= types.lastIndex) {
          (parameter as? PsiArrayType)?.componentType
        } else {
          null
        }
        TypeConversionUtil.isAssignable(parameter, type) ||
            component != null && TypeConversionUtil.isAssignable(component, type)
      }
    }
  }
}
