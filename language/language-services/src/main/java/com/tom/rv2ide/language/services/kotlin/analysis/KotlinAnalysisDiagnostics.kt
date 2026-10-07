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
package com.tom.rv2ide.language.services.kotlin.analysis
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.analyze as kaAnalyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile

/**
 * Collects Kotlin compiler diagnostics for a single file and maps them to IDE diagnostic items.
 *
 * TODO(ACS-KT-ANALYSIS-EXPERIMENT): v0 maps message/range/severity only; diagnostic codes and
 *  quick-fix payloads are not wired yet.
 */
@OptIn(KaExperimentalApi::class, KaImplementationDetail::class, K1Deprecation::class)
internal object KotlinAnalysisDiagnostics {
  fun collectDiagnosticsFor(file: KtFile): List<DiagnosticItem> =
      kaAnalyze(file) {
        val text = file.text
        val syntaxErrors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        val analysisDiagnostics =
            file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS).toList()


        buildList {
          syntaxErrors.forEach { errorElement ->
            add(
                diagnosticItem(
                    text = text,
                    range = errorElement.textRange,
                    message = errorElement.errorDescription,
                    severity = DiagnosticSeverity.ERROR,
                    source = "syntax",
                ),
            )
          }

          analysisDiagnostics.forEach { diagnostic ->
            add(
                diagnosticItem(
                    text = text,
                    range = diagnostic.textRanges.firstOrNull() ?: diagnostic.psi.textRange,
                    message = diagnostic.defaultMessage,
                    severity = diagnostic.severity.toIdeSeverity(),
                    source = "kotlin",
                ),
            )
          }
        }
      }

  private fun diagnosticItem(
      text: CharSequence,
      range: TextRange,
      message: String,
      severity: DiagnosticSeverity,
      source: String,
  ): DiagnosticItem =
      DiagnosticItem(
          message = message,
          code = "",
          range = toIdeRange(text, range),
          source = source,
          severity = severity,
      )

  private fun toIdeRange(text: CharSequence, range: TextRange): Range =
      Range(
          start = offsetToPosition(text, range.startOffset),
          end = offsetToPosition(text, range.endOffset),
      )

  private fun offsetToPosition(text: CharSequence, offset: Int): Position {
    var line = 0
    var column = 0
    val limit = offset.coerceIn(0, text.length)
    for (index in 0 until limit) {
      if (text[index] == '\n') {
        line++
        column = 0
      } else {
        column++
      }
    }
    return Position(line, column)
  }

  private fun KaSeverity.toIdeSeverity(): DiagnosticSeverity =
      when (this) {
        KaSeverity.ERROR -> DiagnosticSeverity.ERROR
        KaSeverity.WARNING -> DiagnosticSeverity.WARNING
        KaSeverity.INFO -> DiagnosticSeverity.INFO
      }
}
