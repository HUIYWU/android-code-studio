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
package com.tom.rv2ide.language.services.kotlin

import android.content.Context
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.DiagnosticResult

/**
 * Placeholder backend used to prove the pluggable Kotlin backend wiring.
 *
 * This does not start a real server yet. It only keeps upper layers stable while
 * a future backend implementation is integrated behind the same interfaces.
 */
class StubKotlinLspConnection(context: Context) : KotlinLspConnection {
  private val appContext = context.applicationContext
  private var diagnosticsCallback: ((DiagnosticResult) -> Unit)? = null

  override val isReady: Boolean
    get() = false

  override fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) {
    diagnosticsCallback = callback
    KslLogs.info("Stub Kotlin backend installed diagnostics callback")
  }

  override fun startServer(classpathProvider: KotlinClasspathProvider): Boolean {
    KslLogs.warn(
        "Stub Kotlin backend selected. No real Kotlin language server process will be started. projectFilesDir={}",
        appContext.filesDir.absolutePath,
    )
    return false
  }

  override fun sendRequest(method: String, params: JsonObject, callback: (JsonObject?) -> Unit) {
    KslLogs.warn("Stub Kotlin backend ignoring request: {}", method)
    callback.invoke(null)
  }

  override fun sendNotification(method: String, params: JsonObject) {
    KslLogs.warn("Stub Kotlin backend ignoring notification: {}", method)
  }

  override fun sendNotificationOrThrow(method: String, params: JsonObject) {
    KslLogs.warn("Stub Kotlin backend ignoring notification: {}", method)
  }

  override fun shutdown() {
    KslLogs.info("Stub Kotlin backend shutdown")
    diagnosticsCallback = null
  }
}
