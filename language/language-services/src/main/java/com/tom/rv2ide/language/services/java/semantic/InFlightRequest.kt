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
package com.tom.rv2ide.language.services.java.semantic

import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Common identity of a shareable semantic request, used for supersession and file cancellation. */
internal interface SemanticRequestKey {
  val file: Path
  val documentRevision: Long
}

/**
 * Session-owned state of one in-flight semantic request.
 *
 * The [workerCancelChecker] belongs to the computation, never to a UI waiter. A waiter can detach
 * without stopping the worker while another waiter remains, and the result is published through
 * [result] instead of blocking the requesting thread.
 */
internal class InFlightRequest<T>(
    val key: SemanticRequestKey,
    private val cancellationMessage: String,
) {
  val workerCancelChecker = ICancelChecker.Default()
  val result = CompletableFuture<T>()
  private val subscribers = RequestSubscribers()
  private val terminal = AtomicBoolean(false)

  fun attachSubscriber(): AutoCloseable {
    val subscriberLease = subscribers.attach()
    val detached = AtomicBoolean(false)
    return AutoCloseable {
      if (detached.compareAndSet(false, true)) {
        subscriberLease.close()
        cancelWorkerIfUnobserved()
      }
    }
  }

  val subscriberCount: Int
    get() = subscribers.size

  /** Completes once; returns false when cancellation or another completion won the race. */
  fun complete(workerResult: T): Boolean {
    if (!terminal.compareAndSet(false, true)) {
      return false
    }
    return result.complete(workerResult)
  }

  /** Completes exceptionally once; returns false when the worker already published a result. */
  fun fail(error: Throwable): Boolean {
    if (!terminal.compareAndSet(false, true)) {
      return false
    }
    return result.completeExceptionally(error)
  }

  fun cancelWorkerIfUnobserved() {
    if (subscribers.isEmpty && !result.isDone) {
      cancelWorker()
    }
  }

  fun cancelWorker() {
    workerCancelChecker.cancel()
    fail(CancellationException(cancellationMessage))
  }
}