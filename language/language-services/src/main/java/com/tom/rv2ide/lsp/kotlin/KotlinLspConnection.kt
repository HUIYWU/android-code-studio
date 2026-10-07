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

import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.DiagnosticResult

/**
 * Minimal abstraction over the active Kotlin LSP transport/backend connection.
 *
 * The goal of this interface is to decouple higher-level Kotlin editing logic
 * (document sync, requests, workspace setup, formatting, diagnostics wiring)
 * from the concrete backend process implementation.
 *
 * The interface intentionally stays small so different Kotlin backends can share
 * the same upper-layer integration with minimal behavioral drift.
 */
interface KotlinLspConnection {
  val isReady: Boolean

  fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit)
  /**
   * Starts the backend process.
   *
   * @return true only when the transport is ready to receive JSON-RPC messages. Callers must not
   *   send initialize or other requests when this returns false.
   */
  fun startServer(classpathProvider: KotlinClasspathProvider): Boolean


  fun sendRequest(method: String, params: JsonObject, callback: (JsonObject?) -> Unit)

  fun sendNotification(method: String, params: JsonObject)

  fun sendNotificationOrThrow(method: String, params: JsonObject)

  fun shutdown()
}
