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

package com.tom.rv2ide.language.services.kotlin.backend.fwcd

import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.*
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.projectdata.logs.LogStream
import java.nio.file.Paths
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinNotificationHandler {

  companion object {
    private val log = LoggerFactory.getLogger(KotlinNotificationHandler::class.java)
  }

  private var diagnosticsCallback: ((DiagnosticResult) -> Unit)? = null

  fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) {
    this.diagnosticsCallback = callback
  }

  fun handle(obj: JsonObject) {
    val method = obj.get("method")?.asString ?: return
    val params = obj.getAsJsonObject("params")

    when (method) {
      "textDocument/publishDiagnostics" -> {
        KlsLogs.debug("Received diagnostics notification")
        handlePublishDiagnostics(params)
      }
      "window/showMessage" -> {
        val message = params?.get("message")?.asString
        KlsLogs.info("KLS window/showMessage: {}", message)
      }
      "window/logMessage" -> {
        handleLogMessage(params)
      }
      else -> {
        KlsLogs.debug("Unhandled notification: {}", method)
      }
    }
  }

  private fun handlePublishDiagnostics(params: JsonObject?) {
    params ?: return
    val uri = params.get("uri")?.asString ?: return
    val diagnosticsArray = params.getAsJsonArray("diagnostics") ?: return

    KlsLogs.info("Received {} diagnostics for: {}", diagnosticsArray.size(), uri)

    val diagnostics =
        diagnosticsArray.mapNotNull { element ->
          try {
            val diag = element.asJsonObject
            val range = diag.getAsJsonObject("range")
            val start = range.getAsJsonObject("start")
            val end = range.getAsJsonObject("end")

            // Extract diagnostic code (may be string or number)
            val code =
                when {
                  diag.has("code") && diag.get("code").isJsonPrimitive -> {
                    val codeElement = diag.get("code")
                    when {
                      codeElement.asJsonPrimitive.isString -> codeElement.asString
                      codeElement.asJsonPrimitive.isNumber -> codeElement.asString
                      else -> ""
                    }
                  }
                  else -> ""
                }

            DiagnosticItem(
                message = diag.get("message")?.asString ?: "",
                code = code,
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
                source = diag.get("source")?.asString ?: "kotlin",
                severity =
                    when (diag.get("severity")?.asInt) {
                      1 -> DiagnosticSeverity.ERROR
                      2 -> DiagnosticSeverity.WARNING
                      3 -> DiagnosticSeverity.INFO
                      4 -> DiagnosticSeverity.HINT
                      else -> DiagnosticSeverity.ERROR
                    },
            )
          } catch (e: Exception) {
            KlsLogs.error("Failed to parse diagnostic item", e)
            null
          }
        }

    val filePath =
        try {
          java.nio.file.Paths.get(java.net.URI(uri))
        } catch (e: Exception) {
          KlsLogs.error("Invalid URI: {}", uri, e)
          return
        }
    if (diagnostics.isNotEmpty()) {
      diagnosticsCallback?.invoke(
          DiagnosticResult(filePath, diagnostics, DiagnosticResult.CHANNEL_SERVER)
      )
    } else {
      // An empty publishDiagnostics payload is still semantically meaningful: it clears stale editor
      // diagnostics for this file. Dropping the callback here makes the IDE behave as if nothing changed.
      diagnosticsCallback?.invoke(
          DiagnosticResult(filePath, emptyList(), DiagnosticResult.CHANNEL_SERVER)
      )
    }

  }

  private fun handleLogMessage(params: JsonObject?) {
    val message = params?.get("message")?.asString
    val messageType = params?.get("type")?.asInt

    message?.let { originalMsg ->
      val msg = originalMsg.replace("async2    ", "")
      when {
        // Indexing started messages - match actual server output
        msg.contains("Updating symbol index", ignoreCase = true) ||
        msg.contains("Updating full symbol index", ignoreCase = true) ||
        msg.contains("building symbol index", ignoreCase = true) ||
        msg.contains("Triggering full workspace indexing", ignoreCase = true) ||
        msg.contains("Restoring cached index", ignoreCase = true) -> {
          LogStream.emitLineBlocking(msg)
        }

        msg.contains("updated full symbol", ignoreCase = true) -> {
          KlsLogs.info("Full symbol index completed")
          LogStream.emitLineBlocking(msg)
        }

        msg.contains("Loaded symbol index from cache in", ignoreCase = true) -> {
          KlsLogs.info("Symbol index loaded from cache")
          LogStream.emitLineBlocking(msg)
        }

        msg.contains("symbol index complete", ignoreCase = true) ||
        msg.contains("indexing complete", ignoreCase = true) -> {
          KlsLogs.debugThrottled(
              "kls:generic-indexing-complete",
              1500L,
              "KLS generic indexing completion: {}",
              msg,
          )
        }
        // "Updated symbol index" is just progress, NOT completion - keep emitting but don't stop
        msg.contains("Updated symbol index in", ignoreCase = true) -> {
          if (shouldEmitKlsProgressToLogStream(msg)) {
            LogStream.emitLineBlocking(msg)
          }
        }

        // Indexing failed/error messages
        msg.contains("Error while updating symbol index", ignoreCase = true) ||
        msg.contains("Failed to build symbol index", ignoreCase = true) -> {
          KlsLogs.warn("Indexing error detected")
          LogStream.emitLineBlocking(msg)
        }

        // Emit any other symbol-related messages while indexing
        msg.contains("symbol", ignoreCase = true) -> {
          if (shouldEmitKlsProgressToLogStream(msg)) {
            LogStream.emitLineBlocking(msg)
          }
        }
      }
    }

    when (messageType) {
      1 -> KlsLogs.error("KLS: {}", message)
      2 -> KlsLogs.warn("KLS: {}", message)
      3 -> {
        if (shouldThrottleKlsInfo(message)) {
          KlsLogs.infoThrottled(normalizeKlsThrottleKey(message), 1500L, "KLS: {}", message)
        } else {
          KlsLogs.info("KLS: {}", message)
        }
      }
      4 -> {
        if (shouldThrottleKlsDebug(message)) {
          KlsLogs.debugThrottled(normalizeKlsThrottleKey(message), 1500L, "KLS: {}", message)
        } else {
          KlsLogs.debug("KLS: {}", message)
        }
      }
      else -> KlsLogs.trace("KLS: {}", message)
    }
  }

  private fun shouldEmitKlsProgressToLogStream(message: String): Boolean {
    return !shouldThrottleKlsInfo(message)
  }

  private fun shouldThrottleKlsInfo(message: String?): Boolean {
    val msg = message ?: return false
    return msg.contains("Updating symbol index", ignoreCase = true) ||
        msg.contains("Updated symbol index in", ignoreCase = true) ||
        msg.contains("PERF SourcePath.doCompile", ignoreCase = true) ||
        msg.contains("Watching for build changes", ignoreCase = true) ||
        msg.contains("Added generated source", ignoreCase = true) ||
        msg.contains("Added ", ignoreCase = true) && msg.contains("generated source paths", ignoreCase = true) ||
        msg.contains("Compiler/provider classpath diff", ignoreCase = true) ||
        msg.contains("Provider-only interesting paths", ignoreCase = true) ||
        msg.contains("Compiler-only interesting paths", ignoreCase = true)
  }

  private fun shouldThrottleKlsDebug(message: String?): Boolean {
    val msg = message ?: return false
    return msg.contains("Received diagnostics notification", ignoreCase = true) ||
        msg.contains("Unhandled notification", ignoreCase = true) ||
        msg.contains("diagnostics.recv", ignoreCase = true) ||
        msg.contains("diagnostics.clear", ignoreCase = true)
  }

  private fun normalizeKlsThrottleKey(message: String?): String {
    val msg = message ?: return "kls:null"
    return when {
      msg.contains("Updating symbol index", ignoreCase = true) -> "kls:update-symbol-index"
      msg.contains("Updated symbol index in", ignoreCase = true) -> "kls:updated-symbol-index"
      msg.contains("PERF SourcePath.doCompile", ignoreCase = true) -> "kls:source-compile-perf"
      msg.contains("Watching for build changes", ignoreCase = true) -> "kls:watch-build-changes"
      msg.contains("Added generated source", ignoreCase = true) -> "kls:added-generated-source"
      msg.contains("generated source paths", ignoreCase = true) -> "kls:generated-source-paths"
      msg.contains("Compiler/provider classpath diff", ignoreCase = true) -> "kls:classpath-diff"
      msg.contains("Provider-only interesting paths", ignoreCase = true) -> "kls:provider-only-paths"
      msg.contains("Compiler-only interesting paths", ignoreCase = true) -> "kls:compiler-only-paths"
      msg.contains("Received diagnostics notification", ignoreCase = true) -> "kls:diagnostics-notification"
      msg.contains("Unhandled notification", ignoreCase = true) -> "kls:unhandled-notification"
      msg.contains("diagnostics.recv", ignoreCase = true) -> "kls:diagnostics-recv"
      msg.contains("diagnostics.clear", ignoreCase = true) -> "kls:diagnostics-clear"
      else -> "kls:${msg.take(80)}"
    }
  }
}