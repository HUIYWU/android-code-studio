/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java.providers
import android.os.Process
import com.tom.rv2ide.common.logging.IdeLogConfig


import com.tom.rv2ide.lsp.java.JavaSemanticSession
import com.tom.rv2ide.lsp.java.JavaSemanticSessions
import com.tom.rv2ide.lsp.java.compiler.CompileTask
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.java.providers.DiagnosticsProvider.findDiagnostics
import com.tom.rv2ide.lsp.java.utils.CancelChecker
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.progress.ProgressManager
import com.tom.rv2ide.progress.ProgressManager.Companion.abortIfCancelled
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Code analyzer for java source code.
 *
 * @author Akash Yadav
 */
class JavaDiagnosticProvider {

  private val analyzeTimestamps = mutableMapOf<Path, Instant>()
  private val cachedDiagnostics = mutableMapOf<Path, DiagnosticResult>()
  private val analyzeGeneration = AtomicLong(0)
  private val interactiveYieldState = DiagnosticInteractiveYieldState()
  private var analyzing = AtomicBoolean(false)
  private var analyzingThread: AnalyzingThread? = null

  companion object {

    private val log = LoggerFactory.getLogger(JavaDiagnosticProvider::class.java)
  }

  fun analyze(file: Path): DiagnosticResult {

    val module =
        IProjectManager.getInstance().getWorkspace()?.findModuleForFile(file, false)
            ?: return DiagnosticResult.NO_UPDATE
    val session = JavaSemanticSessions.forModule(module)
    val compiler = session.compiler()
    val environmentGeneration = session.environmentGeneration

    abortIfCancelled()

    if (IdeLogConfig.shouldLogDebug()) {
      log.debug("Analyzing: {}", file)
    }

    val activeSnapshot = FileManager.getActiveDocumentSnapshot(file)
    val modifiedAt = activeSnapshot?.modified ?: FileManager.getLastModified(file)
    val analyzedAt = analyzeTimestamps[file]
    val cachedResult = cachedDiagnostics[file]
    val cacheMatchesDocument =
        activeSnapshot == null ||
            (cachedResult?.documentVersion == activeSnapshot.version &&
                cachedResult.documentRevision == activeSnapshot.revision)

    if (analyzedAt?.isAfter(modifiedAt) == true && cacheMatchesDocument) {
      val result = cachedResult ?: DiagnosticResult.NO_UPDATE
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
            "Diagnostics cache-hit file={} version={} revision={} environmentGeneration={} resultKind={} diagnosticCount={}",
            file,
            activeSnapshot?.version ?: DiagnosticResult.UNKNOWN_DOCUMENT_VERSION,
            activeSnapshot?.revision ?: DiagnosticResult.UNKNOWN_DOCUMENT_REVISION,
            environmentGeneration,
            if (result == DiagnosticResult.NO_UPDATE) "no-update" else "result",
            result.diagnostics.size,
        )
      }
      return result
    }

    analyzingThread?.let { analyzingThread ->
      if (analyzing.get()) {
        if (IdeLogConfig.shouldLogDebug()) {
          log.debug("Cancelling currently analyzing thread...")
        }
        ProgressManager.instance.cancel(analyzingThread)
        this.analyzingThread = null
      }
    }

    val requestedGeneration = analyzeGeneration.incrementAndGet()
    val startedAt = System.currentTimeMillis()
    analyzing.set(true)

    val analyzingThread =
        AnalyzingThread(compiler, file, requestedGeneration, session, environmentGeneration).also {
          analyzingThread = it
          it.start()
          it.join()
        }

    return analyzingThread.result.also { result ->
      this.analyzingThread = null
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
            "Diagnostics terminal file={} version={} revision={} environmentGeneration={} requestedGeneration={} currentGeneration={} resultKind={} diagnosticCount={} durationMs={}",
            file,
            result.documentVersion,
            result.documentRevision,
            environmentGeneration,
            requestedGeneration,
            analyzeGeneration.get(),
            if (result == DiagnosticResult.NO_UPDATE) "no-update" else "result",
            result.diagnostics.size,
            System.currentTimeMillis() - startedAt,
        )
      }
      if (requestedGeneration == analyzeGeneration.get() && result != DiagnosticResult.NO_UPDATE) {
        cachedDiagnostics[file] = result
        analyzeTimestamps[file] = Instant.now()
      } else if (requestedGeneration != analyzeGeneration.get() && IdeLogConfig.shouldLogInfo()) {
        log.info(
          "Analyze cache update skipped due to newer request requestedGeneration={} currentGeneration={} file={}",
          requestedGeneration,
          analyzeGeneration.get(),
          file,
        )
      }
    }
  }

  fun isAnalyzing(): Boolean {
    return this.analyzing.get()
  }

  /** Consumes a retry request only when the completed analysis yielded with [DiagnosticResult.NO_UPDATE]. */
  fun consumeInteractiveYield(result: DiagnosticResult): Boolean =
      interactiveYieldState.consumeRetryAfter(result)

  fun cancel() {
    this.analyzingThread?.cancel()
  }

  fun clearTimestamp(file: Path) {
    analyzeTimestamps.remove(file)
    cachedDiagnostics.remove(file)
  }

  private fun doAnalyze(
      file: Path,
      task: CompileTask,
      documentVersion: Int,
      documentRevision: Long,
  ): DiagnosticResult {
    val result =
        if (!isTaskValid(task)) {
          // Do not use Collections.emptyList ()
          // The returned list is accessed and the list returned by Collections.emptyList()
          // throws exception when trying to access.
          if (IdeLogConfig.shouldLogInfo()) {
            log.info("Using cached diagnostics")
          }
          cachedDiagnostics[file]?.takeIf { cached ->
            documentRevision == DiagnosticResult.UNKNOWN_DOCUMENT_REVISION ||
                cached.documentRevision == documentRevision
          } ?: DiagnosticResult.NO_UPDATE
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

  private inner class AnalyzingThread(
      val compiler: JavaCompilerService,
      val file: Path,
      val requestedGeneration: Long,
      val session: JavaSemanticSession,
      val environmentGeneration: Long,
  ) : Thread("JavaAnalyzerThread") {

    var result: DiagnosticResult = DiagnosticResult.NO_UPDATE

    fun cancel() {
      ProgressManager.instance.cancel(this)
    }

    override fun run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
      result =
          try {
                if (requestedGeneration != analyzeGeneration.get()) {
                  if (IdeLogConfig.shouldLogInfo()) {
                    log.info(
                      "Analyze skipped before compile due to newer request requestedGeneration={} currentGeneration={} file={}",
                      requestedGeneration,
                      analyzeGeneration.get(),
                      file,
                    )
                  }
                  return
                }
                val activeSnapshot = FileManager.getActiveDocumentSnapshot(file)
                val contents = activeSnapshot?.content ?: FileManager.getDocumentContents(file)
                val modified = activeSnapshot?.modified ?: FileManager.getLastModified(file)
                val documentVersion =
                    activeSnapshot?.version ?: DiagnosticResult.UNKNOWN_DOCUMENT_VERSION
                val documentRevision =
                    activeSnapshot?.revision ?: DiagnosticResult.UNKNOWN_DOCUMENT_REVISION
                if (requestedGeneration != analyzeGeneration.get()) {
                  if (IdeLogConfig.shouldLogInfo()) {
                    log.info(
                        "Analyze skipped after snapshot due to newer request requestedGeneration={} currentGeneration={} file={}",
                        requestedGeneration,
                        analyzeGeneration.get(),
                        file,
                    )
                  }
                  return
                }
                // Diagnostics are speculative background work. Yield before entering javac when a
                // foreground semantic request is active for this module, rather than waiting
                // behind it and competing for the same reusable compiler.
                if (session.hasInteractiveRequest) {
                  interactiveYieldState.markYielded()
                  if (IdeLogConfig.shouldLogInfo()) {
                    log.info("Analyze yielded to interactive semantic request file={}", file)
                  }
                  return
                }
                compiler
                    .compile(
                        com.tom.rv2ide.lsp.java.models.CompilationRequest(
                            sources =
                                listOf(
                                    com.tom.rv2ide.lsp.java.compiler.SourceFileObject(
                                        file,
                                        contents,
                                        modified,
                                    )
                                ),
                            documentState =
                                com.tom.rv2ide.lsp.java.models.CompilationDocumentState(
                                    file,
                                    contents,
                                    documentVersion,
                                    documentRevision,
                                ),
                        )
                    )
                    .get { task ->
                      if (requestedGeneration != analyzeGeneration.get()) {
                        if (IdeLogConfig.shouldLogInfo()) {
                          log.info(
                            "Analyze skipped after compile due to newer request requestedGeneration={} currentGeneration={} file={}",
                            requestedGeneration,
                            analyzeGeneration.get(),
                            file,
                          )
                        }
                        DiagnosticResult.NO_UPDATE
                      } else if (
                          documentRevision != DiagnosticResult.UNKNOWN_DOCUMENT_REVISION &&
                              FileManager.getActiveDocumentSnapshot(file)?.revision != documentRevision
                      ) {
                        DiagnosticResult.NO_UPDATE
                      } else if (
                          !JavaSemanticSessions.isCurrent(session, environmentGeneration)
                      ) {
                        if (IdeLogConfig.shouldLogInfo()) {
                          log.info(
                              "Analyze skipped after compile due to environment change file={} requestedEnvironmentGeneration={} currentEnvironmentGeneration={}",
                              file,
                              environmentGeneration,
                              session.environmentGeneration,
                          )
                        }
                        DiagnosticResult.NO_UPDATE
                      } else {
                        doAnalyze(file, task, documentVersion, documentRevision)
                      }
                    }
              } catch (err: Throwable) {
                if (CancelChecker.isCancelled(err)) {
                  // Cancellation is expected during interactive editing; keep quiet.
                } else {
                  if (IdeLogConfig.shouldLogWarn()) {
                    log.warn("Unable to analyze file", err)
                    log.warn(
                      "Java analyze failure file={} type={} message={} causeType={} causeMessage={} thread={} compilerHash={} currentContextPresent={} synchronizedTaskPresent={} requestedGeneration={} currentGeneration={}",
                      file,
                      err.javaClass.name,
                      err.message,
                      err.cause?.javaClass?.name,
                      err.cause?.message,
                      Thread.currentThread().name,
                      System.identityHashCode(compiler),
                      compiler.compiler.currentContext != null,
                      compiler.getSynchronizedTask() != null,
                      requestedGeneration,
                      analyzeGeneration.get(),
                    )
                  }
                }
                DiagnosticResult.NO_UPDATE
              } finally {
                analyzing.set(false)
              }
    }
  }
}

/**
 * One-shot handoff from speculative diagnostics to the server debounce scheduler.
 *
 * The provider marks this only after yielding to a foreground semantic lease. The server consumes it
 * only for [DiagnosticResult.NO_UPDATE], so ordinary no-update results cannot create a retry loop.
 */
internal class DiagnosticInteractiveYieldState {
  private val yielded = AtomicBoolean(false)

  fun markYielded() {
    yielded.set(true)
  }

  fun consumeRetryAfter(result: DiagnosticResult): Boolean =
      result == DiagnosticResult.NO_UPDATE && yielded.getAndSet(false)
}
