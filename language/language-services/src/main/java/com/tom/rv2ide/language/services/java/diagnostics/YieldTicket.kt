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

import com.tom.rv2ide.lsp.models.DiagnosticResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot handoff from speculative diagnostics to the debounce scheduler.
 *
 * The analyzer marks this only after yielding to a foreground semantic lease. The server consumes it
 * only for [DiagnosticResult.NO_UPDATE], so ordinary no-update results cannot create a retry loop.
 */
internal class YieldTicket {
  private val yielded = AtomicBoolean(false)

  fun markYielded() {
    yielded.set(true)
  }

  fun consumeOnce(result: DiagnosticResult): Boolean =
      result == DiagnosticResult.NO_UPDATE && yielded.getAndSet(false)
}