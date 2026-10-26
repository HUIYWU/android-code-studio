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
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisRequestKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisRequestScheduler
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.projects.models.ProjectInputSnapshotFactory
import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentKind
import com.tom.rv2ide.language.services.kotlin.document.kotlinDocumentKind
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.progress.ICancelChecker
import java.util.concurrent.CancellationException
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
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

  private val requestScheduler = AnalysisRequestScheduler()
  private val closeRequested = AtomicBoolean(false)
  private val stateRef = AtomicReference(KotlinBackendState.NEW)
  private val generationCounter = AtomicLong(0L)
  private val diagnosticDebounceJobs = ConcurrentHashMap<Path, Future<*>>()
  private val diagnosticDebounceCancellations = ConcurrentHashMap<Path, ICancelChecker>()
  private val diagnosticSequences = ConcurrentHashMap<Path, Long>()
  private val diagnosticSequenceCounter = AtomicLong(0L)
  private val diagnosticRescheduledSequences = ConcurrentHashMap<Path, Long>()
  private val interruptedDiagnostics = ConcurrentHashMap<Path, Long>()
  private val diagnosticsStateLock = Any()
  private val interactiveRequestLock = Any()
  private val activeInteractiveRequests = AtomicInteger(0)
  private var runtime: KotlinAnalysisRuntime? = null
  private var diagnosticsCallback: ((DiagnosticResult) -> Unit)? = null
  @Volatile private var initialized = false
  private var projectInputState: KotlinProjectInputSnapshotState? = null
  private var projectInputClasspathProvider: KotlinProjectClasspathProvider? = null

  override val state: KotlinBackendState
    get() = stateRef.get()

  override val isReady: Boolean
    get() = state == KotlinBackendState.READY && !closeRequested.get()

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
    val normalized = path.normalize()
    executeOnAnalysisQueue(path = normalized, mutationPaths = setOf(normalized)) {
      runtime?.onFileCreated(normalized)
    }
  }

  override fun notifyFileRemoved(path: Path) {
    val normalized = path.normalize()
    executeOnAnalysisQueue(path = normalized, mutationPaths = setOf(normalized)) {
      runtime?.onFileRemoved(normalized)
    }
  }

  override fun notifyFileMoved(fromPath: Path, toPath: Path) {
    val from = fromPath.normalize()
    val to = toPath.normalize()
    executeOnAnalysisQueue(path = to, mutationPaths = setOf(from, to)) {
      runtime?.onFileMoved(from, to)
    }
  }

  override fun notifyFileChanges(changes: List<KotlinBackendFileChange>): Boolean {
    val mutationPaths = changes.map { it.path.normalize() }.toSet()
    return executeOnAnalysisQueue(mutationPaths = mutationPaths) {
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
  }

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
        val targetGeneration = generationCounter.get() + 1L
        val startedRuntime = createRuntime(classpathProvider, targetGeneration)
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
        diagnosticRescheduledSequences.clear()
        interruptedDiagnostics.clear()
        val wasInitialized = initialized
        val oldRuntime = runtime
        val oldGeneration = generation
        requestScheduler.cancelRequests(AnalysisRequestKind.DIAGNOSTICS, oldGeneration)
        requestScheduler.cancelPendingRequests(AnalysisRequestKind.INDEXING, oldGeneration)
        val targetGeneration = oldGeneration + 1L
        val newRuntime = createRuntime(classpathProvider, targetGeneration)
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
    executeOnAnalysisQueue(mutationPaths = notificationMutationPaths(method, params)) {
      if (isReady) handleNotification(method, params)
    }
  }

  override fun sendNotificationOrThrow(method: String, params: JsonObject) {
    val handled = executeOnAnalysisQueue(mutationPaths = notificationMutationPaths(method, params)) {
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
            scheduleDiagnostics(path)
          }
        }
        "textDocument/didClose" -> {
          val path = extractFilePath(params)?.normalize() ?: return
          runtime?.onFileClosed(path)
          synchronized(diagnosticsStateLock) {
            diagnosticSequences[path] = diagnosticSequenceCounter.incrementAndGet()
            diagnosticRescheduledSequences.remove(path)
            interruptedDiagnostics.remove(path)
            diagnosticDebounceCancellations.remove(path)?.cancel()
            diagnosticDebounceJobs.remove(path)?.cancel(false)
          }
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

  private fun notificationMutationPaths(method: String, params: JsonObject): Set<Path> =
      if (method in setOf("textDocument/didOpen", "textDocument/didChange", "textDocument/didClose")) {
        extractFilePath(params)?.normalize()?.let(::setOf).orEmpty()
      } else {
        emptySet()
      }

  private fun rescheduleActiveDocuments() {
    activeDocuments()
        .filter(::supportsDocument)
        .forEach { path -> scheduleDiagnostics(path) }
  }

  private fun scheduleDiagnostics(path: Path, preserveRescheduleState: Boolean = false) {
    synchronized(diagnosticsStateLock) {
      if (!isReady || !initialized) return
      val normalizedPath = path.normalize()
      val sequence = diagnosticSequenceCounter.incrementAndGet()
      diagnosticSequences[normalizedPath] = sequence
      if (!preserveRescheduleState) {
        diagnosticRescheduledSequences.remove(normalizedPath)
        interruptedDiagnostics.remove(normalizedPath)
      }
      diagnosticDebounceCancellations.remove(normalizedPath)?.cancel()
      diagnosticDebounceJobs.remove(normalizedPath)?.cancel(false)
      val debounceCancellation = ICancelChecker.Default()
      diagnosticDebounceCancellations[normalizedPath] = debounceCancellation
      val (_, timer) = requestScheduler.schedule(
          delay = DIAGNOSTICS_DEBOUNCE_MS,
          unit = TimeUnit.MILLISECONDS,
          kind = AnalysisRequestKind.DIAGNOSTICS,
          path = normalizedPath,
          backendGeneration = generation,
          cancelChecker = debounceCancellation,
          requiredMutationPaths = setOf(normalizedPath),
          action = {},
          cancellableAction = { cancelChecker ->
            synchronized(diagnosticsStateLock) {
              if (diagnosticSequences[normalizedPath] == sequence) {
                diagnosticDebounceJobs.remove(normalizedPath)
                diagnosticDebounceCancellations.remove(normalizedPath)
              }
            }
            publishDiagnostics(normalizedPath, sequence, cancelChecker)
            Unit
          },
      )
      diagnosticDebounceJobs[normalizedPath] = timer
    }
  }

  private fun publishDiagnostics(path: Path, sequence: Long, cancelChecker: ICancelChecker) {
    val normalizedPath = path.normalize()
    if (!isReady || !initialized || diagnosticSequences[path] != sequence) {
      return
    }
    cancelChecker.abortIfCancelled()
    val callback = diagnosticsCallback ?: return
    val requestGeneration = generation
    val snapshot = FileManager.getActiveDocumentSnapshot(normalizedPath) ?: return
    val activeRuntime = runtime ?: return
    try {
      val result = activeRuntime.analyze(normalizedPath, snapshot, cancelChecker) ?: run {
        KlsLogs.warn("Kotlin diagnostics analysis returned null: file={}", normalizedPath)
        return
      }
      cancelChecker.abortIfCancelled()
      if (diagnosticSequences[normalizedPath] != sequence) return
      if (requestGeneration != generation || !isReady) return
      val currentSnapshot = FileManager.getActiveDocumentSnapshot(normalizedPath)
      if (currentSnapshot == null || snapshot.version != currentSnapshot.version ||
          snapshot.revision != currentSnapshot.revision) return
      if (diagnosticSequences[normalizedPath] != sequence) return
      val published = result.copy(
          channel = DiagnosticResult.CHANNEL_KOTLIN,
          documentVersion = snapshot.version,
          documentRevision = snapshot.revision,
          backendGeneration = requestGeneration,
      )
      callback.invoke(published)
    } catch (e: CancellationException) {
      KlsLogs.debug("Kotlin Analysis diagnostics cancelled: file={}", normalizedPath)
    } catch (t: Throwable) {
      KlsLogs.error("Kotlin Analysis API diagnostics failed for: $normalizedPath", t)
    }
  }

  override fun close() {
    if (state == KotlinBackendState.CLOSED) {
      return
    }
    closeRequested.set(true)
    requestScheduler.cancelAll()
    executeOnAnalysisQueue {
      if (state == KotlinBackendState.CLOSED) return@executeOnAnalysisQueue
      stateRef.set(KotlinBackendState.CLOSED)
      cancelScheduledDiagnostics()
      diagnosticRescheduledSequences.clear()
      interruptedDiagnostics.clear()
      initialized = false
      val activeRuntime = runtime
      runtime = null
      diagnosticsCallback = null
      closeRuntime(activeRuntime)
    }
    requestScheduler.close()
  }

internal suspend fun complete(
       request: KotlinSemanticRequest,
       isCurrent: (KotlinSemanticRequest) -> Boolean,
   ): CompletionResult? = executeSemanticRequest(request, AnalysisRequestKind.SEMANTIC, isCurrent) { it.complete(request) }

internal suspend fun hover(
       request: KotlinSemanticRequest,
       isCurrent: (KotlinSemanticRequest) -> Boolean,
   ): MarkupContent? = executeSemanticRequest(request, AnalysisRequestKind.SEMANTIC, isCurrent) { it.hover(request) }

internal suspend fun findDefinition(
       request: KotlinSemanticRequest,
       isCurrent: (KotlinSemanticRequest) -> Boolean,
   ): DefinitionResult? = executeSemanticRequest(request, AnalysisRequestKind.COMMAND, isCurrent) { it.findDefinition(request) }

internal suspend fun findReferences(
       request: KotlinSemanticRequest,
       isCurrent: (KotlinSemanticRequest) -> Boolean,
   ): ReferenceResult? = executeSemanticRequest(request, AnalysisRequestKind.COMMAND, isCurrent) { it.findReferences(request) }

internal suspend fun signatureHelp(
       request: KotlinSemanticRequest,
       isCurrent: (KotlinSemanticRequest) -> Boolean,
   ): SignatureHelp? = executeSemanticRequest(request, AnalysisRequestKind.SEMANTIC, isCurrent) { it.signatureHelp(request) }
  private suspend fun <T> executeSemanticRequest(
      request: KotlinSemanticRequest,
      kind: AnalysisRequestKind,
      isCurrent: (KotlinSemanticRequest) -> Boolean,
      action: (KotlinAnalysisRuntime) -> T?,
  ): T? = suspendCancellableCoroutine { continuation ->
    fun canExecute(): Boolean = continuation.isActive && isReady && initialized &&
        generation == request.backendGeneration && !request.cancelChecker.isCancelled() && isCurrent(request)

    if (!canExecute()) {
      continuation.resume(null)
      return@suspendCancellableCoroutine
    }

    cancelDiagnosticsForInteractiveRequest()
    val completionReported = AtomicBoolean(false)

    fun reportInteractiveCompletion() {
      if (completionReported.compareAndSet(false, true)) {
        finishInteractiveRequest()
      }
    }

    try {
      val task = requestScheduler.submit(
          kind = kind,
          path = request.file,
          backendGeneration = request.backendGeneration,
          documentVersion = request.snapshot.version,
          documentRevision = request.snapshot.revision,
          cancelChecker = request.cancelChecker,
          requiredMutationPaths = setOf(request.file),
          action = {
            val currentRuntime = runtime
            val result = if (canExecute() && currentRuntime != null) action(currentRuntime) else null
            val currentAfterExecution = canExecute()
            if (currentAfterExecution) result else null
          },
      )
      task.whenComplete { result, error ->
        reportInteractiveCompletion()
        if (!continuation.isActive) return@whenComplete
        if (error != null) {
          KlsLogs.warn("Kotlin Analysis semantic request failed for: {}", request.file, error)
          continuation.resume(null)
        } else {
          continuation.resume(result)
        }
      }
      continuation.invokeOnCancellation {
        task.cancel(false)
        reportInteractiveCompletion()
      }
    } catch (t: Throwable) {
      reportInteractiveCompletion()
      KlsLogs.warn("Kotlin Analysis semantic request submission failed for: {}", request.file, t)
      if (continuation.isActive) continuation.resume(null)
    }
  }

  private fun createRuntime(
      provider: KotlinProjectClasspathProvider,
      backendGeneration: Long,
  ): KotlinAnalysisRuntime? {
    var candidate: KotlinAnalysisRuntime? = null
    return try {
      candidate = runtimeFactory.create(provider)
      candidate.configureIndexingScheduler { path, action ->
        val normalizedPath = path.normalize()
        if (isReady && generation == backendGeneration) {
          requestScheduler.submit(
              kind = AnalysisRequestKind.INDEXING,
              path = normalizedPath,
              backendGeneration = backendGeneration,
              cancelChecker = ICancelChecker.NOOP,
              mutationPaths = setOf(normalizedPath),
              action = { Unit },
              cancellableAction = { cancelChecker ->
                cancelChecker.abortIfCancelled()
                action()
                Unit
              },
          )
        }
        Unit
      }
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

  private fun cancelDiagnosticsForInteractiveRequest() {
    synchronized(interactiveRequestLock) {
      synchronized(diagnosticsStateLock) {
        val interruptedPaths = cancelScheduledDiagnostics().toMutableSet()
        interruptedPaths += requestScheduler.cancelDiagnostics()
        interruptedPaths.forEach { path ->
          diagnosticSequences[path]?.let { sequence ->
            if (diagnosticRescheduledSequences[path] != sequence) {
              interruptedDiagnostics[path] = sequence
            } else {
              interruptedDiagnostics.remove(path)
            }
          }
        }
      }
      activeInteractiveRequests.incrementAndGet()
    }
  }

  private fun finishInteractiveRequest() {
    synchronized(interactiveRequestLock) {
      val remaining = activeInteractiveRequests.decrementAndGet()
      if (remaining != 0 || !isReady || !initialized) return
      synchronized(diagnosticsStateLock) {
        val paths = interruptedDiagnostics.entries
            .mapNotNull { (path, sequence) ->
              interruptedDiagnostics.remove(path)
              if (diagnosticSequences[path] == sequence &&
                  diagnosticRescheduledSequences[path] != sequence) {
                path to sequence
              } else {
                null
              }
            }
        paths.forEach { (path, sequence) ->
          scheduleDiagnostics(path, preserveRescheduleState = true)
          diagnosticRescheduledSequences[path] = diagnosticSequences[path] ?: sequence
        }
      }
    }
  }

  private fun cancelScheduledDiagnostics(): Set<Path> {
    synchronized(diagnosticsStateLock) {
      val paths = diagnosticDebounceJobs.keys.toSet()
      diagnosticDebounceCancellations.values.forEach { it.cancel() }
      diagnosticDebounceCancellations.clear()
      diagnosticDebounceJobs.values.forEach { it.cancel(false) }
      diagnosticDebounceJobs.clear()
      return paths
    }
  }

  private fun <T> executeOnAnalysisQueue(
      path: Path? = null,
      mutationPaths: Set<Path> = emptySet(),
      action: () -> T,
  ): T? = requestScheduler.execute(
      kind = AnalysisRequestKind.INDEXING,
      path = path,
      cancelChecker = ICancelChecker.NOOP,
      mutationPaths = mutationPaths,
      action = action,
  )

  private companion object {
    const val DIAGNOSTICS_DEBOUNCE_MS = 400L
  }
}
