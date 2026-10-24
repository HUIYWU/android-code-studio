package com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler

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
      attempt: Int = 0,
      action: () -> T,
  ): CompletableFuture<T?> {
    val future = CompletableFuture<T?>()
    if (closed.get()) {
      future.complete(null)
      return future
    }
    val request = AnalysisRequest<T>(
        id = requestIds.incrementAndGet(),
        kind = kind,
        path = path,
        backendGeneration = backendGeneration,
        documentVersion = documentVersion,
        documentRevision = documentRevision,
        cancelChecker = cancelChecker,
        attempt = attempt,
        action = {
          if (closed.get() || future.isCancelled || cancelChecker.isCancelled()) {
            future.complete(null)
            null
          } else {
            try {
              action().also { future.complete(it) }
            } catch (e: CancellationException) {
              future.complete(null)
              throw e
            } catch (t: Throwable) {
              future.completeExceptionally(t)
              null
            }
          }
        },
        cancel = { future.complete(null) },
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
      attempt: Int = 0,
      action: () -> T,
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
                  kind,
                  path,
                  backendGeneration,
                  documentVersion,
                  documentRevision,
                  cancelChecker,
                  attempt,
                  action,
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

  fun <T> execute(
      kind: AnalysisRequestKind,
      path: java.nio.file.Path? = null,
      backendGeneration: Long = 0L,
      documentVersion: Int? = null,
      documentRevision: Long? = null,
      cancelChecker: ICancelChecker,
      action: () -> T,
  ): T? {
    if (Thread.currentThread() === worker) {
      if (closed.get() || cancelChecker.isCancelled()) return null
      return runCatching(action).getOrNull()
    }
    return try {
      submit(kind, path, backendGeneration, documentVersion, documentRevision, cancelChecker, action = action).join()
    } catch (t: Throwable) {
      KlsLogs.warn("Kotlin Analysis request execution failed", t)
      null
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    debounceExecutor.shutdownNow()
    pending.values.forEach { request ->
      request.cancelChecker.cancel()
      request.cancel()
    }
    queue.clear()
    synchronized(wakeup) { wakeup.notifyAll() }
    worker.interrupt()
  }

  private fun runWorker() {
    while (!closed.get()) {
      val request = queue.poll()
      if (request == null) {
        synchronized(wakeup) {
          if (!closed.get() && queue.isEmpty()) {
            try {
              wakeup.wait()
            } catch (_: InterruptedException) {
              if (closed.get()) return
            }
          }
        }
        continue
      }
      pending.remove(request.id)
      if (request.cancelChecker.isCancelled()) {
        request.cancel()
        continue
      }
      try {
        request.action()
      } catch (e: CancellationException) {
        if (!closed.get()) KlsLogs.debug("Kotlin Analysis request cancelled: id={}", request.id)
      } catch (t: Throwable) {
        KlsLogs.warn("Kotlin Analysis request failed: id={}", request.id, t)
      }
    }
  }
}