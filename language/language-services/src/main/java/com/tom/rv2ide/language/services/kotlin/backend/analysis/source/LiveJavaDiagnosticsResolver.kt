package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsResolver
import com.tom.rv2ide.language.services.java.diagnostics.JavaPsiDiagnosticsCollector
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable

internal class LiveJavaDiagnosticsResolver(
    private val environment: LiveAnalysisEnvironment,
) : JavaDiagnosticsResolver {
  override fun diagnostics(
      file: Path,
      cancelChecker: ICancelChecker,
      documentVersion: Int,
      documentRevision: Long,
  ): List<DiagnosticItem>? {
    var stage = "begin"
    return try {
      AnalysisApiGuard.withAccess(cancelChecker) {
        stage = "application-read-action"
        ApplicationManager.getApplication().runReadAction(Computable {
          stage = "project-analysis-read"
          environment.project.analysisRead {
            stage = "psi-lookup"
            val psiFile = environment.javaFiles.currentFile(file.normalize(), documentVersion, documentRevision)
                ?: return@analysisRead null
            cancelChecker.abortIfCancelled()
            stage = "java-diagnostics"
            JavaPsiDiagnosticsCollector.collect(
                psiFile, file.normalize(), documentVersion, documentRevision, cancelChecker,
            )
          }
        })
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      KlsLogs.warn(
          "Java codeserver resolver failed: file={}, stage={}, type={}, message={}",
          file, stage, error.javaClass.name, error.message, error,
      )
      throw error
    }
  }
}