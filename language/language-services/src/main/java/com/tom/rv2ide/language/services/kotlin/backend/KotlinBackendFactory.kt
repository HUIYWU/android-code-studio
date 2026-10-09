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
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisBackendConnection
import com.tom.rv2ide.language.services.kotlin.backend.fwcd.FwcdKotlinBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.fwcd.FwcdKotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.backend.stub.StubKotlinBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.stub.StubKotlinBackendConnection
import com.tom.rv2ide.preferences.internal.LSPPreferences
import com.tom.rv2ide.language.services.kotlin.backend.analysis.AnalysisKotlinSemanticBackend
import com.tom.rv2ide.language.services.kotlin.backend.fwcd.FwcdKotlinSemanticBackend
import com.tom.rv2ide.language.services.kotlin.completion.KotlinJavaCompilerBridge
import com.tom.rv2ide.language.services.kotlin.request.KotlinRequestHandler
import com.tom.rv2ide.language.services.kotlin.semantic.EmptyKotlinSemanticBackend

/**
 * Minimal factory for creating the active Kotlin LSP backend connection.
 *
 * Available concrete backends currently include:
 * - [KotlinBackendId.FWCD] for fwcd/kotlin-language-server
 * - [KotlinBackendId.STUB] for a no-op structural placeholder
 * - [KotlinBackendId.ANALYSIS] for the in-process Kotlin Analysis API engine
 */
object KotlinBackendFactory {
  private fun activeBackendId(): KotlinBackendId {
    return when (LSPPreferences.kotlinLspBackend.trim().lowercase()) {
      LSPPreferences.KOTLIN_LSP_BACKEND_STUB -> KotlinBackendId.STUB
      LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS -> KotlinBackendId.ANALYSIS
      else -> KotlinBackendId.FWCD
    }
  }

  internal fun createSpec(context: Context): KotlinBackendSpec = when (activeBackendId()) {
    KotlinBackendId.FWCD -> {
      val connection = FwcdKotlinBackendConnection()
      KotlinBackendSpec(
          connection = connection,
          createConfigurator = ::FwcdKotlinBackendConfigurator,
          createSemanticBackend = { backendContext, documentSync, validator ->
            val handler = KotlinRequestHandler(connection, documentSync, validator)
            handler.setJavaCompilerBridge(KotlinJavaCompilerBridge(backendContext.workspace))
            FwcdKotlinSemanticBackend(handler)
          },
      )
    }
    KotlinBackendId.STUB -> KotlinBackendSpec(
        connection = StubKotlinBackendConnection(context),
        createConfigurator = ::StubKotlinBackendConfigurator,
        createSemanticBackend = { _, _, _ -> EmptyKotlinSemanticBackend },
    )
    KotlinBackendId.ANALYSIS -> {
      val connection = KotlinAnalysisBackendConnection(context.applicationInfo.sourceDir)
      KotlinBackendSpec(
          connection = connection,
          createConfigurator = ::KotlinAnalysisBackendConfigurator,
          createSemanticBackend = { _, _, validator -> AnalysisKotlinSemanticBackend(connection, validator) },
      )
    }
  }
}
