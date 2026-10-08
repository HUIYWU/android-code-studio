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

import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentKind
import com.tom.rv2ide.language.services.kotlin.document.kotlinDocumentKind

/**
 * Shared lifecycle and document capability contract for Kotlin backends.
 *
 * The contract is used by both the external FWCD process backend and the in-process
 * Analysis backend. JSON-RPC methods remain available because FWCD uses them directly;
 * Analysis handles only the subset needed by the shared document and diagnostics layer.
 */
interface KotlinBackendConnection {
  val isReady: Boolean
  val state: KotlinBackendState
    get() = if (isReady) KotlinBackendState.READY else KotlinBackendState.NEW
  val generation: Long
    get() = 0L
  val isInitialized: Boolean
    get() = false

  fun supportsDocument(path: java.nio.file.Path): Boolean =
      path.kotlinDocumentKind() == KotlinDocumentKind.KOTLIN_SOURCE

  fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit)
  /**
   * Starts the backend process.
   *
   * @return true only when the transport is ready to receive JSON-RPC messages. Callers must not
   *   send initialize or other requests when this returns false.
   */
  fun startBackend(classpathProvider: KotlinProjectClasspathProvider): Boolean

  fun refreshEnvironment(classpathProvider: KotlinProjectClasspathProvider): Boolean = isReady

  fun initialize(params: JsonObject, callback: (JsonObject?) -> Unit) {
    sendRequest("initialize", params, callback)
  }

  fun markInitialized() = Unit

  fun markInitializationFailed() = close()

  fun sendRequest(method: String, params: JsonObject, callback: (JsonObject?) -> Unit)

  fun sendNotification(method: String, params: JsonObject)

  fun sendNotificationOrThrow(method: String, params: JsonObject)
  fun close()

}
