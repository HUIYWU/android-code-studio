package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.interop.JavaPsiConstructorResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiConstructorResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateConstructorParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.models.LineIndex
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiEllipsisType
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil

internal object JavaPsiConstructorGeneration {
  fun generate(
      content: String,
      index: LineIndex,
      owner: PsiClass,
      params: JavaPsiGenerateConstructorParams,
  ): JavaPsiConstructorResult {
    val checker = params.cancelChecker
    checker.abortIfCancelled()
    val name = owner.name ?: return result(JavaPsiConstructorResultStatus.UNSUPPORTED)
    if (owner.isInterface || owner.isEnum || owner.isRecord || owner.isAnnotationType) {
      return result(JavaPsiConstructorResultStatus.UNSUPPORTED)
    }
    val fields = owner.fields.filter { field ->
      checker.abortIfCancelled()
      !field.hasModifierProperty(PsiModifier.STATIC) && field.name in params.selectedFields
    }
    if (fields.isEmpty()) return result(JavaPsiConstructorResultStatus.EMPTY_SELECTION)
    if (fields.size != params.selectedFields.size || fields.any {
          it.hasModifierProperty(PsiModifier.FINAL) && it.hasInitializer()
        }) return result(JavaPsiConstructorResultStatus.UNSUPPORTED)
    val types = fields.map { it.type }
    val signature = types.map { TypeConversionUtil.erasure(it) }
    for (constructor in owner.constructors) {
      checker.abortIfCancelled()
      val parameters = constructor.parameterList.parameters
      if (parameters.size != signature.size) continue
      if (parameters.indices.all { index ->
            checker.abortIfCancelled()
            val type = parameters[index].type
            val parameterType = if (type is PsiEllipsisType) type.toArrayType() else type
            TypeConversionUtil.erasure(parameterType) == signature[index]
          }) return result(JavaPsiConstructorResultStatus.CONSTRUCTOR_EXISTS)
    }

    var declaration: PsiElement = fields.last()
    while (true) {
      checker.abortIfCancelled()
      val next = PsiTreeUtil.skipWhitespacesAndCommentsForward(declaration)
      if (next !is PsiJavaToken || next.tokenType != JavaTokenType.COMMA) break
      declaration = PsiTreeUtil.skipWhitespacesAndCommentsForward(next) as? PsiField
          ?: return result(JavaPsiConstructorResultStatus.UNSUPPORTED)
    }
    val lastToken = declaration.lastChild as? PsiJavaToken
    if (lastToken?.tokenType != JavaTokenType.SEMICOLON) {
      val semicolon = PsiTreeUtil.skipWhitespacesAndCommentsForward(declaration) as? PsiJavaToken
      if (semicolon?.tokenType != JavaTokenType.SEMICOLON) return result(JavaPsiConstructorResultStatus.UNSUPPORTED)
      declaration = semicolon
    }
    var offset = declaration.textRange.endOffset
    var next = declaration.nextSibling
    var atLineStart = false
    while (next is PsiWhiteSpace || next is PsiComment) {
      checker.abortIfCancelled()
      if (next is PsiWhiteSpace) {
        val separator = next.text.indexOfFirst { it == '\r' || it == '\n' }
        if (separator >= 0) {
          offset = next.textRange.startOffset + separator + 1
          if (content[offset - 1] == '\r' && offset < content.length && content[offset] == '\n') offset++
          atLineStart = true
          break
        }
      } else {
        offset = next.textRange.endOffset
      }
      next = next.nextSibling
    }
    val separatorOffset = content.indexOfFirst { it == '\r' || it == '\n' }
    val newline = when {
      separatorOffset < 0 -> "\n"
      content[separatorOffset] == '\r' && content.getOrNull(separatorOffset + 1) == '\n' -> "\r\n"
      else -> content[separatorOffset].toString()
    }
    val unit = if (params.useSoftTabs) " ".repeat(params.indentationTabSize) else "\t"
    val fieldStart = fields.last().textRange.startOffset
    val lineStart = maxOf(content.lastIndexOf('\n', fieldStart - 1), content.lastIndexOf('\r', fieldStart - 1)) + 1
    val prefix = content.substring(lineStart, fieldStart)
    val memberIndent = if (prefix.all { it == ' ' || it == '\t' }) prefix else {
      val ownerStart = owner.textRange.startOffset
      val ownerLineStart = maxOf(content.lastIndexOf('\n', ownerStart - 1), content.lastIndexOf('\r', ownerStart - 1)) + 1
      content.substring(ownerLineStart, ownerStart).takeWhile { it == ' ' || it == '\t' } + unit
    }
    val parameters = fields.indices.joinToString(", ") { "${types[it].canonicalText} ${fields[it].name}" }
    val text = buildString {
      if (!atLineStart) append(newline)
      append(memberIndent).append("public ").append(name).append('(').append(parameters).append(") {").append(newline)
      for (field in fields) {
        checker.abortIfCancelled()
        append(memberIndent).append(unit).append("this.").append(field.name).append(" = ").append(field.name).append(';').append(newline)
      }
      append(memberIndent).append('}').append(newline)
    }
    val position = index.indexToPosition(offset)
    return JavaPsiConstructorResult(
        JavaPsiConstructorResultStatus.GENERATED,
        JavaPsiTextEdit(
            params.file.normalize(), params.documentVersion, params.documentRevision,
            position.line, position.column, position.line, position.column, text,
        ),
    )
  }

  private fun result(status: JavaPsiConstructorResultStatus) = JavaPsiConstructorResult(status, null)
}
