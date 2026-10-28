package com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler

import com.tom.rv2ide.language.services.kotlin.backend.analysis.analysisExceptionToPropagate
import com.tom.rv2ide.language.services.kotlin.backend.analysis.isRequestCancellation
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.progress.ICancelChecker
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.nio.file.Path
import java.util.HashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CancellationException

internal class AnalysisRequestScheduler(
    workerName: String = "acs-kotlin-analysis",
) : AutoCloseable {
  private val closed = AtomicBoolean(false)
  private val requestIds = AtomicLong(0L)
  private val queue = PriorityBlockingQueue<AnalysisRequest<*>>()
  private val wakeup = Object()
  private val pending = ConcurrentHashMap<Long, AnalysisRequest<*>>()
  @Volatile private var activeRequest: AnalysisRequest<*>? = null
  private val mutationLock = Any()
  private val reservedMutationSequences = HashMap<Path, Long>()
  private val completedMutationSequences = HashMap<Path, Long>()
  private val debounceExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "$workerName-debounce").apply { isDaemon = true }
  }
  private val worker: Thread = Thread(::runWorker, workerName).apply {
    isDaemon = true
    start()
  }

  fun <T> submit(
      kind: AnalysisRequestKind,
      path: java.nio.file.Path? = null,
      backendGeneration: Long = 0L,
      documentVersion: Int? = null,
      documentRevision: Long? = null,
      cancelChecker: ICancelChecker,
      requiredMutationPaths: Set<Path> = emptySet(),
      mutationPaths: Set<Path> = emptySet(),
      action: () -> T,
      cancellableAction: ((ICancelChecker) -> T)? = null,
  ): CompletableFuture<T?> {
    val future = CompletableFuture<T?>()
    if (closed.get()) {
      future.complete(null)
      return future
    }
    val (requiredMutationSequences, reservation) = synchronized(mutationLock) {
      val required = requiredMutationPaths
          .map { it.normalize() }
          .distinct()
          .associateWith { reservedMutationSequences[it] ?: completedMutationSequences[it] ?: 0L }
          .toMutableMap()
      val reserved = mutationPaths
          .map { it.normalize() }
          .distinct()
          .associateWith { path ->
            val previous = reservedMutationSequences[path] ?: completedMutationSequences[path] ?: 0L
            required.putIfAbsent(path, previous)
            val sequence = previous + 1L
            reservedMutationSequences[path] = sequence
            sequence
          }
      required to reserved
    }
    val requestCancellation = AnalysisRequestCancellation(cancelChecker)
    val request = AnalysisRequest<T>(
        id = requestIds.incrementAndGet(),
        kind = kind,
        path = path,
        backendGeneration = backendGeneration,
        documentVersion = documentVersion,
        documentRevision = documentRevision,
        cancelChecker = requestCancellation,
        requiredMutationSequences = requiredMutationSequences,
        mutationSequences = reservation,
        action = {
          if (closed.get() || future.isCancelled || cancelChecker.isCancelled()) {
            future.complete(null)
            null
          } else {
            try {
              (cancellableAction?.invoke(requestCancellation) ?: action()).also { future.complete(it) }
            } catch (t: Throwable) {
              val propagated = analysisExceptionToPropagate(t, requestCancellation)
              if (propagated != null) {
                future.completeExceptionally(propagated)
                throw propagated
              }
              if (isRequestCancellation(t, requestCancellation)) {
                future.complete(null)
                throw t
              }
              future.completeExceptionally(t)
              null
            }
          }
        },
        cancel = {
           requestCancellation.cancel()
           future.complete(null)
         },
    )
    pending[request.id] = request
    queue.offer(request)
    synchronized(wakeup) { wakeup.notifyAll() }
    return future
  }

  fun <T> schedule(
      delay: Long,
      unit: TimeUnit,
      kind: AnalysisRequestKind,
      path: java.nio.file.Path? = null,
      backendGeneration: Long = 0L,
      documentVersion: Int? = null,
      documentRevision: Long? = null,
      cancelChecker: ICancelChecker,
      requiredMutationPaths: Set<Path> = emptySet(),
      mutationPaths: Set<Path> = emptySet(),
      action: () -> T,
      cancellableAction: ((ICancelChecker) -> T)? = null,
  ): Pair<CompletableFuture<T?>, Future<*>> {
    val future = CompletableFuture<T?>()
    if (closed.get()) {
      future.complete(null)
      return future to CompletableFuture.completedFuture(null)
    }
    val timer = try {
      debounceExecutor.schedule(
          {
            if (!closed.get()) {
              val submitted = submit(
                  kind = kind,
                  path = path,
                  backendGeneration = backendGeneration,
                  documentVersion = documentVersion,
                  documentRevision = documentRevision,
                  cancelChecker = cancelChecker,
                  requiredMutationPaths = requiredMutationPaths,
                  mutationPaths = mutationPaths,
                  action = action,
                  cancellableAction = cancellableAction,
              )
              submitted.whenComplete { value, error ->
                if (error != null) future.completeExceptionally(error) else future.complete(value)
              }
            } else {
              future.complete(null)
            }
          },
          delay,
          unit,
      )
    } catch (_: RejectedExecutionException) {
      future.complete(null)
      CompletableFuture.completedFuture(null)
    }
    return future to timer
  }

  fun cancelDiagnostics(): Set<Path> =
      cancelRequests(predicate = { it.kind == AnalysisRequestKind.DIAGNOSTICS })

  fun cancelRequests(kind: AnalysisRequestKind, backendGeneration: Long): Set<Path> =
      cancelRequests(predicate = { it.kind == kind && it.backendGeneration == backendGeneration })

  fun cancelPendingRequests(kind: AnalysisRequestKind, backendGeneration: Long): Set<Path> =
      cancelRequests(
          predicate = { it.kind == kind && it.backendGeneration == backendGeneration },
          includeActive = false,
      )

  fun cancelAll(): Set<Path> = cancelRequests(predicate = { true })

  private fun cancelRequests(
      predicate: (AnalysisRequest<*>) -> Boolean,
      includeActive: Boolean = true,
  ): Set<Path> {
    val paths = linkedSetOf<Path>()
    pending.values
        .filter(predicate)
        .forEach { request ->
          request.path?.let(paths::add)
          request.cancel()
        }
    if (includeActive) {
      activeRequest
          ?.takeIf(predicate)
          ?.also { request ->
            request.path?.let(paths::add)
            request.cancel()
          }
    }
    synchronized(wakeup) { wakeup.notifyAll() }
    return paths
  }

  fun <T> execute(
      kind: AnalysisRequestKind,
      path: java.nio.file.Path? = null,
      backendGeneration: Long = 0L,
      documentVersion: Int? = null,
      documentRevision: Long? = null,
      cancelChecker: ICancelChecker,
      requiredMutationPaths: Set<Path> = emptySet(),
      mutationPaths: Set<Path> = emptySet(),
      action: () -> T,
      cancellableAction: ((ICancelChecker) -> T)? = null,
  ): T? {
    if (Thread.currentThread() === worker) {
      if (closed.get() || cancelChecker.isCancelled()) return null
      return try {
        cancellableAction?.invoke(cancelChecker) ?: action()
      } catch (t: Throwable) {
        val propagated = analysisExceptionToPropagate(t, cancelChecker)
        if (propagated != null) throw propagated
        if (isRequestCancellation(t, cancelChecker)) return null
        null
      }
    }
    return try {
      submit(
          kind = kind,
          path = path,
          backendGeneration = backendGeneration,
          documentVersion = documentVersion,
          documentRevision = documentRevision,
          cancelChecker = cancelChecker,
          requiredMutationPaths = requiredMutationPaths,
          mutationPaths = mutationPaths,
          action = action,
          cancellableAction = cancellableAction,
      ).join()
    } catch (t: Throwable) {
      val propagated = analysisExceptionToPropagate(t, cancelChecker)
      if (propagated != null) throw propagated
      if (isRequestCancellation(t, cancelChecker)) return null
      KlsLogs.warn("Kotlin Analysis request execution failed", t)
      null
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) {
      return
    }
    debounceExecutor.shutdownNow()
    pending.values.forEach { request ->
      request.cancelChecker.cancel()
      request.cancel()
    }
    activeRequest?.cancel()
    queue.clear()
    synchronized(wakeup) { wakeup.notifyAll() }
    worker.interrupt()
  }

  private fun runWorker() {
    while (!closed.get()) {
      val request = takeExecutableRequest()
      if (request == null) return
      pending.remove(request.id)
      activeRequest = request
      try {
        if (request.cancelChecker.isCancelled()) {
          request.cancel()
        } else {
          request.action()
        }
      } catch (_: CancellationException) {
        Unit
      } catch (t: Throwable) {
        KlsLogs.warn(
            "Kotlin Analysis scheduler: action=request-failed, id={}, kind={}, path={}, generation={}",
            request.id,
            request.kind,
            request.path,
            request.backendGeneration,
            t,
        )
      } finally {
        completeMutationSequences(request.mutationSequences)
        activeRequest = null
      }
    }
  }

  private fun takeExecutableRequest(): AnalysisRequest<*>? {
    while (!closed.get()) {
      val candidate = queue
          .toList()
          .sorted()
          .firstOrNull { isExecutable(it) }
      if (candidate != null && queue.remove(candidate)) return candidate
      synchronized(wakeup) {
        if (!closed.get() && queue.none { isExecutable(it) }) {
          try {
            wakeup.wait()
          } catch (_: InterruptedException) {
            if (closed.get()) return null
          }
        }
      }
    }
    return null
  }

  private fun isExecutable(request: AnalysisRequest<*>): Boolean =
      request.requiredMutationSequences.all { (path, sequence) ->
        synchronized(mutationLock) {
          (completedMutationSequences[path] ?: 0L) >= sequence
        }
      }

  private fun completeMutationSequences(sequences: Map<Path, Long>) {
    if (sequences.isEmpty()) return
    synchronized(mutationLock) {
      sequences.forEach { (path, sequence) ->
        val current = completedMutationSequences[path] ?: 0L
        if (sequence > current) completedMutationSequences[path] = sequence
      }
    }
    synchronized(wakeup) { wakeup.notifyAll() }
  }
}