package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.interop.JavaPsiAccessorsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiAccessorsResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateAccessorsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.models.LineIndex
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier

internal object JavaPsiAccessorsGeneration {
  fun generate(
      content: String,
      index: LineIndex,
      owner: PsiClass,
      params: JavaPsiGenerateAccessorsParams,
  ): JavaPsiAccessorsResult {
    val checker = params.cancelChecker
    checker.abortIfCancelled()
    if (params.selectedFields.isEmpty()) return result(JavaPsiAccessorsResultStatus.EMPTY_SELECTION)
    val fields = owner.fields.filter { field ->
      checker.abortIfCancelled()
      !field.hasModifierProperty(PsiModifier.STATIC) && field.name in params.selectedFields
    }
    if (fields.size != params.selectedFields.size) return result(JavaPsiAccessorsResultStatus.UNSUPPORTED)
    val brace = owner.rBrace ?: return result(JavaPsiAccessorsResultStatus.UNSUPPORTED)
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
    val fieldStart = fields.first().textRange.startOffset
    val fieldPrefix = content.substring(lineStart(content, fieldStart), fieldStart)
    val memberIndent = if (fieldPrefix.all { it == ' ' || it == '\t' }) fieldPrefix else {
      val ownerStart = owner.textRange.startOffset
      content.substring(lineStart(content, ownerStart), ownerStart).takeWhile { it == ' ' || it == '\t' } + unit
    }
    val text = buildString {
      for (field in fields) {
        checker.abortIfCancelled()
        val name = field.name
        val suffix = Character.toUpperCase(name[0]) + name.substring(1)
        val type = field.type.presentableText
        append(newline)
        append(memberIndent).append("public ").append(type).append(" get").append(suffix).append("() {").append(newline)
        append(memberIndent).append(unit).append("return this.").append(name).append(';').append(newline)
        append(memberIndent).append('}').append(newline)
        if (!field.hasModifierProperty(PsiModifier.FINAL)) {
          append(newline)
          append(memberIndent).append("public void set").append(suffix).append('(').append(type).append(' ').append(name).append(") {").append(newline)
          append(memberIndent).append(unit).append("this.").append(name).append(" = ").append(name).append(';').append(newline)
          append(memberIndent).append('}').append(newline)
        }
      }
    }
    val position = index.indexToPosition(offset)
    return JavaPsiAccessorsResult(
        JavaPsiAccessorsResultStatus.GENERATED,
        JavaPsiTextEdit(
            params.file.normalize(), params.documentVersion, params.documentRevision,
            position.line, position.column, position.line, position.column, text,
        ),
    )
  }

  private fun lineStart(content: String, offset: Int): Int =
      maxOf(content.lastIndexOf('\n', offset - 1), content.lastIndexOf('\r', offset - 1)) + 1

  private fun result(status: JavaPsiAccessorsResultStatus) = JavaPsiAccessorsResult(status, null)
}
