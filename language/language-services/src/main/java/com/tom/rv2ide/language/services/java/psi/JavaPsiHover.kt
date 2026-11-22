package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.utils.MarkdownHelper
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiDocCommentOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameterListOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable

internal object JavaPsiHover {
  fun render(target: PsiElement): MarkupContent? {
    val signature = signature(target)
    if (signature.isEmpty()) return null
    return MarkupContent(markdown(signature, documentation(target)), MarkupKind.MARKDOWN)
  }

  fun documentation(target: PsiElement): String {
    val owner = target.navigationElement as? PsiDocCommentOwner
    val description = owner?.docComment?.descriptionElements
        ?.joinToString("") { it.text }
        ?.trim()
        .orEmpty()
    return if (description.isEmpty()) "" else MarkdownHelper.asMarkdown(description).trim()
  }

  fun signature(target: PsiElement): String = when (target) {
    is PsiTypeParameter -> typeParameter(target)
    is PsiClass -> {
      val name = target.name.orEmpty()
      if (name.isEmpty()) {
        ""
      } else {
        val kind = when {
          target.isAnnotationType -> "@interface"
          target.isInterface -> "interface"
          target.isEnum -> "enum"
          target.isRecord -> "record"
          else -> "class"
        }
        "$kind $name${typeParameters(target)}"
      }
    }
    is PsiMethod -> {
      val generics = typeParameters(target)
      val prefix = if (generics.isEmpty()) "" else "$generics "
      val name = if (target.isConstructor) target.containingClass?.name.orEmpty() else target.name
      val result = if (target.isConstructor) "" else "${target.returnType?.presentableText.orEmpty()} "
      val parameters = target.parameterList.parameters.joinToString(", ") {
        "${it.type.presentableText} ${it.name.orEmpty()}"
      }
      "$prefix$result$name($parameters)"
    }
    is PsiVariable -> "${target.type.presentableText} ${target.name.orEmpty()}"
    else -> ""
  }

  fun markdown(signature: String, documentation: String): String {
    val codeBlock = "```java\n$signature\n```"
    return if (documentation.isEmpty()) codeBlock else "$codeBlock\n---\n$documentation"
  }

  private fun typeParameters(owner: PsiTypeParameterListOwner): String {
    val parameters = owner.typeParameters
    if (parameters.isEmpty()) return ""
    return parameters.joinToString(", ", "<", ">", transform = ::typeParameter)
  }

  private fun typeParameter(parameter: PsiTypeParameter): String {
    val bounds = parameter.extendsListTypes
        .filter { it.canonicalText != "java.lang.Object" }
        .joinToString(" & ") { it.presentableText }
    return if (bounds.isEmpty()) parameter.name.orEmpty() else "${parameter.name} extends $bounds"
  }
}
