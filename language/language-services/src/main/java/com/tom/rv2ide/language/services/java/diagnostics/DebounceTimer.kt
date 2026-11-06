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

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One-shot debounce timer for speculative diagnostics.
 *
 * A dedicated daemon executor is used instead of a main-thread Handler: the timeout only starts a
 * background analysis pass, and diagnostics publication already returns through `Dispatchers.Main`.
 * Keeping the timer off the main looper also removes the construction-thread requirement the old
 * Handler-based timer had.
 */
internal class DebounceTimer(private val onTimeout: () -> Unit) {

  companion object {
    const val DEFAULT_INTERVAL_MS = 400L
  }

  private val executor: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "java-diagnostics-debounce").apply { isDaemon = true }
      }
  private val lock = Any()
  private var pending: ScheduledFuture<*>? = null

  @Volatile
  var interval: Long = DEFAULT_INTERVAL_MS
    set(value) {
      require(value > 0L) { "Invalid interval specified for timer." }
      field = value
    }

  @Volatile
  var isStarted: Boolean = false
    private set

  fun start() {
    restart()
  }

  fun restart() {
    synchronized(lock) {
      pending?.cancel(false)
      // Mark the timer inactive before invoking client code so a callback that decides to defer
      // analysis can schedule its own next attempt.
      isStarted = true
      pending =
          executor.schedule(
              {
                isStarted = false
                onTimeout()
              },
              interval,
              TimeUnit.MILLISECONDS,
          )
    }
  }

  fun cancel() {
    synchronized(lock) {
      pending?.cancel(false)
      pending = null
      isStarted = false
    }
  }

  fun close() {
    cancel()
    executor.shutdownNow()
  }
}