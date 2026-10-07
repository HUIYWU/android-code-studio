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
package com.tom.rv2ide.lsp.kotlin

import android.content.Context
import com.tom.rv2ide.lsp.kotlin.analysis.KotlinAnalysisBackendConfigurator
import com.tom.rv2ide.lsp.kotlin.analysis.KotlinAnalysisLspConnection
import com.tom.rv2ide.preferences.internal.LSPPreferences

/**
 * Minimal factory for creating the active Kotlin LSP backend connection.
 *
 * Available concrete backends currently include:
 * - [KotlinLspBackendId.FWCD] for fwcd/kotlin-language-server
 * - [KotlinLspBackendId.STUB] for a no-op structural placeholder
 * - [KotlinLspBackendId.ANALYSIS] for the in-process Kotlin Analysis API engine
 */
object KotlinLspBackendFactory {
  private fun activeBackendId(): KotlinLspBackendId {
    return when (LSPPreferences.kotlinLspBackend.trim().lowercase()) {
      LSPPreferences.KOTLIN_LSP_BACKEND_STUB -> KotlinLspBackendId.STUB
      LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS -> KotlinLspBackendId.ANALYSIS
      else -> KotlinLspBackendId.FWCD
    }
  }

  fun createSpec(context: Context): KotlinLspBackendSpec {
    return when (activeBackendId()) {
      KotlinLspBackendId.FWCD ->
          KotlinLspBackendSpec(
              id = KotlinLspBackendId.FWCD,
              connection = FwcdKotlinLspConnection(),
              configurator = FwcdKotlinLspBackendConfigurator,
          )
      KotlinLspBackendId.STUB ->
          KotlinLspBackendSpec(
              id = KotlinLspBackendId.STUB,
              connection = StubKotlinLspConnection(context),
              configurator = StubKotlinLspBackendConfigurator,
          )
      KotlinLspBackendId.ANALYSIS ->
          KotlinLspBackendSpec(
              id = KotlinLspBackendId.ANALYSIS,
              connection = KotlinAnalysisLspConnection(context.applicationInfo.sourceDir),
              configurator = KotlinAnalysisBackendConfigurator,
          )
    }
  }
}
