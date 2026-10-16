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
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendFileChange
import com.tom.rv2ide.language.services.kotlin.backend.analysis.input.KotlinProjectInputDiff
import com.tom.rv2ide.language.services.kotlin.backend.analysis.input.KotlinProjectInputSnapshotState
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.projects.models.ProjectInputSnapshotFactory
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
  private var projectInputState: KotlinProjectInputSnapshotState? = null
  private var projectInputClasspathProvider: KotlinProjectClasspathProvider? = null

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
  override fun notifyFileCreated(path: Path) {
    executeOnAnalysisQueue { runtime?.onFileCreated(path.normalize()) }
  }

  override fun notifyFileRemoved(path: Path) {
    executeOnAnalysisQueue { runtime?.onFileRemoved(path.normalize()) }
  }

  override fun notifyFileMoved(fromPath: Path, toPath: Path) {
    executeOnAnalysisQueue { runtime?.onFileMoved(fromPath.normalize(), toPath.normalize()) }
  }

  override fun notifyFileChanges(changes: List<KotlinBackendFileChange>): Boolean =
      executeOnAnalysisQueue {
        val provider = projectInputClasspathProvider
        if (changes.isEmpty() || provider == null || !isReady || runtime == null) {
          return@executeOnAnalysisQueue false
        }
        if (changes.any {
              it.kind != KotlinBackendFileChange.Kind.MODIFIED ||
                  it.path.fileName?.toString()?.endsWith(".kt") != true
            }) {
          return@executeOnAnalysisQueue false
        }
        val workspace = provider.workspace ?: return@executeOnAnalysisQueue false
        val currentState = runCatching {
          KotlinProjectInputSnapshotState.from(ProjectInputSnapshotFactory.capture(workspace))
        }.getOrNull() ?: return@executeOnAnalysisQueue false
        val previousState = projectInputState ?: return@executeOnAnalysisQueue false
        val change = KotlinProjectInputDiff.compare(previousState, currentState)
        if (change.kind != com.tom.rv2ide.language.services.kotlin.backend.analysis.input.KotlinProjectChangeKind.NONE) {
          return@executeOnAnalysisQueue false
        }
        changes.map { it.path.normalize() }.distinct().forEach { path ->
          runtime?.onFileContentChanged(path)
        }
        projectInputState = currentState
        true
      } ?: false

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
        projectInputClasspathProvider = classpathProvider
        generationCounter.incrementAndGet()
        recordProjectInput(classpathProvider)
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
        projectInputClasspathProvider = classpathProvider
        generationCounter.incrementAndGet()
        recordProjectInput(classpathProvider)
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
        "textDocument/didOpen" -> {
          val path = extractFilePath(params)?.normalize() ?: return
          if (supportsDocument(path)) {
            runtime?.onFileOpened(path)
            scheduleDiagnostics(path)
          }
        }
        "textDocument/didChange" -> {
          val path = extractFilePath(params)?.normalize() ?: return
          if (supportsDocument(path)) {
            runtime?.onFileContentChanged(
                path,
                FileManager.getLatestOneHopDocumentEdit(path),
            )
            scheduleDiagnostics(path)
          }
        }
        "textDocument/didSave" -> {
          val path = extractFilePath(params)?.normalize() ?: return
          if (supportsDocument(path)) {
            runtime?.onFileContentChanged(path)
            scheduleDiagnostics(path)
          }
        }
        "textDocument/didClose" -> {
          val path = extractFilePath(params)?.normalize() ?: return
          runtime?.onFileClosed(path)
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
    if (!isReady || !initialized) return
    val normalizedPath = path.normalize()
    scheduledJobs.remove(normalizedPath)?.cancel(false)
    try {
      val job =
          analysisQueue.schedule(
              {
                scheduledJobs.remove(normalizedPath)
                publishDiagnostics(normalizedPath)
              },
              DIAGNOSTICS_DEBOUNCE_MS,
              TimeUnit.MILLISECONDS,
          )
      scheduledJobs[normalizedPath] = job
    } catch (e: RejectedExecutionException) {
      KlsLogs.debug("Analysis task rejected during shutdown: {}", e.message)
    }
  }

  private fun publishDiagnostics(path: Path) {
    if (!isReady || !initialized) return
    val callback = diagnosticsCallback ?: return
    val requestGeneration = generation
    val normalizedPath = path.normalize()
    val snapshot = FileManager.getActiveDocumentSnapshot(normalizedPath) ?: return
    val activeRuntime = runtime ?: return
    KlsLogs.info(
        "Kotlin Analysis live PSI: action=diagnostics-start, path={}, generation={}, version={}, revision={}",
        normalizedPath,
        requestGeneration,
        snapshot.version,
        snapshot.revision,
    )
    try {
      val result = activeRuntime.analyze(normalizedPath, snapshot) ?: run {
        KlsLogs.warn("Kotlin diagnostics analysis returned null: file={}", normalizedPath)
        return
      }
if (requestGeneration != generation || state != KotlinBackendState.READY) {
         KlsLogs.info(
             "Kotlin Analysis live PSI: action=diagnostics-drop, path={}, generation={}, reason=backend-generation-or-state",
             normalizedPath,
             requestGeneration,
         )
         return
       }
       val currentSnapshot = FileManager.getActiveDocumentSnapshot(normalizedPath)
       if (currentSnapshot == null || snapshot.version != currentSnapshot.version ||
           snapshot.revision != currentSnapshot.revision) {
         KlsLogs.info(
             "Kotlin Analysis live PSI: action=diagnostics-drop, path={}, generation={}, reason=document-identity, requestedVersion={}, requestedRevision={}, currentVersion={}, currentRevision={}",
             normalizedPath,
             requestGeneration,
             snapshot.version,
             snapshot.revision,
             currentSnapshot?.version,
             currentSnapshot?.revision,
         )
         return
       }
       val published = result.copy(
           channel = DiagnosticResult.CHANNEL_KOTLIN,
           documentVersion = snapshot.version,
           documentRevision = snapshot.revision,
           backendGeneration = requestGeneration,
       )
       KlsLogs.info(
           "Kotlin Analysis live PSI: action=diagnostics-ready, path={}, generation={}, version={}, revision={}, diagnosticCount={}, ranges={}",
           normalizedPath,
           requestGeneration,
           published.documentVersion,
           published.documentRevision,
           published.diagnostics.size,
           published.diagnostics.take(12).joinToString("|") { diagnostic ->
             "${diagnostic.code}:${diagnostic.range.start.line}:${diagnostic.range.start.column}-${diagnostic.range.end.line}:${diagnostic.range.end.column}"
           },
       )
       callback.invoke(published)
    } catch (t: Throwable) {
      KlsLogs.error("Kotlin Analysis API diagnostics failed for: $normalizedPath", t)
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

  internal suspend fun signatureHelp(
      request: KotlinSemanticRequest,
      isCurrent: (KotlinSemanticRequest) -> Boolean,
  ): SignatureHelp? = executeSemanticRequest(request, isCurrent) { it.signatureHelp(request) }

  private suspend fun <T> executeSemanticRequest(
      request: KotlinSemanticRequest,
      isCurrent: (KotlinSemanticRequest) -> Boolean,
      action: (KotlinAnalysisRuntime) -> T?,
  ): T? = suspendCancellableCoroutine { continuation ->
    fun canExecute(): Boolean = continuation.isActive && isReady && initialized &&
        generation == request.backendGeneration && !request.cancelChecker.isCancelled() && isCurrent(request)
    if (!canExecute()) {
      KlsLogs.info(
          "Kotlin Analysis live PSI: action=semantic-drop, path={}, generation={}, reason=before-execution-current-check",
          request.file,
          request.backendGeneration,
      )
      continuation.resume(null)
      return@suspendCancellableCoroutine
    }
    try {
      val task = analysisQueue.submit {
        try {
          val currentRuntime = runtime
val result = if (canExecute() && currentRuntime != null) action(currentRuntime) else null
           val currentAfterExecution = canExecute()
           if (!currentAfterExecution) {
             KlsLogs.info(
                 "Kotlin Analysis live PSI: action=semantic-drop, path={}, generation={}, reason=after-execution-current-check",
                 request.file,
                 request.backendGeneration,
             )
           }
           if (continuation.isActive) continuation.resume(if (currentAfterExecution) result else null)
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

  private fun recordProjectInput(classpathProvider: KotlinProjectClasspathProvider) {
    val workspace = classpathProvider.workspace ?: return
    try {
      val snapshot = ProjectInputSnapshotFactory.capture(workspace)
      val currentState = KotlinProjectInputSnapshotState.from(snapshot)
      projectInputState = currentState
    } catch (t: Throwable) {
      KlsLogs.warn("Kotlin Analysis project input snapshot failed", t)
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
