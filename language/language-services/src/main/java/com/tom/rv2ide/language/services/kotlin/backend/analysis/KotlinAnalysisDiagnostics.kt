package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile

internal object KotlinAnalysisDiagnostics {
  fun collectDiagnosticsFor(session: KaSession, file: KtFile): List<DiagnosticItem> {
    val text = file.text
    val lineIndex = LineIndex.from(text)
    val items = buildList {
      PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).forEach { error ->
        add(item(lineIndex, error.textRange, "PARSER_ERROR", error.errorDescription, DiagnosticSeverity.ERROR, "syntax"))
      }
      with(session) { file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS) }.forEach { diagnostic ->
        val ranges = diagnostic.textRanges.ifEmpty { listOf(diagnostic.psi.textRange) }
        ranges.forEach { range ->
          add(item(lineIndex, range, diagnostic.factoryName, diagnostic.defaultMessage, diagnostic.severity.toIdeSeverity(), "kotlin"))
        }
      }
    }
    return items.distinct().sortedWith(DiagnosticItem.START_COMPARATOR)
  }

  private fun item(
      lineIndex: LineIndex,
      range: TextRange,
      code: String,
      message: String,
      severity: DiagnosticSeverity,
      source: String,
  ) = DiagnosticItem(
      message = message,
      code = code,
      range = Range(lineIndex.indexToPosition(range.startOffset), lineIndex.indexToPosition(range.endOffset)),
      source = source,
      severity = severity,
  )

  private fun KaSeverity.toIdeSeverity(): DiagnosticSeverity = when (this) {
    KaSeverity.ERROR -> DiagnosticSeverity.ERROR
    KaSeverity.WARNING -> DiagnosticSeverity.WARNING
    KaSeverity.INFO -> DiagnosticSeverity.INFO
  }
}