/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.language.services.kotlin.backend.analysis
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import org.jetbrains.kotlin.analysis.api.analyze as kaAnalyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile

internal object KotlinAnalysisDiagnostics {
  fun collectDiagnosticsFor(file: KtFile): List<DiagnosticItem> =
      kaAnalyze(file) {
        val text = file.text
        val lineIndex = LineIndex.from(text)
        val items = buildList {
          PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).forEach { error ->
            add(item(lineIndex, error.textRange, "PARSER_ERROR", error.errorDescription,
                DiagnosticSeverity.ERROR, "syntax"))
          }
          file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
              .forEach { diagnostic ->
                val ranges = diagnostic.textRanges.ifEmpty { listOf(diagnostic.psi.textRange) }
                ranges.forEach { range ->
                  add(item(lineIndex, range, diagnostic.factoryName, diagnostic.defaultMessage,
                      diagnostic.severity.toIdeSeverity(), "kotlin"))
                }
              }
        }
        items.distinct().sortedWith(DiagnosticItem.START_COMPARATOR)
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
