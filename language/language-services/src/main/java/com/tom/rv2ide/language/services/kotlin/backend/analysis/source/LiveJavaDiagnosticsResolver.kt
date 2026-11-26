package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsResolver
import com.tom.rv2ide.language.services.java.diagnostics.JavaPsiDiagnosticsCollector
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar.LiveFileBasedIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicatorBase
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.StandardProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.progress.WrappedProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex

internal fun <T> withJavaDiagnosticProgress(cancelChecker: ICancelChecker, action: () -> T): T {
  val cancellation = object : EmptyProgressIndicatorBase() {
    override fun isCanceled(): Boolean = cancelChecker.isCancelled()
    override fun cancel() = cancelChecker.cancel()
  }
  val progress = object : ProgressIndicator by cancellation, StandardProgressIndicator, WrappedProgressIndicator {
    override fun getOriginalProgressIndicator(): ProgressIndicator = cancellation
  }
  return ProgressManager.getInstance().runProcess(Computable { action() }, progress)
}

internal class LiveJavaDiagnosticsResolver(
    private val environment: LiveAnalysisEnvironment,
) : JavaDiagnosticsResolver {
  override fun diagnostics(
      file: Path,
      cancelChecker: ICancelChecker,
      documentVersion: Int,
      documentRevision: Long,
  ): List<DiagnosticItem>? {
    val trace = IdeLogConfig.shouldLogInfo()
    val startedAt = if (trace) System.nanoTime() else 0L
    var stageStartedAt = startedAt
    var guardWaitUs = 0L
    var readActionWaitUs = 0L
    var analysisReadWaitUs = 0L
    var psiLookupUs = 0L
    var collectUs = 0L
    var diagnosticCount = -1
    var outcome = "failed"
    var stage = "guard"
    return try {
      AnalysisApiGuard.withAccess(cancelChecker) {
        if (trace) {
          val now = System.nanoTime()
          guardWaitUs = (now - stageStartedAt) / 1_000L
          stageStartedAt = now
        }
        stage = "application-read-action"
        ApplicationManager.getApplication().runReadAction(Computable {
          if (trace) {
            val now = System.nanoTime()
            readActionWaitUs = (now - stageStartedAt) / 1_000L
            stageStartedAt = now
          }
          stage = "project-analysis-read"
          environment.project.analysisRead {
            if (trace) {
              val now = System.nanoTime()
              analysisReadWaitUs = (now - stageStartedAt) / 1_000L
              stageStartedAt = now
            }
            stage = "psi-lookup"
            val psiFile = environment.javaFiles.currentFile(file.normalize(), documentVersion, documentRevision)
            if (trace) {
              val now = System.nanoTime()
              psiLookupUs = (now - stageStartedAt) / 1_000L
              stageStartedAt = now
            }
            if (psiFile == null) return@analysisRead null
            val normalized = file.normalize()
            val snapshotChecker = object : ICancelChecker.Default() {
              override fun isCancelled(): Boolean {
                if (super.isCancelled() || cancelChecker.isCancelled()) return true
                val current = FileManager.getActiveDocument(normalized)
                val stale = current == null || synchronized(current) {
                  current.version != documentVersion || current.revision != documentRevision
                }
                if (stale) cancel()
                return stale
              }
            }
            snapshotChecker.abortIfCancelled()
            stage = "java-diagnostics"
            withJavaDiagnosticProgress(snapshotChecker) {
              (FileBasedIndex.getInstance() as LiveFileBasedIndex).withIdQueryCache {
                JavaPsiDiagnosticsCollector.collect(
                    psiFile, normalized, documentVersion, documentRevision, snapshotChecker,
                )
              }
            }.also {
              if (trace) {
                val now = System.nanoTime()
                collectUs = (now - stageStartedAt) / 1_000L
                stageStartedAt = now
              }
            }
          }
        })
      }.also {
        diagnosticCount = it?.size ?: -1
        outcome = if (it == null) "unavailable" else "result"
        stage = "done"
      }
    } catch (error: CancellationException) {
      outcome = "cancelled"
      throw error
    } catch (error: Throwable) {
      KlsLogs.warn(
          "Java codeserver resolver failed: file={}, stage={}, type={}, message={}",
          file, stage, error.javaClass.name, error.message, error,
      )
      throw error
    } finally {
      if (trace) {
        val now = System.nanoTime()
        KlsLogs.info(
            "Java inspect resolver perf: file={}, version={}, revision={}, project={}, outcome={}, stage={}, totalUs={}, guardWaitUs={}, readActionWaitUs={}, analysisReadWaitUs={}, psiLookupUs={}, collectUs={}, stageUs={}, diagnosticCount={}",
            file, documentVersion, documentRevision, System.identityHashCode(environment.project), outcome, stage,
            (now - startedAt) / 1_000L, guardWaitUs, readActionWaitUs, analysisReadWaitUs, psiLookupUs, collectUs,
            (now - stageStartedAt) / 1_000L, diagnosticCount,
        )
      }
    }
  }
}