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

/**
 * Pure result dispatch decision for speculative diagnostics.
 *
 * Staleness is expressed by identity rather than by a counter: the caller passes whether the analyzed
 * document is still the editor's current target. A document edit already turns the analyzer's result
 * into [DiagnosticResult.NO_UPDATE], so no additional sequence is needed here.
 */
internal object PublishDecision {
  enum class Action {
    DROP,
    RETRY,
    PUBLISH,
  }

  fun decide(
      result: DiagnosticResult,
      isCurrentTarget: Boolean,
      retryAfterYield: Boolean,
  ): Action =
      when {
        !isCurrentTarget -> Action.DROP
        result == DiagnosticResult.NO_UPDATE && retryAfterYield -> Action.RETRY
        result == DiagnosticResult.NO_UPDATE -> Action.DROP
        else -> Action.PUBLISH
      }
}