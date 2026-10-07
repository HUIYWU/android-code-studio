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
package com.tom.rv2ide.lsp.kotlin.analysis

import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.kotlin.KotlinClasspathProvider
import com.tom.rv2ide.lsp.kotlin.KotlinLspConnection
import com.tom.rv2ide.lsp.kotlin.KslLogs
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.projects.FileManager
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process Kotlin LSP connection backed by the Kotlin Analysis API engine.
 */
class KotlinAnalysisLspConnection(private val intellijPluginRoot: String) : KotlinLspConnection {

  private var diagnosticsCallback: ((DiagnosticResult) -> Unit)? = null
  private var engine: KotlinAnalysisEngine? = null
  @Volatile private var ready = false

  override val isReady: Boolean
    get() = ready

  private val closing = AtomicBoolean(false)
  private val scheduledJobs = java.util.concurrent.ConcurrentHashMap<Path, ScheduledFuture<*>>()
  private val executor =
      Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "acs-kotlin-analysis")
      }

  override fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) {
    diagnosticsCallback = callback
    KslLogs.info("Analysis API Kotlin backend installed diagnostics callback")
  }

  override fun startServer(classpathProvider: KotlinClasspathProvider): Boolean {
    if (closing.get()) {
      KslLogs.warn("Kotlin Analysis API backend is already shutting down")
      return false
    }
    if (!KotlinAnalysisEngine.isEngineBundled()) {
      KslLogs.error("Kotlin Analysis API engine is not bundled in this build")
      return false
    }
    if (ready && engine != null) {
      return true
    }

    ready = false
    return try {
      val startedEngine = KotlinAnalysisEngine(classpathProvider, intellijPluginRoot)
      if (!startedEngine.start()) {
        startedEngine.close()
        KslLogs.error("Kotlin Analysis API engine failed to start")
        false
      } else {
        engine = startedEngine
        ready = true
        KslLogs.info("Kotlin Analysis API engine started")
        true
      }
    } catch (t: Throwable) {
      KslLogs.error("Failed to start Kotlin Analysis API engine", t)
      false
    }
  }

  override fun sendRequest(method: String, params: JsonObject, callback: (JsonObject?) -> Unit) {
    if (!isReady && method != "initialize") {
      callback.invoke(null)
      return
    }
    if (method == "initialize") {
      if (!isReady) {
        callback.invoke(null)
        return
      }
      callback.invoke(JsonObject().apply { add("capabilities", JsonObject()) })
    } else {
      KslLogs.debug("Analysis API Kotlin backend has no handler for request: {}", method)
      callback.invoke(null)
    }
  }

  override fun sendNotification(method: String, params: JsonObject) {
    if (!isReady) {
      return
    }
    handleNotification(method, params)
  }

  override fun sendNotificationOrThrow(method: String, params: JsonObject) {
    if (!isReady) {
      throw IllegalStateException("Kotlin Analysis API backend is not ready")
    }
    handleNotification(method, params)
  }

  private fun handleNotification(method: String, params: JsonObject) {
    try {
      when (method) {
         "textDocument/didOpen",
         "textDocument/didChange",
         "textDocument/didSave" -> {
            val path = extractFilePath(params)?.normalize() ?: return
            if (!isKotlinSource(path)) {
              return
            }
            scheduleDiagnostics(path)
          }
          "textDocument/didClose" -> {
            val path = extractFilePath(params)?.normalize() ?: return
            scheduledJobs.remove(path)?.cancel(false)
          }
         else -> {
          KslLogs.debug("Analysis API Kotlin backend ignoring notification: {}", method)
        }
      }
    } catch (t: Throwable) {
      KslLogs.warn("Analysis API Kotlin backend failed to handle notification: {}", method, t)
    }
  }

  private fun extractFilePath(params: JsonObject): Path? {
    val uri = params.getAsJsonObject("textDocument")?.get("uri")?.asString ?: return null
    return try {
      Paths.get(URI(uri))
    } catch (e: Exception) {
      KslLogs.debug("Analysis API Kotlin backend cannot parse document uri: {}", e.message)
      null
    }
  }

  private fun isKotlinSource(path: Path): Boolean {
    return path.toString().endsWith(".kt")
  }

  private fun scheduleDiagnostics(path: Path) {
    if (closing.get()) {
      return
    }

    scheduledJobs.remove(path)?.cancel(false)
    try {
      val job =
          executor.schedule(
              {
                scheduledJobs.remove(path)
                publishDiagnostics(path)
              },
              DIAGNOSTICS_DEBOUNCE_MS,
              TimeUnit.MILLISECONDS,
          )
      scheduledJobs[path] = job
    } catch (e: RejectedExecutionException) {
      KslLogs.debug("Analysis task rejected during shutdown: {}", e.message)
    }
  }

  private fun publishDiagnostics(path: Path) {
    if (closing.get()) {
      return
    }
    val activeEngine = engine ?: return
    val callback = diagnosticsCallback ?: return
    val normalizedPath = path.normalize()
    val snapshot = FileManager.getActiveDocumentSnapshot(normalizedPath)
    try {
      val result = activeEngine.analyze(normalizedPath, snapshot) ?: return
      val currentSnapshot = FileManager.getActiveDocumentSnapshot(normalizedPath)
      if (snapshot != null &&
          (currentSnapshot == null || currentSnapshot.revision != snapshot.revision)) {
        return
      }
      callback.invoke(result)
    } catch (t: Throwable) {
      KslLogs.warn("Kotlin Analysis API diagnostics failed for: {}", normalizedPath, t)
    }
  }

  override fun shutdown() {
    if (!closing.compareAndSet(false, true)) {
      return
    }

    ready = false
    scheduledJobs.values.forEach { it.cancel(false) }
    scheduledJobs.clear()

    val activeEngine = engine
    engine = null
    diagnosticsCallback = null

    try {
      if (activeEngine != null) {
        executor.submit { activeEngine.close() }.get(ENGINE_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      }
    } catch (e: TimeoutException) {
      KslLogs.warn("Timed out while closing Kotlin Analysis API engine", e)
    } catch (e: Throwable) {
      KslLogs.warn("Failed to close Kotlin Analysis API engine", e)
    } finally {
      executor.shutdownNow()
      try {
        if (!executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
          KslLogs.warn("Kotlin Analysis API executor did not terminate during shutdown")
        }
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        KslLogs.warn("Interrupted while waiting for Kotlin Analysis API executor shutdown", e)
      }
    }
    KslLogs.info("Kotlin Analysis API backend shutdown")
  }

  private companion object {
    const val DIAGNOSTICS_DEBOUNCE_MS = 400L
    const val ENGINE_CLOSE_TIMEOUT_MS = 5000L
    const val EXECUTOR_CLOSE_TIMEOUT_MS = 1000L
  }
}
