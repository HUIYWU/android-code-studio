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
package com.tom.rv2ide.lsp.clang

import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.*
import java.nio.file.Paths

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ClangNotificationHandler(private val documentManager: ClangDocumentManager? = null) {

  private var diagnosticsCallback: ((DiagnosticResult) -> Unit)? = null

  fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) {
    this.diagnosticsCallback = callback
  }

  fun handle(obj: JsonObject) {
    val method = obj.get("method")?.asString ?: return
    val params = obj.getAsJsonObject("params")

    when (method) {
      "textDocument/publishDiagnostics" -> {
        ClangLogs.debug("Received diagnostics notification")
        handlePublishDiagnostics(params)
      }
      "textDocument/clangd.fileStatus" -> {
        ClangLogs.debug("Received clangd fileStatus notification")
      }
      "window/showMessage" -> {
        val message = params?.get("message")?.asString
        ClangLogs.debug("Clangd window/showMessage: {}", message)
      }
      "window/logMessage" -> {
        handleLogMessage(params)
      }
      else -> {
        ClangLogs.debug("Unhandled notification: {}", method)
      }
    }
  }

  private fun handlePublishDiagnostics(params: JsonObject?) {
    params ?: return
    val uri = params.get("uri")?.asString ?: return
    val diagnosticsArray = params.getAsJsonArray("diagnostics") ?: return

    val diagnostics =
        diagnosticsArray.map { element ->
          val diag = element.asJsonObject
          val range = diag.getAsJsonObject("range")
          val start = range.getAsJsonObject("start")
          val end = range.getAsJsonObject("end")

          DiagnosticItem(
              message = diag.get("message")?.asString ?: "",
              code = diag.get("code")?.asString ?: "",
              range =
                  com.tom.rv2ide.models.Range(
                      start =
                          com.tom.rv2ide.models.Position(
                              start.get("line").asInt,
                              start.get("character").asInt,
                          ),
                      end =
                          com.tom.rv2ide.models.Position(
                              end.get("line").asInt,
                              end.get("character").asInt,
                          ),
                  ),
              source = diag.get("source")?.asString ?: "clangd",
              severity =
                  when (diag.get("severity")?.asInt) {
                    1 -> DiagnosticSeverity.ERROR
                    2 -> DiagnosticSeverity.WARNING
                    3 -> DiagnosticSeverity.INFO
                    4 -> DiagnosticSeverity.HINT
                    else -> DiagnosticSeverity.ERROR
                  },
          )
        }

    val filePath =
        try {
          Paths.get(java.net.URI(uri))
        } catch (e: Exception) {
          ClangLogs.error("Failed to parse clangd diagnostics URI: {}", uri, e)
          return
        }

    diagnosticsCallback?.invoke(DiagnosticResult(filePath, diagnostics))
  }

  private fun handleLogMessage(params: JsonObject?) {
    val message = params?.get("message")?.asString
    val messageType = params?.get("type")?.asInt
    when (messageType) {
      1 -> ClangLogs.error("Clangd: {}", message)
      2 -> ClangLogs.warn("Clangd: {}", message)
      3 -> ClangLogs.info("Clangd: {}", message)
      4 -> ClangLogs.debug("Clangd: {}", message)
      else -> ClangLogs.trace("Clangd: {}", message)
    }
  }
}
