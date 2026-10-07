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

package com.tom.rv2ide.language.services.kotlin.format

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspConnection
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.language.services.kotlin.settings.KotlinLspSettings
import com.tom.rv2ide.lsp.models.CodeFormatResult
import com.tom.rv2ide.lsp.models.FormatCodeParams
import com.tom.rv2ide.lsp.models.IndexedTextEdit
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinCodeFormatProvider(
    private val connection: KotlinLspConnection,
    private val backendConfigurator: KotlinLspBackendConfigurator,
) {

  companion object {
    private val log = LoggerFactory.getLogger(KotlinCodeFormatProvider::class.java)
    private const val FORMAT_TIMEOUT = 15000L
  }

  fun format(filePath: Path, params: FormatCodeParams?): CodeFormatResult {
    if (params == null) {
      KlsLogs.warn("Format params is null")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!(filePath.toString().endsWith(".kt") || filePath.toString().endsWith(".kts"))) {
      KlsLogs.debug("Not a Kotlin file: {}", filePath)
      return CodeFormatResult(false, mutableListOf())
    }

    KlsLogs.info("Starting format for: {}", filePath)

    return runBlocking {
      try {
        formatDocument(filePath, params)
      } catch (e: Exception) {
        KlsLogs.error("Error formatting document", e)
        CodeFormatResult(false, mutableListOf())
      }
    }
  }

  private suspend fun formatDocument(filePath: Path, params: FormatCodeParams): CodeFormatResult {
    val deferred = CompletableDeferred<CodeFormatResult>()

    val uri = filePath.toUri().toString()

    // Read current content for offset calculation
    val content = params.content?.toString() ?: filePath.toFile().readText()

    val style = KotlinLspSettings.getCodeFormatStyle() ?: "google"
    backendConfigurator.applyFormattingStyle(connection, style)

    val lspParams =
        JsonObject().apply {
          add("textDocument", JsonObject().apply { addProperty("uri", uri) })
          add(
              "options",
              JsonObject().apply {
                val currentIndent =
                    when (style) {
                      "google",
                      "facebook" -> 2
                      "kotlinlang" -> 4
                      else -> 4
                    }
                addProperty("tabSize", currentIndent)
                addProperty("insertSpaces", true)
                addProperty("trimTrailingWhitespace", true)
                addProperty("insertFinalNewline", true)
                addProperty("trimFinalNewlines", true)
              },
          )
        }

    KlsLogs.info("Sending format request for: {}", uri)
    KlsLogs.debug("Format params: {}", lspParams.toString())

    connection.sendRequest("textDocument/formatting", lspParams) { result ->
      try {
        KlsLogs.info("Received format response")

        if (result == null) {
          KlsLogs.warn("Format result is null - no changes needed or error occurred")
          deferred.complete(CodeFormatResult(false, mutableListOf()))
          return@sendRequest
        }

        KlsLogs.debug("Format result: {}", result.toString())

        val edits = convertToIndexedTextEdits(result, content)
        val success = edits.isNotEmpty()

        if (success) {
          KlsLogs.info("Format successful: {} edits", edits.size)
          for (i in edits.indices) {
            val edit = edits[i]
            val preview = edit.newText.take(50).split('\n').joinToString(" ")
            KlsLogs.debug(
                "Edit {}: [{} - {}] length={}, preview='{}'",
                i + 1,
                edit.start,
                edit.end,
                edit.newText.length,
                preview,
            )
          }
        } else {
          KlsLogs.warn("Format returned no edits")
        }

        // Create result with IndexedTextEdits
        val formatResult = CodeFormatResult(success)
        edits.forEach { formatResult.indexedTextEdits.add(it) }

        deferred.complete(formatResult)
      } catch (e: Exception) {
        KlsLogs.error("Error processing format result", e)
        deferred.complete(CodeFormatResult(false, mutableListOf()))
      }
    }

    val result = withTimeoutOrNull(FORMAT_TIMEOUT) { deferred.await() }

    if (result == null) {
      KlsLogs.error("Format request timed out after {}ms", FORMAT_TIMEOUT)
      return CodeFormatResult(false, mutableListOf())
    }

    return result
  }

  private fun convertToIndexedTextEdits(
      result: JsonObject?,
      content: String,
  ): List<IndexedTextEdit> {
    try {
      if (result == null) {
        KlsLogs.debug("Result is null")
        return emptyList()
      }

      val editsArray: JsonArray =
          when {
            result.has("result") -> {
              val resultField = result.get("result")
              when {
                resultField.isJsonArray -> {
                  KlsLogs.debug("Result has 'result' field with JsonArray")
                  resultField.asJsonArray
                }
                resultField.isJsonNull -> {
                  KlsLogs.debug("Result field is null")
                  return emptyList()
                }
                else -> {
                  KlsLogs.warn("Result field is not an array")
                  return emptyList()
                }
              }
            }
            result.isJsonArray -> {
              KlsLogs.debug("Result is directly a JsonArray")
              result.asJsonArray
            }
            else -> {
              KlsLogs.warn("Result is neither object with 'result' field nor array")
              return emptyList()
            }
          }

      if (editsArray.size() == 0) {
        KlsLogs.debug("Edits array is empty")
        return emptyList()
      }

      KlsLogs.debug("Converting {} edits", editsArray.size())

      // Split content into lines for offset calculation
      val lines = content.split("\n")

      return editsArray.mapNotNull { element ->
        try {
          val edit = element.asJsonObject
          val range = edit.getAsJsonObject("range")
          val start = range.getAsJsonObject("start")
          val end = range.getAsJsonObject("end")
          val newText = edit.get("newText")?.asString ?: ""

          val startLine = start.get("line").asInt
          val startChar = start.get("character").asInt
          val endLine = end.get("line").asInt
          val endChar = end.get("character").asInt

          KlsLogs.debug(
              "LSP edit: [{},{}] to [{},{}], text length: {}",
              startLine,
              startChar,
              endLine,
              endChar,
              newText.length,
          )

          // Convert line/column to character offsets
          val startOffset = lineColumnToOffset(lines, startLine, startChar)
          val endOffset = lineColumnToOffset(lines, endLine, endChar)

          KlsLogs.debug("Converted to offsets: {} to {}", startOffset, endOffset)

          val indexedEdit = IndexedTextEdit()
          indexedEdit.newText = newText
          indexedEdit.start = startOffset
          indexedEdit.end = endOffset
          indexedEdit
        } catch (e: Exception) {
          KlsLogs.error("Error converting text edit", e)
          null
        }
      }
    } catch (e: Exception) {
      KlsLogs.error("Error parsing format result", e)
      return emptyList()
    }
  }

  private fun lineColumnToOffset(lines: List<String>, line: Int, column: Int): Int {
    var offset = 0

    // Add lengths of all lines before the target line
    for (i in 0 until minOf(line, lines.size)) {
      offset += lines[i].length + 1 // +1 for newline character
    }

    // Add the column offset within the target line
    if (line < lines.size) {
      offset += minOf(column, lines[line].length)
    } else {
      // If line is beyond content, just add the column
      offset += column
    }

    return offset
  }
}
