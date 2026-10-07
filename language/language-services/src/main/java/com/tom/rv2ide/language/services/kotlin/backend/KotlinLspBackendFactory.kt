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
package com.tom.rv2ide.language.services.kotlin.backend

import android.content.Context
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisLspConnection
import com.tom.rv2ide.language.services.kotlin.backend.fwcd.FwcdKotlinLspBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.fwcd.FwcdKotlinLspConnection
import com.tom.rv2ide.language.services.kotlin.backend.stub.StubKotlinLspBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.stub.StubKotlinLspConnection
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
              connection = FwcdKotlinLspConnection(),
              createConfigurator = ::FwcdKotlinLspBackendConfigurator,
          )
      KotlinLspBackendId.STUB ->
          KotlinLspBackendSpec(
              connection = StubKotlinLspConnection(context),
              createConfigurator = ::StubKotlinLspBackendConfigurator,
          )
      KotlinLspBackendId.ANALYSIS ->
          KotlinLspBackendSpec(
              connection = KotlinAnalysisLspConnection(context.applicationInfo.sourceDir),
              createConfigurator = ::KotlinAnalysisBackendConfigurator,
          )
    }
  }
}
