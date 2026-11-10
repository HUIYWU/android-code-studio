package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLResolutionFacadeService
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.DiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.plus
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.diagnostics.KtPsiDiagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.psi.KtFile

@OptIn(LLFirInternals::class)
internal object KotlinAnalysisDiagnostics {
  fun collectDiagnosticsFor(
      session: KaSession,
      file: KtFile,
      facadeService: LLResolutionFacadeService,
  ): List<DiagnosticItem> {
    val text = file.text
    val lineIndex = LineIndex.from(text)
    val language = KotlinDiagnosticTemplates.currentLanguage()
    val parserItems =
        PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).map { error ->
          item(
              lineIndex,
              error.textRange,
              "PARSER_ERROR",
              KotlinParserMessages.messageFor(error.errorDescription, language),
              DiagnosticSeverity.ERROR,
              "syntax",
          )
        }

    val collected = collectCompilerDiagnostics(session, file, facadeService)

    val items = buildList {
      addAll(parserItems)
      collected.forEach { diagnostic ->
        val ranges = diagnostic.textRanges.ifEmpty { listOf(diagnostic.psiElement.textRange) }
        val message = KotlinDiagnosticTemplates.messageFor(diagnostic, language)
        ranges.forEach { range ->
          add(item(lineIndex, range, diagnostic.factory.name, message, diagnostic.severity.toIdeSeverity(), "kotlin"))
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

  @OptIn(LLFirInternals::class)
  private fun collectCompilerDiagnostics(
      session: KaSession,
      file: KtFile,
      facadeService: LLResolutionFacadeService,
  ): List<KtPsiDiagnostic> {
    val filter =
        DiagnosticCheckerFilter.ONLY_DEFAULT_CHECKERS + DiagnosticCheckerFilter.ONLY_EXTRA_CHECKERS
    return facadeService
        .getResolutionFacade(session.useSiteModule)
        .diagnosticProvider
        .collectDiagnostics(file, filter)
  }

  private fun Severity.toIdeSeverity(): DiagnosticSeverity = when (this) {
    Severity.ERROR -> DiagnosticSeverity.ERROR
    Severity.WARNING,
    Severity.FIXED_WARNING,
    Severity.STRONG_WARNING -> DiagnosticSeverity.WARNING
    Severity.INFO -> DiagnosticSeverity.INFO
  }
}