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
package com.tom.rv2ide.lsp.java

import com.tom.rv2ide.lsp.java.providers.DiagnosticInteractiveYieldState
import com.tom.rv2ide.lsp.models.DiagnosticResult
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticDispatchDecisionTest {

  @Test
  fun currentYieldedNoUpdate_retries() {
    assertEquals(
        DiagnosticDispatchDecision.Action.RETRY,
        decide(DiagnosticResult.NO_UPDATE, requestedGeneration = 4L, currentGeneration = 4L, yielded = true),
    )
  }

  @Test
  fun currentOrdinaryNoUpdate_dropsWithoutRetry() {
    assertEquals(
        DiagnosticDispatchDecision.Action.DROP,
        decide(DiagnosticResult.NO_UPDATE, requestedGeneration = 4L, currentGeneration = 4L, yielded = false),
    )
  }

  @Test
  fun currentRealResult_publishes() {
    assertEquals(
        DiagnosticDispatchDecision.Action.PUBLISH,
        decide(result(), requestedGeneration = 4L, currentGeneration = 4L, yielded = false),
    )
  }

  @Test
  fun staleResult_dropsEvenWhenYieldWasRecorded() {
    assertEquals(
        DiagnosticDispatchDecision.Action.DROP,
        decide(DiagnosticResult.NO_UPDATE, requestedGeneration = 3L, currentGeneration = 4L, yielded = true),
    )
  }

  @Test
  fun staleRealResult_neverPublishes() {
    assertEquals(
        DiagnosticDispatchDecision.Action.DROP,
        decide(result(), requestedGeneration = 3L, currentGeneration = 4L, yielded = false),
    )
  }

  @Test
  fun yieldedCurrentAttempt_retriesThenLaterCurrentResultPublishes() {
    val yieldState = DiagnosticInteractiveYieldState()
    yieldState.markYielded()

    val retryAfterYield = yieldState.consumeRetryAfter(DiagnosticResult.NO_UPDATE)
    assertEquals(
        DiagnosticDispatchDecision.Action.RETRY,
        decide(
            DiagnosticResult.NO_UPDATE,
            requestedGeneration = 7L,
            currentGeneration = 7L,
            yielded = retryAfterYield,
        ),
    )

    assertEquals(
        DiagnosticDispatchDecision.Action.PUBLISH,
        decide(result(), requestedGeneration = 8L, currentGeneration = 8L, yielded = false),
    )
  }

  @Test
  fun staleAttempt_doesNotConsumeYieldReservedForCurrentAttempt() {
    val yieldState = DiagnosticInteractiveYieldState()
    yieldState.markYielded()

    val staleGeneration = 10L
    val currentGeneration = 11L
    // This mirrors the server's order: a stale attempt must not call consumeRetryAfter().
    val staleRetryAfterYield =
        if (staleGeneration == currentGeneration) {
          yieldState.consumeRetryAfter(DiagnosticResult.NO_UPDATE)
        } else {
          false
        }
    assertEquals(
        DiagnosticDispatchDecision.Action.DROP,
        decide(
            DiagnosticResult.NO_UPDATE,
            requestedGeneration = staleGeneration,
            currentGeneration = currentGeneration,
            yielded = staleRetryAfterYield,
        ),
    )

    val currentRetryAfterYield = yieldState.consumeRetryAfter(DiagnosticResult.NO_UPDATE)
    assertEquals(
        DiagnosticDispatchDecision.Action.RETRY,
        decide(
            DiagnosticResult.NO_UPDATE,
            requestedGeneration = currentGeneration,
            currentGeneration = currentGeneration,
            yielded = currentRetryAfterYield,
        ),
    )
  }

  private fun decide(
      result: DiagnosticResult,
      requestedGeneration: Long,
      currentGeneration: Long,
      yielded: Boolean,
  ): DiagnosticDispatchDecision.Action =
      DiagnosticDispatchDecision.decide(
          result,
          requestedGeneration,
          currentGeneration,
          retryAfterInteractiveYield = yielded,
      )

  private fun result(): DiagnosticResult = DiagnosticResult(Paths.get("Sample.java"), emptyList())
}