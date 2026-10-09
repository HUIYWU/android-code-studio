/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendState
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentKind
import com.tom.rv2ide.language.services.kotlin.document.kotlinDocumentKind
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.projects.FileManager
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** In-process Kotlin Analysis backend and its replaceable standalone runtime. */
class KotlinAnalysisBackendConnection internal constructor(
    private val runtimeFactory: KotlinAnalysisRuntimeFactory,
    private val activeDocuments: () -> List<Path> = { FileManager.getActiveDocumentFiles().toList() },
) : KotlinBackendConnection {

  constructor(intellijPluginRoot: String) : this(
      KotlinAnalysisRuntimeFactory { provider ->
        KotlinStandaloneAnalysisRuntime(provider, intellijPluginRoot)
      },
  )

  @Volatile private var queueThread: Thread? = null
  private val analysisQueue =
      ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "acs-kotlin-analysis").apply {
          isDaemon = true
          queueThread = this
        }
      }.apply { removeOnCancelPolicy = true }
  private val stateRef = AtomicReference(KotlinBackendState.NEW)
  private val generationCounter = AtomicLong(0L)
  private val scheduledJobs = ConcurrentHashMap<Path, ScheduledFuture<*>>()
  private var runtime: KotlinAnalysisRuntime? = null
  private var diagnosticsCallback: ((DiagnosticResult) -> Unit)? = null
  @Volatile private var initialized = false

  override val state: KotlinBackendState
    get() = stateRef.get()

  override val isReady: Boolean
    get() = state == KotlinBackendState.READY

  override val generation: Long
    get() = generationCounter.get()

  override val diagnosticChannel: String
    get() = DiagnosticResult.CHANNEL_KOTLIN

  override val isInitialized: Boolean
    get() = initialized

  override fun markInitialized() {
    executeOnAnalysisQueue {
      if (isReady) initialized = true
    }
  }

  override fun markInitializationFailed() {
    executeOnAnalysisQueue { initialized = false }
  }

  override fun supportsDocument(path: Path): Boolean =
      path.kotlinDocumentKind() == KotlinDocumentKind.KOTLIN_SOURCE

  override fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) {
    executeOnAnalysisQueue { diagnosticsCallback = callback }
    KlsLogs.info("Analysis API Kotlin backend installed diagnostics callback")
  }

  override fun startBackend(classpathProvider: KotlinProjectClasspathProvider): Boolean =
      executeOnAnalysisQueue {
        if (state == KotlinBackendState.CLOSED) {
          KlsLogs.warn("Kotlin Analysis API backend is already closed")
          return@executeOnAnalysisQueue false
        }
        if (state == KotlinBackendState.READY && runtime != null) {
          return@executeOnAnalysisQueue true
        }

        stateRef.set(KotlinBackendState.STARTING)
        val startedRuntime = createRuntime(classpathProvider)
        if (startedRuntime == null) {
          stateRef.set(KotlinBackendState.FAILED)
          return@executeOnAnalysisQueue false
        }

        runtime = startedRuntime
        initialized = false
        generationCounter.incrementAndGet()
        stateRef.set(KotlinBackendState.READY)
        KlsLogs.info("Kotlin Analysis API backend started, generation={}", generation)
        true
      } ?: false

  override fun refreshEnvironment(classpathProvider: KotlinProjectClasspathProvider): Boolean =
      executeOnAnalysisQueue {
        if (state == KotlinBackendState.CLOSED) {
          return@executeOnAnalysisQueue false
        }
        if (state != KotlinBackendState.READY && state != KotlinBackendState.FAILED) {
          return@executeOnAnalysisQueue false
        }

        stateRef.set(KotlinBackendState.REFRESHING)
        cancelScheduledDiagnostics()
        val wasInitialized = initialized
        val oldRuntime = runtime
        val newRuntime = createRuntime(classpathProvider)
        if (newRuntime == null) {
          stateRef.set(if (oldRuntime != null) KotlinBackendState.READY else KotlinBackendState.FAILED)
          initialized = wasInitialized
          KlsLogs.error("Kotlin Analysis API backend environment refresh failed; keeping current runtime")
          if (initialized && oldRuntime != null) {
            rescheduleActiveDocuments()
          }
          return@executeOnAnalysisQueue false
        }

        runtime = newRuntime
        initialized = wasInitialized
        generationCounter.incrementAndGet()
        stateRef.set(KotlinBackendState.READY)
        closeRuntime(oldRuntime)
        if (initialized) {
          rescheduleActiveDocuments()
        }
        KlsLogs.info("Kotlin Analysis API backend environment refreshed, generation={}", generation)
        true
      } ?: false

  override fun initialize(params: JsonObject, callback: (JsonObject?) -> Unit) {
    val result = executeOnAnalysisQueue {
      if (!isReady) return@executeOnAnalysisQueue null
      JsonObject().apply {
        add("capabilities", JsonObject().apply { addProperty("textDocumentSync", 1) })
      }
    }
    if (result != null) {
      executeOnAnalysisQueue { initialized = true }
    }
    callback(result)
  }

  override fun sendRequest(
       method: String,
       params: JsonObject,
       callback: (JsonObject?) -> Unit,
   ) {
    val result = executeOnAnalysisQueue {
      when {
        method == "initialize" && isReady ->
            JsonObject().apply {
              add("capabilities", JsonObject().apply { addProperty("textDocumentSync", 1) })
            }
        else -> null
      }
    }
    callback(result)
  }

  override fun sendNotification(method: String, params: JsonObject) {
    executeOnAnalysisQueue {
      if (isReady) handleNotification(method, params)
    }
  }

  override fun sendNotificationOrThrow(method: String, params: JsonObject) {
    val handled = executeOnAnalysisQueue {
      if (!isReady) return@executeOnAnalysisQueue false
      handleNotification(method, params)
      true
    }
    check(handled == true) { "Kotlin Analysis API backend is not ready" }
  }

  private fun handleNotification(method: String, params: JsonObject) {
    try {
      when (method) {
        "textDocument/didOpen",
        "textDocument/didChange",
        "textDocument/didSave" -> {
          val path = extractFilePath(params)?.normalize() ?: return
          if (supportsDocument(path)) scheduleDiagnostics(path)
        }
        "textDocument/didClose" -> {
          val path = extractFilePath(params)?.normalize() ?: return
          scheduledJobs.remove(path)?.cancel(false)
          diagnosticsCallback?.invoke(
              DiagnosticResult(
                  path,
                  emptyList(),
                  DiagnosticResult.CHANNEL_KOTLIN,
                  DiagnosticResult.UNKNOWN_DOCUMENT_VERSION,
                  DiagnosticResult.UNKNOWN_DOCUMENT_REVISION,
                  generation,
              )
          )
        }
        else -> KlsLogs.debug("Analysis API Kotlin backend ignoring notification: {}", method)
      }
    } catch (t: Throwable) {
      KlsLogs.warn("Analysis API Kotlin backend failed to handle notification: {}", method, t)
    }
  }

  private fun extractFilePath(params: JsonObject): Path? {
    val uri = params.getAsJsonObject("textDocument")?.get("uri")?.asString ?: return null
    return try {
      Paths.get(URI(uri))
    } catch (e: Exception) {
      KlsLogs.debug("Analysis API Kotlin backend cannot parse document uri: {}", e.message)
      null
    }
  }

  private fun rescheduleActiveDocuments() {
    activeDocuments()
        .filter(::supportsDocument)
        .forEach(::scheduleDiagnostics)
  }

  private fun scheduleDiagnostics(path: Path) {
    if (!isReady || !initialized) {
      KlsLogs.warn("Kotlin diagnostics schedule skipped: file={} ready={} initialized={}", path, isReady, initialized)
      return
    }
    KlsLogs.warn("Kotlin diagnostics scheduled: file={}", path)
    scheduledJobs.remove(path)?.cancel(false)
    try {
      val job =
          analysisQueue.schedule(
              {
                scheduledJobs.remove(path)
                publishDiagnostics(path)
              },
              DIAGNOSTICS_DEBOUNCE_MS,
              TimeUnit.MILLISECONDS,
          )
      scheduledJobs[path] = job
    } catch (e: RejectedExecutionException) {
      KlsLogs.debug("Analysis task rejected during shutdown: {}", e.message)
    }
  }

  private fun publishDiagnostics(path: Path) {
    if (!isReady || !initialized) {
      KlsLogs.warn("Kotlin diagnostics execution skipped: file={} ready={} initialized={}", path, isReady, initialized)
      return
    }
    val callback = diagnosticsCallback ?: run {
      KlsLogs.warn("Kotlin diagnostics execution skipped without callback: file={}", path)
      return
    }
    val requestGeneration = generation
    val normalizedPath = path.normalize()
    val snapshot = FileManager.getActiveDocumentSnapshot(normalizedPath) ?: run {
      KlsLogs.warn("Kotlin diagnostics execution skipped without snapshot: file={}", normalizedPath)
      return
    }
    val activeRuntime = runtime ?: run {
      KlsLogs.warn("Kotlin diagnostics execution skipped without runtime: file={}", normalizedPath)
      return
    }
    KlsLogs.warn(
        "Kotlin diagnostics analyzing: file={} version={} revision={} generation={}",
        normalizedPath,
        snapshot.version,
        snapshot.revision,
        requestGeneration,
    )
    try {
      val result = activeRuntime.analyze(normalizedPath, snapshot) ?: run {
        KlsLogs.warn("Kotlin diagnostics analysis returned null: file={}", normalizedPath)
        return
      }
      if (requestGeneration != generation || state != KotlinBackendState.READY) {
        KlsLogs.warn(
            "Kotlin diagnostics result dropped by backend state: file={} requestGeneration={} currentGeneration={} state={}",
            normalizedPath,
            requestGeneration,
            generation,
            state,
        )
        return
      }
      val currentSnapshot = FileManager.getActiveDocumentSnapshot(normalizedPath)
      if (currentSnapshot == null || snapshot.version != currentSnapshot.version ||
          snapshot.revision != currentSnapshot.revision) {
        KlsLogs.warn(
            "Kotlin diagnostics result dropped as stale: file={} requestVersion={} currentVersion={} requestRevision={} currentRevision={}",
            normalizedPath,
            snapshot.version,
            currentSnapshot?.version,
            snapshot.revision,
            currentSnapshot?.revision,
        )
        return
      }
      KlsLogs.warn(
          "Kotlin diagnostics result ready: file={} version={} revision={} count={}",
          normalizedPath,
          snapshot.version,
          snapshot.revision,
          result.diagnostics.size,
      )
      callback.invoke(
          result.copy(
              channel = DiagnosticResult.CHANNEL_KOTLIN,
              documentVersion = snapshot.version,
              documentRevision = snapshot.revision,
              backendGeneration = requestGeneration,
          )
      )
    } catch (t: Throwable) {
      KlsLogs.warn("Kotlin Analysis API diagnostics failed for: {}", normalizedPath, t)
    }
  }

  override fun close() {
    if (state == KotlinBackendState.CLOSED) return
    executeOnAnalysisQueue {
      if (state == KotlinBackendState.CLOSED) return@executeOnAnalysisQueue
      stateRef.set(KotlinBackendState.CLOSED)
      cancelScheduledDiagnostics()
      initialized = false
      val activeRuntime = runtime
      runtime = null
      diagnosticsCallback = null
      closeRuntime(activeRuntime)
    }
    analysisQueue.shutdown()
  }

  internal suspend fun complete(
      request: KotlinSemanticRequest,
      isCurrent: (KotlinSemanticRequest) -> Boolean,
  ): CompletionResult? = executeSemanticRequest(request, isCurrent) { it.complete(request) }

  internal suspend fun hover(
      request: KotlinSemanticRequest,
      isCurrent: (KotlinSemanticRequest) -> Boolean,
  ): MarkupContent? = executeSemanticRequest(request, isCurrent) { it.hover(request) }

  internal suspend fun findDefinition(
      request: KotlinSemanticRequest,
      isCurrent: (KotlinSemanticRequest) -> Boolean,
  ): DefinitionResult? = executeSemanticRequest(request, isCurrent) { it.findDefinition(request) }

  internal suspend fun findReferences(
      request: KotlinSemanticRequest,
      isCurrent: (KotlinSemanticRequest) -> Boolean,
  ): ReferenceResult? = executeSemanticRequest(request, isCurrent) { it.findReferences(request) }

  // TODO(XXX-XXX-EXPERIMENT): Remove signature boundary logging after the missing hints are verified.
  internal suspend fun signatureHelp(
      request: KotlinSemanticRequest,
      isCurrent: (KotlinSemanticRequest) -> Boolean,
  ): SignatureHelp? {
    val result = executeSemanticRequest(request, isCurrent) { it.signatureHelp(request) }
    KlsLogs.warn(
        "Kotlin Analysis signature help boundary: file={} version={} revision={} generation={} line={} column={} signatures={} activeSignature={} activeParameter={} current={}",
        request.file,
        request.snapshot.version,
        request.snapshot.revision,
        request.backendGeneration,
        request.line,
        request.column,
        result?.signatures?.size,
        result?.activeSignature,
        result?.activeParameter,
        isCurrent(request),
    )
    return result
  }

  private suspend fun <T> executeSemanticRequest(
      request: KotlinSemanticRequest,
      isCurrent: (KotlinSemanticRequest) -> Boolean,
      action: (KotlinAnalysisRuntime) -> T?,
  ): T? = suspendCancellableCoroutine { continuation ->
    fun canExecute(): Boolean = continuation.isActive && isReady && initialized &&
        generation == request.backendGeneration && !request.cancelChecker.isCancelled() && isCurrent(request)
    if (!canExecute()) {
      continuation.resume(null)
      return@suspendCancellableCoroutine
    }
    try {
      val task = analysisQueue.submit {
        try {
          val currentRuntime = runtime
          val result = if (canExecute() && currentRuntime != null) action(currentRuntime) else null
          if (continuation.isActive) continuation.resume(if (canExecute()) result else null)
        } catch (e: java.util.concurrent.CancellationException) {
          if (continuation.isActive) continuation.resumeWithException(e)
        } catch (e: Throwable) {
          KlsLogs.warn("Kotlin Analysis semantic request failed for: {}", request.file, e)
          if (continuation.isActive) continuation.resume(null)
        }
      }
      continuation.invokeOnCancellation { task.cancel(false) }
    } catch (e: RejectedExecutionException) {
      if (continuation.isActive) continuation.resume(null)
    }
  }

  private fun createRuntime(provider: KotlinProjectClasspathProvider): KotlinAnalysisRuntime? {
    var candidate: KotlinAnalysisRuntime? = null
    return try {
      candidate = runtimeFactory.create(provider)
      if (candidate.start()) {
        candidate
      } else {
        closeRuntime(candidate)
        null
      }
    } catch (t: Throwable) {
      closeRuntime(candidate)
      KlsLogs.error("Failed to start Kotlin Analysis runtime", t)
      null
    }
  }

  private fun closeRuntime(runtime: KotlinAnalysisRuntime?) {
    try {
      runtime?.close()
    } catch (t: Throwable) {
      KlsLogs.warn("Failed to close Kotlin Analysis runtime", t)
    }
  }

  private fun cancelScheduledDiagnostics() {
    scheduledJobs.values.forEach { it.cancel(false) }
    scheduledJobs.clear()
  }

  private fun <T> executeOnAnalysisQueue(action: () -> T): T? {
    if (analysisQueue.isShutdown) return null
    return try {
      if (Thread.currentThread() === queueThread) {
        action()
      } else {
        CompletableFuture.supplyAsync({ action() }, analysisQueue).join()
      }
    } catch (t: Throwable) {
      KlsLogs.warn("Kotlin Analysis backend operation failed", t)
      null
    }
  }

  private companion object {
    const val DIAGNOSTICS_DEBOUNCE_MS = 400L
  }
}
