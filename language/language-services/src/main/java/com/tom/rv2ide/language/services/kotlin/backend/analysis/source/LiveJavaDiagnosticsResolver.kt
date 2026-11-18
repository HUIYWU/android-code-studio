package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.language.services.interop.JavaDiagnosticsResolver
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.JavaErrorCollector
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.errors.JavaCompilationError
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.errors.JavaErrorHighlightType
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementVisitor
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal class LiveJavaDiagnosticsResolver(
    private val environment: LiveAnalysisEnvironment,
) : JavaDiagnosticsResolver {
  override fun diagnostics(file: Path, cancelChecker: ICancelChecker): List<DiagnosticItem>? {
    val startedAt = System.nanoTime()
    var stage = "begin"
    KlsLogs.info(
        "Java codeserver resolver begin: file={}, project={}, thread={}",
        file,
        System.identityHashCode(environment.project),
        Thread.currentThread().name,
    )
    return try {
      stage = "analysis-api-access"
      AnalysisApiGuard.withAccess(cancelChecker) {
        stage = "application-read-action"
        ApplicationManager.getApplication().runReadAction(Computable {
          stage = "project-analysis-read"
          environment.project.analysisRead {
            stage = "psi-lookup"
            val psiFile = environment.javaFiles.currentFile(file.normalize()) ?: run {
              KlsLogs.warn("Java codeserver resolver PSI missing: file={}", file)
              return@analysisRead null
            }
            stage = "psi-ready"
            KlsLogs.info(
                "Java codeserver resolver PSI ready: file={}, psi={}, textLength={}",
                file,
                System.identityHashCode(psiFile),
                psiFile.textLength,
            )
            cancelChecker.abortIfCancelled()
            stage = "collector"
            collect(psiFile, cancelChecker)

          }
        })
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      KlsLogs.warn(
          "Java codeserver resolver failed: file={}, stage={}, durationUs={}, type={}, message={}",
          file,
          stage,
          (System.nanoTime() - startedAt) / 1_000L,
          error.javaClass.name,
          error.message,
          error,
      )
      throw error
    }.also { result ->
      KlsLogs.info(
          "Java codeserver resolver end: file={}, result={}, diagnostics={}, durationUs={}",
          file,
          if (result == null) "unavailable" else "success",
          result?.size ?: 0,
          (System.nanoTime() - startedAt) / 1_000L,
      )
    }
  }

  private fun collect(
      file: PsiJavaFile,
      cancelChecker: ICancelChecker,
  ): List<DiagnosticItem> {
    val startedAt = System.nanoTime()
    KlsLogs.info(
        "Java codeserver collector begin: file={}, psi={}, textLength={}",
        file.virtualFile?.path,
        System.identityHashCode(file),
        file.textLength,
    )
    return try {
      val lineIndex = LineIndex.from(file.text)
      val result = ArrayList<DiagnosticItem>()
      val collector = JavaErrorCollector(file) { error ->
        cancelChecker.abortIfCancelled()
        result += toDiagnostic(error, lineIndex)
      }
      file.accept(object : JavaRecursiveElementVisitor() {
        override fun visitElement(element: PsiElement) {
          cancelChecker.abortIfCancelled()
          collector.processElement(element)
          super.visitElement(element)
        }
      })
      result.sortedWith(DiagnosticItem.START_COMPARATOR).also { diagnostics ->
        KlsLogs.info(
            "Java codeserver collector end: file={}, visitedDiagnostics={}, durationUs={}",
            file.virtualFile?.path,
            diagnostics.size,
            (System.nanoTime() - startedAt) / 1_000L,
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      KlsLogs.warn(
          "Java codeserver collector failed: file={}, durationUs={}, type={}, message={}",
          file.virtualFile?.path,
          (System.nanoTime() - startedAt) / 1_000L,
          error.javaClass.name,
          error.message,
          error,
      )
      throw error
    }
  }

  private fun toDiagnostic(
      error: JavaCompilationError<*, *>,
      lineIndex: LineIndex,
  ): DiagnosticItem {
    val anchor = error.anchor()
    val anchorRange = requireNotNull(anchor.textRange)
    val relativeRange = error.range()
    val startOffset =
        if (relativeRange == null) anchorRange.startOffset else anchorRange.startOffset + relativeRange.startOffset
    val endOffset =
        if (relativeRange == null) anchorRange.endOffset else anchorRange.startOffset + relativeRange.endOffset
    return DiagnosticItem(
        message = error.description().toString(),
        code = error.kind().key(),
        range = Range(lineIndex.indexToPosition(startOffset), lineIndex.indexToPosition(endOffset)),
        source = "java-codeserver",
        severity = error.highlightType().toSeverity(),
    )
  }

  private fun JavaErrorHighlightType.toSeverity(): DiagnosticSeverity =
      when (this) {
        JavaErrorHighlightType.PENDING_REF -> DiagnosticSeverity.INFO
        JavaErrorHighlightType.ERROR,
        JavaErrorHighlightType.FILE_LEVEL_ERROR,
        JavaErrorHighlightType.WRONG_REF,
        JavaErrorHighlightType.UNHANDLED_EXCEPTION,
        -> DiagnosticSeverity.ERROR
        else -> DiagnosticSeverity.ERROR
      }
}