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
package com.tom.rv2ide.language.services.java.diagnostics

import android.os.Process
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.services.java.compiler.CompileTask
import com.tom.rv2ide.language.services.java.compiler.JavaCompilerService
import com.tom.rv2ide.language.services.java.compiler.SourceFileObject
import com.tom.rv2ide.language.services.java.models.CompilationDocumentState
import com.tom.rv2ide.language.services.java.models.CompilationRequest
import com.tom.rv2ide.language.services.java.providers.DiagnosticsProvider.findDiagnostics
import com.tom.rv2ide.language.services.java.semantic.DocumentStamp
import com.tom.rv2ide.language.services.java.semantic.JavaModuleScope
import com.tom.rv2ide.language.services.java.semantic.JavaModuleScopes
import com.tom.rv2ide.language.services.java.utils.CancelChecker
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.progress.ProgressManager
import com.tom.rv2ide.progress.ProgressManager.Companion.abortIfCancelled
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import java.nio.file.Path
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Speculative diagnostics engine for one Java language server.
 *
 * The analyzer owns execution and caching only: semantic identity comes from [JavaModuleScope], the
 * reusable compiler comes from the module's scope, and the cache policy lives in
 * [DiagnosticsLedger].
 *
 * Result freshness is decided by identity, not by counters: a cached result is valid only while the
 * document stamp and the module epoch still match, and a computed result is recorded with the stamp
 * it was computed from. A later document edit or environment change therefore cannot reuse it.
 */
internal class JavaDiagnosticsAnalyzer(
    private val ledger: DiagnosticsLedger = DiagnosticsLedger(),
) {

  private val yieldTicket = YieldTicket()

  @Volatile private var analyzing = false

  @Volatile private var analyzingWorker: DiagnosticsWork? = null

  companion object {
    private val log = LoggerFactory.getLogger(JavaDiagnosticsAnalyzer::class.java)
  }

  fun analyze(file: Path): DiagnosticResult {

    val module =
        IProjectManager.getInstance().getWorkspace()?.findModuleForFile(file, false)
            ?: return DiagnosticResult.NO_UPDATE
    val scope = JavaModuleScopes.forModule(module)
    val compiler = scope.compiler()
    val epoch = scope.epoch

    abortIfCancelled()

    if (IdeLogConfig.shouldLogDebug()) {
      log.debug("Analyzing: {}", file)
    }

    val activeSnapshot = FileManager.getActiveDocumentSnapshot(file)
    val stamp = activeSnapshot?.let { DocumentStamp(it.version, it.revision) }
    val modifiedAt = activeSnapshot?.modified ?: FileManager.getLastModified(file)

    ledger.find(file, stamp, epoch, modifiedAt)?.let { cached ->
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
            "Diagnostics cache-hit file={} version={} revision={} epoch={} resultKind={} diagnosticCount={}",
            file,
            activeSnapshot?.version ?: DiagnosticResult.UNKNOWN_DOCUMENT_VERSION,
            activeSnapshot?.revision ?: DiagnosticResult.UNKNOWN_DOCUMENT_REVISION,
            epoch,
            if (cached == DiagnosticResult.NO_UPDATE) "no-update" else "result",
            cached.diagnostics.size,
        )
      }
      return cached
    }

    if (analyzing) {
      analyzingWorker?.let { worker ->
        if (IdeLogConfig.shouldLogDebug()) {
          log.debug("Cancelling currently analyzing worker...")
        }
        ProgressManager.instance.cancel(worker)
        analyzingWorker = null
      }
    }

    val startedAt = System.currentTimeMillis()
    analyzing = true

    val worker =
        DiagnosticsWork(compiler, file, scope, epoch).also {
          analyzingWorker = it
          it.start()
          it.join()
        }

    return worker.result.also { result ->
      analyzingWorker = null
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
            "Diagnostics terminal file={} version={} revision={} epoch={} resultKind={} diagnosticCount={} durationMs={}",
            file,
            result.documentVersion,
            result.documentRevision,
            epoch,
            if (result == DiagnosticResult.NO_UPDATE) "no-update" else "result",
            result.diagnostics.size,
            System.currentTimeMillis() - startedAt,
        )
      }
      if (result != DiagnosticResult.NO_UPDATE) {
        val resultStamp =
            if (result.documentVersion >= 0 && result.documentRevision >= 0L) {
              DocumentStamp(result.documentVersion, result.documentRevision)
            } else {
              stamp
            }
        ledger.record(file, resultStamp, epoch, Instant.now(), result)
      }
    }
  }

  fun isAnalyzing(): Boolean = analyzing

  /** Consumes a retry request only when the completed analysis yielded with [DiagnosticResult.NO_UPDATE]. */
  fun consumeYieldRetry(result: DiagnosticResult): Boolean = yieldTicket.consumeOnce(result)

  fun cancel() {
    analyzingWorker?.cancel()
  }

  /** Drops every cached result for [file], for example when the document is closed. */
  fun forget(file: Path) {
    ledger.forget(file)
  }

  /** Drops all cached results, used when the workspace environment is reset. */
  fun clear() {
    ledger.clear()
  }

  private fun computeDiagnostics(
      file: Path,
      task: CompileTask,
      documentVersion: Int,
      documentRevision: Long,
  ): DiagnosticResult {
    val result =
        if (!isTaskValid(task)) {
          // Do not use Collections.emptyList(): callers access the returned list.
          if (IdeLogConfig.shouldLogInfo()) {
            log.info("Using cached diagnostics")
          }
          ledger.findByRevision(file, documentRevision) ?: DiagnosticResult.NO_UPDATE
        } else {
          DiagnosticResult(
              file,
              findDiagnostics(task, file).sortedBy { it.range },
              documentVersion = documentVersion,
              documentRevision = documentRevision,
          )
        }
    return result.also {
      if (IdeLogConfig.shouldLogDebug()) {
        log.debug("Analyze file completed. Found {} diagnostic items", result.diagnostics.size)
      }
    }
  }

  private fun isTaskValid(task: CompileTask?): Boolean {
    abortIfCancelled()
    return task?.task != null && task.roots != null && task.roots.size > 0
  }

  private inner class DiagnosticsWork(
      val compiler: JavaCompilerService,
      val file: Path,
      val scope: JavaModuleScope,
      val epoch: Long,
  ) : Thread("JavaDiagnosticsWorker") {

    var result: DiagnosticResult = DiagnosticResult.NO_UPDATE

    fun cancel() {
      ProgressManager.instance.cancel(this)
    }

    override fun run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
      result =
          try {
            val activeSnapshot = FileManager.getActiveDocumentSnapshot(file)
            val contents = activeSnapshot?.content ?: FileManager.getDocumentContents(file)
            val modified = activeSnapshot?.modified ?: FileManager.getLastModified(file)
            val documentVersion =
                activeSnapshot?.version ?: DiagnosticResult.UNKNOWN_DOCUMENT_VERSION
            val documentRevision =
                activeSnapshot?.revision ?: DiagnosticResult.UNKNOWN_DOCUMENT_REVISION
            // Diagnostics are speculative background work. Yield before entering javac when a
            // foreground semantic request is active for this module, rather than waiting behind it
            // and competing for the same reusable compiler.
            if (scope.hasForegroundWork) {
              yieldTicket.markYielded()
              if (IdeLogConfig.shouldLogInfo()) {
                log.info("Analyze yielded to foreground semantic request file={}", file)
              }
              return
            }
            compiler
                .compile(
                    CompilationRequest(
                        sources = listOf(SourceFileObject(file, contents, modified)),
                        documentState =
                            CompilationDocumentState(
                                file,
                                contents,
                                documentVersion,
                                documentRevision,
                            ),
                    )
                )
                .get { task ->
                  if (
                      documentRevision != DiagnosticResult.UNKNOWN_DOCUMENT_REVISION &&
                          FileManager.getActiveDocumentSnapshot(file)?.revision != documentRevision
                  ) {
                    DiagnosticResult.NO_UPDATE
                  } else if (!JavaModuleScopes.isCurrent(scope, epoch)) {
                    if (IdeLogConfig.shouldLogInfo()) {
                      log.info(
                          "Analyze skipped after compile due to environment change file={} requestedEpoch={} currentEpoch={}",
                          file,
                          epoch,
                          scope.epoch,
                      )
                    }
                    DiagnosticResult.NO_UPDATE
                  } else {
                    computeDiagnostics(file, task, documentVersion, documentRevision)
                  }
                }
          } catch (err: Throwable) {
            if (CancelChecker.isCancelled(err)) {
              // Cancellation is expected while foreground work preempts analysis; keep quiet.
            } else {
              if (IdeLogConfig.shouldLogWarn()) {
                log.warn("Unable to analyze file", err)
                log.warn(
                    "Java analyze failure file={} type={} message={} causeType={} causeMessage={} thread={} compilerHash={} currentContextPresent={} synchronizedTaskPresent={}",
                    file,
                    err.javaClass.name,
                    err.message,
                    err.cause?.javaClass?.name,
                    err.cause?.message,
                    Thread.currentThread().name,
                    System.identityHashCode(compiler),
                    compiler.compiler.currentContext != null,
                    compiler.getSynchronizedTask() != null,
                )
              }
            }
            DiagnosticResult.NO_UPDATE
          } finally {
            analyzing = false
          }
    }
  }
}