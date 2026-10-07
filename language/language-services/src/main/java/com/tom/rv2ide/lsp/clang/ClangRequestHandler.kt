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

import android.os.SystemClock
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.*
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ClangRequestHandler(
    private val processManager: ClangServerProcessManager,
    private val documentManager: ClangDocumentManager,
) {
    companion object {
        private const val COMPLETION_TIMEOUT = 10000L
        private const val DEBOUNCE_DELAY = 100L
    }

  private val completionConverter = ClangCompletionConverter()
  private val lastCompletionRequest = AtomicLong(0)

  suspend fun complete(params: CompletionParams): CompletionResult = coroutineScope {
    if (params.position.line < 0 || params.position.column < 0) {
      ClangLogs.info(
          "completion skipped: invalid position file={}, line={}, column={}",
          params.file,
          params.position.line,
          params.position.column,
      )
      return@coroutineScope CompletionResult(emptyList())
    }

    val requestTimestamp = System.currentTimeMillis()
    val startMs = SystemClock.elapsedRealtime()
    val threadName = Thread.currentThread().name
    val uri = params.file.toUri().toString()
    val content = params.content?.toString()
    val contentLength = content?.length ?: -1
    val currentVersionBeforeSync = documentManager.getDocumentVersion(uri)

    ClangLogs.info(
        "completion requested: file={}, uri={}, line={}, column={}, prefix='{}', contentLength={}, currentVersionBeforeSync={}, debounceMs={}, timeoutMs={}, thread={}",
        params.file,
        uri,
        params.position.line,
        params.position.column,
        params.prefix ?: "",
        contentLength,
        currentVersionBeforeSync,
        DEBOUNCE_DELAY,
        COMPLETION_TIMEOUT,
        threadName,
    )

    lastCompletionRequest.set(requestTimestamp)
    delay(DEBOUNCE_DELAY)

    if (lastCompletionRequest.get() != requestTimestamp) {
      val elapsedMs = SystemClock.elapsedRealtime() - startMs
      ClangLogs.info(
          "completion cancelled by newer request: file={}, uri={}, line={}, column={}, elapsedMs={}, thread={}",
          params.file,
          uri,
          params.position.line,
          params.position.column,
          elapsedMs,
          threadName,
      )
      return@coroutineScope CompletionResult(emptyList())
    }

    try {
      val syncStartMs = SystemClock.elapsedRealtime()
      documentManager.syncDocument(params.file, content)
      val syncCostMs = SystemClock.elapsedRealtime() - syncStartMs
      val currentVersionAfterSync = documentManager.getDocumentVersion(uri)

      ClangLogs.info(
          "completion syncDocument done: file={}, uri={}, versionBefore={}, versionAfter={}, syncCostMs={}, thread={}",
          params.file,
          uri,
          currentVersionBeforeSync,
          currentVersionAfterSync,
          syncCostMs,
          threadName,
      )

      val lspParams =
          JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", uri) })
            add(
                "position",
                JsonObject().apply {
                  addProperty("line", params.position.line)
                  addProperty("character", params.position.column)
                },
            )
            add("context", JsonObject().apply { addProperty("triggerKind", 1) })
          }

      ClangLogs.info(
          "completion sending request: uri={}, line={}, column={}, versionAfterSync={}, thread={}",
          uri,
          params.position.line,
          params.position.column,
          currentVersionAfterSync,
          threadName,
      )

      val requestStartMs = SystemClock.elapsedRealtime()
      val result =
          withTimeoutOrNull(COMPLETION_TIMEOUT) {
            withContext(Dispatchers.IO) {
              processManager
                  .sendRequest("textDocument/completion", lspParams)
                  .get(COMPLETION_TIMEOUT, TimeUnit.MILLISECONDS)
            }
          }

      if (result == null) {
        val totalElapsedMs = SystemClock.elapsedRealtime() - startMs
        val requestCostMs = SystemClock.elapsedRealtime() - requestStartMs
        ClangLogs.warn(
            "completion request timed out or returned null: uri={}, line={}, column={}, versionAfterSync={}, requestCostMs={}, totalElapsedMs={}, thread={}",
            uri,
            params.position.line,
            params.position.column,
            currentVersionAfterSync,
            requestCostMs,
            totalElapsedMs,
            threadName,
        )
        return@coroutineScope CompletionResult(emptyList())
      }

      val parseStartMs = SystemClock.elapsedRealtime()
            val items = parseCompletionItems(result, params.prefix ?: "")

      val parseCostMs = SystemClock.elapsedRealtime() - parseStartMs
      val requestCostMs = SystemClock.elapsedRealtime() - requestStartMs
      val totalElapsedMs = SystemClock.elapsedRealtime() - startMs

      ClangLogs.info(
          "completion response: uri={}, line={}, column={}, versionAfterSync={}, itemCount={}, requestCostMs={}, parseCostMs={}, totalElapsedMs={}, resultType={}, thread={}",
          uri,
          params.position.line,
          params.position.column,
          currentVersionAfterSync,
          items.size,
          requestCostMs,
          parseCostMs,
          totalElapsedMs,
          describeJsonElement(result),
          threadName,
      )

      CompletionResult(items)
    } catch (e: Exception) {
      val totalElapsedMs = SystemClock.elapsedRealtime() - startMs
      ClangLogs.error(
          "Exception in complete method: file={}, uri={}, line={}, column={}, totalElapsedMs={}, thread={}",
          params.file,
          uri,
          params.position.line,
          params.position.column,
          totalElapsedMs,
          threadName,
          e,
      )
      CompletionResult(emptyList())
    }
  }

  suspend fun findReferences(params: ReferenceParams): ReferenceResult =
      withContext(Dispatchers.IO) {
        documentManager.ensureDocumentOpen(params.file)

        val lspParams =
            JsonObject().apply {
              add(
                  "textDocument",
                  JsonObject().apply { addProperty("uri", params.file.toUri().toString()) },
              )
              add(
                  "position",
                  JsonObject().apply {
                    addProperty("line", params.position.line)
                    addProperty("character", params.position.column)
                  },
              )
              add(
                  "context",
                  JsonObject().apply {
                    addProperty("includeDeclaration", params.includeDeclaration)
                  },
              )
            }

        val result =
            withTimeoutOrNull(5000) {
              processManager
                  .sendRequest("textDocument/references", lspParams)
                  .get(5000, TimeUnit.MILLISECONDS)
            } ?: return@withContext ReferenceResult(emptyList())

        ReferenceResult(convertToLocations(result))
      }

  suspend fun findDefinition(params: DefinitionParams): DefinitionResult =
      withContext(Dispatchers.IO) {
        documentManager.ensureDocumentOpen(params.file)

        val lspParams =
            JsonObject().apply {
              add(
                  "textDocument",
                  JsonObject().apply { addProperty("uri", params.file.toUri().toString()) },
              )
              add(
                  "position",
                  JsonObject().apply {
                    addProperty("line", params.position.line)
                    addProperty("character", params.position.column)
                  },
              )
            }

        val result =
            withTimeoutOrNull(5000) {
              processManager
                  .sendRequest("textDocument/definition", lspParams)
                  .get(5000, TimeUnit.MILLISECONDS)
            } ?: return@withContext DefinitionResult(emptyList())

        DefinitionResult(convertToLocations(result))
      }

  suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp =
      withContext(Dispatchers.IO) {
        documentManager.ensureDocumentOpen(params.file)

        val lspParams =
            JsonObject().apply {
              add(
                  "textDocument",
                  JsonObject().apply { addProperty("uri", params.file.toUri().toString()) },
              )
              add(
                  "position",
                  JsonObject().apply {
                    addProperty("line", params.position.line)
                    addProperty("character", params.position.column)
                  },
              )
            }

        val result =
            withTimeoutOrNull(5000) {
              processManager
                  .sendRequest("textDocument/signatureHelp", lspParams)
                  .get(5000, TimeUnit.MILLISECONDS)
            } ?: return@withContext SignatureHelp(emptyList(), 0, 0)

        convertToSignatureHelp(result)
      }

  private fun parseCompletionItems(result: JsonElement?, prefix: String): List<CompletionItem> {
    if (result == null || result.isJsonNull) {
      return emptyList()
    }

    val itemsArray =
        when {
          result.isJsonArray -> result.asJsonArray
          result.isJsonObject && result.asJsonObject.has("items") ->
              result.asJsonObject.getAsJsonArray("items")
          else -> JsonArray()
        }

    return completionConverter.convert(itemsArray, prefix)
  }

  private fun describeJsonElement(result: JsonElement?): String {
    return when {
      result == null -> "null"
      result.isJsonNull -> "jsonNull"
      result.isJsonArray -> "array(size=${result.asJsonArray.size()})"
      result.isJsonObject && result.asJsonObject.has("items") ->
          "object(items=${result.asJsonObject.getAsJsonArray("items")?.size() ?: -1})"
      result.isJsonObject -> "object"
      else -> result.javaClass.simpleName
    }
  }

  private fun convertToLocations(result: JsonElement?): List<com.tom.rv2ide.models.Location> {
    val locationsArray =
        when {
          result == null || result.isJsonNull -> null
          result.isJsonArray -> result.asJsonArray
          result.isJsonObject && result.asJsonObject.has("uri") -> JsonArray().apply { add(result) }
          result.isJsonObject && result.asJsonObject.has("items") -> result.asJsonObject.getAsJsonArray("items")
          else -> null
        }

    return locationsArray?.map { element ->
      val loc = element.asJsonObject
      val range = loc.getAsJsonObject("range")
      val start = range.getAsJsonObject("start")
      val end = range.getAsJsonObject("end")

      com.tom.rv2ide.models.Location(
          file = Paths.get(java.net.URI(loc.get("uri").asString)),
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
      )
    } ?: emptyList()
  }

  private fun convertToSignatureHelp(result: JsonElement?): SignatureHelp {
    if (result == null || result.isJsonNull || !result.isJsonObject) {
      return SignatureHelp(emptyList(), 0, 0)
    }

    val resultObject = result.asJsonObject
    val signatures =
        resultObject.getAsJsonArray("signatures")?.map { element ->
          val sig = element.asJsonObject
          val label = sig.get("label")?.asString ?: ""
          val doc = sig.get("documentation")?.asString ?: ""

          val parameters =
              sig.getAsJsonArray("parameters")?.map { paramElement ->
                val param = paramElement.asJsonObject
                ParameterInformation(
                    label = param.get("label")?.asString ?: "",
                    documentation =
                        MarkupContent(param.get("documentation")?.asString ?: "", MarkupKind.PLAIN),
                )
              } ?: emptyList()

          SignatureInformation(
              label = label,
              documentation = MarkupContent(doc, MarkupKind.PLAIN),
              parameters = parameters,
          )
        } ?: emptyList()

    return SignatureHelp(
        signatures,
        resultObject.get("activeSignature")?.asInt ?: 0,
        resultObject.get("activeParameter")?.asInt ?: 0,
    )
  }
}
