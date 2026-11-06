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
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YieldTicketTest {

  @Test
  fun yieldedNoUpdate_requestsExactlyOneRetry() {
    val state = YieldTicket()
    state.markYielded()

    assertTrue(state.consumeOnce(DiagnosticResult.NO_UPDATE))
    assertFalse(state.consumeOnce(DiagnosticResult.NO_UPDATE))
  }

  @Test
  fun ordinaryNoUpdate_neverRequestsRetry() {
    val state = YieldTicket()

    assertFalse(state.consumeOnce(DiagnosticResult.NO_UPDATE))
  }

  @Test
  fun nonNoUpdate_doesNotConsumeYieldUntilNoUpdateArrives() {
    val state = YieldTicket()
    state.markYielded()
    val completedResult = DiagnosticResult(Paths.get("Sample.java"), emptyList())

    assertFalse(state.consumeOnce(completedResult))
    assertTrue(state.consumeOnce(DiagnosticResult.NO_UPDATE))
  }

  @Test
  fun repeatedYields_coalesceIntoOneRetry() {
    val state = YieldTicket()

    state.markYielded()
    state.markYielded()

    assertTrue(state.consumeOnce(DiagnosticResult.NO_UPDATE))
    assertFalse(state.consumeOnce(DiagnosticResult.NO_UPDATE))
  }
}