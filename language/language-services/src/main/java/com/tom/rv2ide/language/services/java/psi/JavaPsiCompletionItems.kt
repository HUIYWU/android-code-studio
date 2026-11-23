package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.models.JavaCompletionItem
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.ClassCompletionData
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.FieldCompletionData
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.MethodCompletionData
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor
import org.jetbrains.kotlin.com.intellij.psi.PsiType
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.com.intellij.psi.util.ClassUtil
import org.jetbrains.kotlin.com.intellij.psi.util.MethodSignatureUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile

internal object JavaPsiCompletionItems {
  fun item(name: String, kind: CompletionItemKind, detail: String, match: MatchLevel) =
      JavaCompletionItem().apply {
        ideLabel = name
        completionKind = kind
        this.detail = detail
        ideSortText = name
        matchLevel = match
      }

  fun keyword(items: MutableList<CompletionItem>, name: String, prefix: String) {
    val match = CompletionItem.matchLevel(name, prefix)
    if (match != MatchLevel.NO_MATCH) items += item(name, CompletionItemKind.KEYWORD, "keyword", match)
  }

  fun field(field: PsiField, owner: PsiClass, substitutor: PsiSubstitutor, match: MatchLevel): CompletionItem {
    val type = field.type
    val label = (substitutor.substitute(type) ?: type).presentableText
    val kind = if (field is PsiEnumConstant) CompletionItemKind.ENUM_MEMBER else CompletionItemKind.FIELD
    return item(field.name, kind, label, match).apply {
      overrideTypeText = label
      data = FieldCompletionData(field.name, classInfo(owner))
    }
  }

  fun type(psiClass: PsiClass, name: String, match: MatchLevel): CompletionItem {
    val kind = when {
      psiClass is PsiTypeParameter -> CompletionItemKind.TYPE_PARAMETER
      psiClass.isAnnotationType -> CompletionItemKind.ANNOTATION_TYPE
      psiClass.isEnum -> CompletionItemKind.ENUM
      psiClass.isInterface -> CompletionItemKind.INTERFACE
      else -> CompletionItemKind.CLASS
    }
    val info = classInfo(psiClass)
    return item(name, kind, info.className, match).apply {
      if (psiClass.qualifiedName != null) data = info
    }
  }

  fun insertCall(item: CompletionItem, name: String, prefix: String, needsArguments: Boolean) {
    item.insertTextFormat = InsertTextFormat.SNIPPET
    item.snippetDescription = describeSnippet(prefix, allowCommandExecution = true)
    if (needsArguments) {
      item.insertText = "$name(\$0)"
      item.command = Command("Trigger Parameter Hints", Command.TRIGGER_PARAMETER_HINTS)
    } else {
      item.insertText = "$name()\$0"
    }
  }

  fun isJavaOwner(owner: PsiClass): Boolean =
      when (owner.navigationElement) {
        is KtDeclaration, is KtFile -> false
        else -> true
      }

  fun method(
      method: PsiMethod,
      declaringClass: PsiClass,
      substitutor: PsiSubstitutor,
      match: MatchLevel,
      plusOverloads: Int = 0,
      displayName: String = method.name,
  ): CompletionItem {
    val name = method.name
    val parameters = method.parameterList.parameters
    val labels = parameters.map {
      val type = it.type
      (substitutor.substitute(type) ?: type).presentableText
    }
    return item(displayName, CompletionItemKind.METHOD, labels.joinToString(", ", "$name(", ")"), match).apply {
      overrideTypeText = method.returnType?.let { (substitutor.substitute(it) ?: it).presentableText }
      data = MethodCompletionData(
          name,
          classInfo(declaringClass),
          labels,
          parameters.map { erasedTypeName(TypeConversionUtil.erasure(it.type)) },
          plusOverloads,
      )
    }
  }

  private fun classInfo(psiClass: PsiClass): ClassCompletionData {
    val name = psiClass.name.orEmpty()
    val qualifiedName = psiClass.qualifiedName ?: return ClassCompletionData(name)
    val topLevel = generateSequence(psiClass) { it.containingClass }.last()
    return ClassCompletionData(
        className = qualifiedName,
        isCompleteData = true,
        flatName = ClassUtil.getJVMClassName(psiClass) ?: qualifiedName,
        simpleName = name,
        isNested = psiClass.containingClass != null,
        topLevelClass = topLevel.qualifiedName ?: qualifiedName,
    )
  }

  private fun erasedTypeName(type: PsiType): String = when (type) {
    is PsiArrayType -> erasedTypeName(type.componentType) + "[]"
    is PsiClassType -> type.resolve()?.let(ClassUtil::getJVMClassName) ?: type.canonicalText
    else -> type.canonicalText
  }

  class MethodGroup(
      private val method: PsiMethod,
      private val declaringClass: PsiClass,
      private val substitutor: PsiSubstitutor,
      val match: MatchLevel,
  ) {
    private val signatures = MethodSignatureUtil.createErasedMethodSignatureSet()

    fun add(method: PsiMethod, substitutor: PsiSubstitutor) {
      signatures.add(method.getSignature(substitutor))
    }

    fun item(prefix: String, addParentheses: Boolean): CompletionItem =
        JavaPsiCompletionItems.method(method, declaringClass, substitutor, match, signatures.size - 1).apply {
          if (addParentheses) insertCall(this, method.name, prefix, signatures.size > 1 || method.hasParameters())
        }
  }
}
