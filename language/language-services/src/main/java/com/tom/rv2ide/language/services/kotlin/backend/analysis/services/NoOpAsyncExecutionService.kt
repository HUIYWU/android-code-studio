@file:Suppress("UnstableApiUsage")

package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import org.jetbrains.concurrency.CancellablePromise
import org.jetbrains.concurrency.Promise
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.application.AppUIExecutor
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.application.AsyncExecutionService
import org.jetbrains.kotlin.com.intellij.openapi.application.ExpirableExecutor
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState
import org.jetbrains.kotlin.com.intellij.openapi.application.NonBlockingReadAction
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.util.Function
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Consumer

internal class NoOpAsyncExecutionService : AsyncExecutionService() {
  private val uiExecutor: AppUIExecutor = NoOpAppUIExecutor()

  override fun createExecutor(backgroundExecutor: Executor): ExpirableExecutor = NoOpExpirableExecutor(backgroundExecutor)
  override fun createUIExecutor(modalityState: ModalityState): AppUIExecutor = uiExecutor
  override fun createWriteThreadExecutor(modalityState: ModalityState): AppUIExecutor = uiExecutor
  override fun <T> buildNonBlockingReadAction(callable: Callable<out T>): NonBlockingReadAction<T> = NoOpNonBlockingReadAction(callable)
}

private class NoOpAppUIExecutor : AppUIExecutor {
  override fun later(): AppUIExecutor = this
  override fun withDocumentsCommitted(project: Project): AppUIExecutor = this
  override fun inSmartMode(project: Project): AppUIExecutor = this
  override fun expireWith(disposable: Disposable): AppUIExecutor = this

  override fun execute(runnable: Runnable) = ApplicationManager.getApplication().invokeLater(runnable)

  override fun <T> submit(callable: Callable<T>): CancellablePromise<T> {
    val future = CompletableFuture<T>()
    ApplicationManager.getApplication().invokeLater {
      try {
        future.complete(callable.call())
      } catch (t: Throwable) {
        future.completeExceptionally(t)
      }
    }
    return future.asCancellablePromise()
  }

  override fun submit(runnable: Runnable): CancellablePromise<*> = submit(Callable { runnable.run(); null })
}

private class NoOpExpirableExecutor(private val executor: Executor) : ExpirableExecutor {
  override fun expireWith(disposable: Disposable): ExpirableExecutor = this
  override fun execute(runnable: Runnable) = executor.execute(runnable)
  override fun <T> submit(callable: Callable<T>): CancellablePromise<T> =
      CompletableFuture.supplyAsync({ callable.call() }, executor).asCancellablePromise()
  override fun submit(runnable: Runnable): CancellablePromise<*> =
      CompletableFuture.runAsync(runnable, executor).thenApply<Any?> { null }.asCancellablePromise()
}

private class NoOpNonBlockingReadAction<T>(private val callable: Callable<out T>) : NonBlockingReadAction<T> {
  override fun inSmartMode(project: Project): NonBlockingReadAction<T> = this
  override fun withDocumentsCommitted(project: Project): NonBlockingReadAction<T> = this
  override fun expireWhen(condition: BooleanSupplier): NonBlockingReadAction<T> = this
  override fun wrapProgress(indicator: ProgressIndicator): NonBlockingReadAction<T> = this
  override fun expireWith(disposable: Disposable): NonBlockingReadAction<T> = this
  override fun finishOnUiThread(modalityState: ModalityState, uiThreadAction: Consumer<in T>): NonBlockingReadAction<T> = this
  override fun coalesceBy(vararg equality: Any): NonBlockingReadAction<T> = this
  override fun submit(backgroundThreadExecutor: Executor): CancellablePromise<T> =
      CompletableFuture.supplyAsync({ callable.call() }, backgroundThreadExecutor).asCancellablePromise()
  override fun executeSynchronously(): T = callable.call()
}

private fun <T> CompletableFuture<T>.asCancellablePromise(): CancellablePromise<T> = CompletableFuturePromise(this)

private class CompletableFuturePromise<T>(val future: CompletableFuture<T>) : CancellablePromise<T> {
  override fun cancel(mayInterruptIfRunning: Boolean): Boolean = future.cancel(mayInterruptIfRunning)
  override fun isCancelled(): Boolean = future.isCancelled
  override fun isDone(): Boolean = future.isDone
  override fun get(): T = future.get()
  override fun get(timeout: Long, unit: TimeUnit): T = future.get(timeout, unit)
  override fun cancel() {
    future.cancel(true)
  }

  override fun onSuccess(handler: Consumer<in T>): CancellablePromise<T> { future.thenAccept(handler); return this }
  override fun onError(handler: Consumer<in Throwable>): CancellablePromise<T> { future.exceptionally { handler.accept(it); null }; return this }
  override fun onProcessed(handler: Consumer<in T?>): CancellablePromise<T> { future.whenComplete { value, _ -> handler.accept(value) }; return this }
  override fun getState(): Promise.State = when {
    future.isCancelled || future.isCompletedExceptionally -> Promise.State.REJECTED
    future.isDone -> Promise.State.SUCCEEDED
    else -> Promise.State.PENDING
  }
  override fun processed(child: Promise<in T>): Promise<T> = this
  override fun blockingGet(timeout: Int, unit: TimeUnit): T = future.get(timeout.toLong(), unit)
  override fun <S> then(handler: Function<in T, out S>): Promise<S> = future.thenApply { handler.`fun`(it) }.asCancellablePromise()
  override fun <S> thenAsync(handler: Function<in T, out Promise<S>>): Promise<S> =
      future.thenCompose<S> { value ->
        (handler.`fun`(value) as CompletableFuturePromise<S>).future
      }.asCancellablePromise()

}