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

import java.util.concurrent.atomic.AtomicInteger

/**
 * Reference counting for UI waiters attached to one in-flight semantic request.
 *
 * Detaching a waiter must never cancel work that another waiter still observes, so the count is
 * tracked separately from the worker's own cancellation.
 */
internal class RequestSubscribers {
  private val count = AtomicInteger(0)

  fun attach(): AutoCloseable {
    count.incrementAndGet()
    val detached = java.util.concurrent.atomic.AtomicBoolean(false)
    return AutoCloseable {
      if (detached.compareAndSet(false, true)) {
        check(count.decrementAndGet() >= 0) { "Semantic subscriber count underflow" }
      }
    }
  }

  val isEmpty: Boolean
    get() = count.get() == 0

  val size: Int
    get() = count.get()
}
