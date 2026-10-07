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

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyzeScheduleDecisionTest {

  @Test
  fun inactiveTimer_startsUsingTheLongestRequiredDelay() {
    assertPlan(
        intervalMs = 1_200L,
        action = AnalyzeScheduleDecision.Action.START,
        timerStarted = false,
        currentIntervalMs = 400L,
        baseIntervalMs = 400L,
        interactiveDelayMs = 1_200L,
        forceRestart = false,
    )
  }

  @Test
  fun explicitRetry_restartsEvenWhenIntervalIsUnchanged() {
    assertPlan(
        intervalMs = 400L,
        action = AnalyzeScheduleDecision.Action.RESTART,
        timerStarted = true,
        currentIntervalMs = 400L,
        baseIntervalMs = 400L,
        interactiveDelayMs = 0L,
        forceRestart = true,
    )
  }

  @Test
  fun longerInteractiveGrace_restartsExistingTimer() {
    assertPlan(
        intervalMs = 1_000L,
        action = AnalyzeScheduleDecision.Action.RESTART,
        timerStarted = true,
        currentIntervalMs = 400L,
        baseIntervalMs = 400L,
        interactiveDelayMs = 1_000L,
        forceRestart = false,
    )
  }

  @Test
  fun shorterOrEqualDelay_keepsExistingTimerWithoutForcedRestart() {
    assertPlan(
        intervalMs = 400L,
        action = AnalyzeScheduleDecision.Action.KEEP,
        timerStarted = true,
        currentIntervalMs = 400L,
        baseIntervalMs = 400L,
        interactiveDelayMs = 200L,
        forceRestart = false,
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun nonPositiveBaseInterval_isRejected() {
    AnalyzeScheduleDecision.decide(
        timerStarted = false,
        currentIntervalMs = 0L,
        baseIntervalMs = 0L,
        interactiveDelayMs = 0L,
        forceRestart = false,
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun negativeInteractiveDelay_isRejected() {
    AnalyzeScheduleDecision.decide(
        timerStarted = false,
        currentIntervalMs = 400L,
        baseIntervalMs = 400L,
        interactiveDelayMs = -1L,
        forceRestart = false,
    )
  }

  private fun assertPlan(
      intervalMs: Long,
      action: AnalyzeScheduleDecision.Action,
      timerStarted: Boolean,
      currentIntervalMs: Long,
      baseIntervalMs: Long,
      interactiveDelayMs: Long,
      forceRestart: Boolean,
  ) {
    val plan =
        AnalyzeScheduleDecision.decide(
            timerStarted,
            currentIntervalMs,
            baseIntervalMs,
            interactiveDelayMs,
            forceRestart,
        )

    assertEquals(intervalMs, plan.intervalMs)
    assertEquals(action, plan.action)
  }
}