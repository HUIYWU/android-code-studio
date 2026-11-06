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
package com.tom.rv2ide.lsp.api

import com.tom.rv2ide.progress.ICancelChecker

/**
 * Thread-scoped cancellation context for a completion computation.
 *
 * Completion runs on separate editor worker threads. A process-global service registry cannot
 * safely represent their independent UI cancellation states: concurrent requests could overwrite
 * or unregister one another's checker. This context is deliberately limited to the synchronous
 * completion call stack and must always be restored by the caller.
 *
 * It lives in the LSP API module because that is the contract boundary: editors install the
 * checker, providers read it, and neither side owns the other.
 */
object CompletionCancellation {
  private val currentChecker = ThreadLocal<ICancelChecker?>()

  @JvmStatic
  fun current(): ICancelChecker? = currentChecker.get()

  /** Installs [checker] for the current thread and returns the nesting-safe previous value. */
  @JvmStatic
  fun install(checker: ICancelChecker): ICancelChecker? {
    val previous = currentChecker.get()
    currentChecker.set(checker)
    return previous
  }

  /** Restores the value returned by [install], removing the ThreadLocal when it was absent. */
  @JvmStatic
  fun restore(previous: ICancelChecker?) {
    if (previous == null) {
      currentChecker.remove()
    } else {
      currentChecker.set(previous)
    }
  }
}