package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.diagnostics.JavaUnusedImportsInspection
import com.tom.rv2ide.language.services.java.interop.JavaPsiRemoveUnusedImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.models.LineIndex
import org.jetbrains.kotlin.com.intellij.codeInspection.SuppressionUtil
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile

internal object JavaPsiUnusedImportsGeneration {
  fun generate(file: PsiJavaFile, params: JavaPsiRemoveUnusedImportsParams): List<JavaPsiTextEdit> {
    val ranges = ArrayList<TextRange>()
    JavaUnusedImportsInspection.inspect(file, params.cancelChecker) { inspection, descriptor ->
      val statement = requireNotNull(descriptor.psiElement) as PsiImportStatementBase
      if (!SuppressionUtil.inspectionResultSuppressed(statement, inspection)) ranges += statement.textRange
    }
    if (ranges.isEmpty()) return emptyList()
    val content = file.text
    val index = LineIndex.from(content)
    val path = params.file.normalize()
    return ranges.sortedByDescending { it.startOffset }.map { range ->
      params.cancelChecker.abortIfCancelled()
      val removal = removalRange(content, range)
      val start = index.indexToPosition(removal.startOffset)
      val end = index.indexToPosition(removal.endOffset)
      JavaPsiTextEdit(
          path, params.documentVersion, params.documentRevision,
          start.line, start.column, end.line, end.column, "",
      )
    }
  }

  private fun removalRange(content: String, range: TextRange): TextRange {
    var start = range.startOffset
    while (start > 0 && (content[start - 1] == ' ' || content[start - 1] == '\t')) start--
    if (start > 0 && content[start - 1] != '\n' && content[start - 1] != '\r') return range
    var end = range.endOffset
    while (end < content.length && (content[end] == ' ' || content[end] == '\t')) end++
    if (end < content.length) {
      when (content[end]) {
        '\r' -> {
          end++
          if (end < content.length && content[end] == '\n') end++
        }
        '\n' -> end++
        else -> return range
      }
    }
    return TextRange(start, end)
  }
}
