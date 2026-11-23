package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateToStringParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.java.interop.JavaPsiToStringResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiToStringResultStatus
import com.tom.rv2ide.lsp.models.LineIndex
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier

internal object JavaPsiToStringGeneration {
  fun generate(
      content: String,
      index: LineIndex,
      owner: PsiClass,
      params: JavaPsiGenerateToStringParams,
  ): JavaPsiToStringResult {
    val checker = params.cancelChecker
    checker.abortIfCancelled()
    if (params.selectedFields.isEmpty()) return result(JavaPsiToStringResultStatus.EMPTY_SELECTION)
    if (owner.methods.any { method ->
          checker.abortIfCancelled()
          !method.isConstructor && method.name == "toString" && method.parameterList.parametersCount == 0
        }) return result(JavaPsiToStringResultStatus.METHOD_EXISTS)
    val fields = owner.fields.filter { field ->
      checker.abortIfCancelled()
      !field.hasModifierProperty(PsiModifier.STATIC) && field.name in params.selectedFields
    }
    if (fields.size != params.selectedFields.size) return result(JavaPsiToStringResultStatus.UNSUPPORTED)
    val brace = owner.rBrace ?: return result(JavaPsiToStringResultStatus.UNSUPPORTED)
    val braceOffset = brace.textRange.startOffset
    val braceLineStart = lineStart(content, braceOffset)
    val bracePrefix = content.substring(braceLineStart, braceOffset)
    val offset = if (bracePrefix.all { it == ' ' || it == '\t' }) braceLineStart else braceOffset
    val separatorOffset = content.indexOfFirst { it == '\r' || it == '\n' }
    val newline = when {
      separatorOffset < 0 -> "\n"
      content[separatorOffset] == '\r' && content.getOrNull(separatorOffset + 1) == '\n' -> "\r\n"
      else -> content[separatorOffset].toString()
    }
    val unit = if (params.useSoftTabs) " ".repeat(params.indentationTabSize) else "\t"
    val ownerStart = owner.textRange.startOffset
    val memberIndent = content.substring(lineStart(content, ownerStart), ownerStart)
        .takeWhile { it == ' ' || it == '\t' } + unit
    val text = buildString {
      append(newline)
      append(memberIndent).append("@Override").append(newline)
      append(memberIndent).append("public String toString() {").append(newline)
      append(memberIndent).append(unit).append("return \"").append(owner.name.orEmpty()).append('[')
      for ((fieldIndex, field) in fields.withIndex()) {
        checker.abortIfCancelled()
        if (fieldIndex > 0) append(", ")
        append(field.name).append("=\" + ").append(field.name).append(" + \"")
      }
      append("]\";").append(newline)
      append(memberIndent).append('}').append(newline)
    }
    val position = index.indexToPosition(offset)
    return JavaPsiToStringResult(
        JavaPsiToStringResultStatus.GENERATED,
        JavaPsiTextEdit(
            params.file.normalize(), params.documentVersion, params.documentRevision,
            position.line, position.column, position.line, position.column, text,
        ),
    )
  }

  private fun lineStart(content: String, offset: Int): Int =
      maxOf(content.lastIndexOf('\n', offset - 1), content.lastIndexOf('\r', offset - 1)) + 1

  private fun result(status: JavaPsiToStringResultStatus) = JavaPsiToStringResult(status, null)
}
