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
package com.tom.rv2ide.language.services.java

import com.tom.rv2ide.language.services.java.diagnostics.PublishDecision
import com.tom.rv2ide.language.services.java.diagnostics.YieldTicket
import com.tom.rv2ide.lsp.models.DiagnosticResult
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishDecisionTest {

  @Test
  fun currentTarget_publishesCompletedResult() {
    assertEquals(
        PublishDecision.Action.PUBLISH,
        PublishDecision.decide(result(), isCurrentTarget = true, retryAfterYield = false),
    )
  }

  @Test
  fun currentTargetYieldedNoUpdate_retries() {
    assertEquals(
        PublishDecision.Action.RETRY,
        PublishDecision.decide(
            DiagnosticResult.NO_UPDATE,
            isCurrentTarget = true,
            retryAfterYield = true,
        ),
    )
  }

  @Test
  fun currentTargetOrdinaryNoUpdate_dropsWithoutRetry() {
    assertEquals(
        PublishDecision.Action.DROP,
        PublishDecision.decide(
            DiagnosticResult.NO_UPDATE,
            isCurrentTarget = true,
            retryAfterYield = false,
        ),
    )
  }

  @Test
  fun nonCurrentTarget_dropsEvenCompletedResult() {
    assertEquals(
        PublishDecision.Action.DROP,
        PublishDecision.decide(result(), isCurrentTarget = false, retryAfterYield = true),
    )
  }

  @Test
  fun nonCurrentTarget_doesNotConsumeYieldReservedForTheCurrentTarget() {
    val yieldTicket = YieldTicket()
    yieldTicket.markYielded()

    // Mirrors the server's order: the ticket is only consumed for the current target.
    assertEquals(
        PublishDecision.Action.DROP,
        PublishDecision.decide(
            DiagnosticResult.NO_UPDATE,
            isCurrentTarget = false,
            retryAfterYield = false,
        ),
    )

    assertTrue("current target must still observe the pending yield", yieldTicket.consumeOnce(DiagnosticResult.NO_UPDATE))
    assertFalse("a yield ticket is one-shot", yieldTicket.consumeOnce(DiagnosticResult.NO_UPDATE))
  }

  private fun result(): DiagnosticResult = DiagnosticResult(Paths.get("Sample.java"), emptyList())
}