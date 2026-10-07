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
import com.tom.rv2ide.lsp.kotlin.etc.LspFeatures
import com.tom.rv2ide.lsp.models.*
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinRequestHandler(
    private val processManager: KotlinLspConnection,
    private val documentManager: KotlinDocumentManager,
) {

  companion object {
    private const val COMPLETION_TIMEOUT = 10000L
    private const val DEBOUNCE_DELAY = 0L
    private const val HOVER_DEBOUNCE_MS = 350L
    private const val HOVER_MIN_INTERVAL_MS = 500L
  }

  private val completionConverter = KotlinCompletionConverter()

  // Track last sync time to avoid redundant syncs
  private val lastSyncTime = ConcurrentHashMap<String, Long>()
  private val syncThrottleMs = 0L

  // Debouncing for rapid typing
  private val lastCompletionRequest = AtomicLong(0)
  private val completionRequestSeq = AtomicLong(0)
  private val lastHoverRequest = AtomicLong(0)
  private var activeCompletionJob: Job? = null
  private var javaCompilerBridge: KotlinJavaCompilerBridge? = null

  fun setJavaCompilerBridge(bridge: KotlinJavaCompilerBridge) {
    this.javaCompilerBridge = bridge
    completionConverter.setJavaCompilerBridge(bridge)
  }

  suspend fun hover(params: DefinitionParams): MarkupContent =
      withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<MarkupContent>()

        // Hover is re-enabled. This path previously had to be hard-disabled after
        // repeated server-side crashes during expression analysis leaked into normal
        // editing sessions and hurt diagnostics/completion stability.
        //
        // Current direction: keep hover available, fail soft on bad responses, and
        // continue narrowing the remaining server-side root cause instead of hiding
        // the feature behind a permanent product switch.

        try {
          documentManager.ensureDocumentOpen(params.file)

          val uri = params.file.toUri().toString()
          val lspParams = JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", uri) })
            add("position", JsonObject().apply {
              addProperty("line", params.position.line)
              addProperty("character", params.position.column)
            })
          }

          processManager.sendRequest("textDocument/hover", lspParams) { result ->
            try {
              deferred.complete(convertToHoverMarkup(result))
            } catch (e: Exception) {
              KslLogs.debug("Failed to parse hover response: {}", e.message)
              deferred.complete(MarkupContent("", MarkupKind.PLAIN))
            }
          }

          withTimeoutOrNull(3000) { deferred.await() } ?: MarkupContent("", MarkupKind.PLAIN)
        } catch (e: Exception) {
          KslLogs.debug("Hover request failed: {}", e.message)
          MarkupContent("", MarkupKind.PLAIN)
        }
      }

  suspend fun complete(params: CompletionParams): CompletionResult = coroutineScope {
      if (params.position.line < 0 || params.position.column < 0) {
          return@coroutineScope CompletionResult(emptyList())
      }

      activeCompletionJob?.cancel()

      val requestTimestamp = System.currentTimeMillis()
      val requestId = completionRequestSeq.incrementAndGet()
      lastCompletionRequest.set(requestTimestamp)

      val initialContent = params.content?.toString() ?: ""
      val initialPrefix = extractPrefix(initialContent, params.position)
      KslLogs.debug(
          "completion request start requestId={} file={} line={} column={} prefix='{}' contentLength={}",
          requestId,
          params.file,
          params.position.line,
          params.position.column,
          initialPrefix,
          initialContent.length,
      )

      delay(50L) // Reduced from 100L

      if (lastCompletionRequest.get() != requestTimestamp || completionRequestSeq.get() != requestId) {
          KslLogs.debug("completion stale drop before sync requestId={}", requestId)
          return@coroutineScope CompletionResult(emptyList())
      }

      return@coroutineScope try {
          val deferred = CompletableDeferred<CompletionResult>()

          val fileContent = params.content?.toString() ?: ""
          val prefix = extractPrefix(fileContent, params.position)

          val uri = params.file.toUri().toString()
          val currentTime = System.currentTimeMillis()
          val lastSync = lastSyncTime[uri] ?: 0L

          if (completionRequestSeq.get() != requestId) {
              KslLogs.debug("completion stale drop before document sync requestId={}", requestId)
              return@coroutineScope CompletionResult(emptyList())
          }

          // Keep KLS in sync before requesting completion. The previous fire-and-forget sync could let
          // textDocument/completion race ahead of didOpen/didChange, producing stale suggestions.
          if (!documentManager.isDocumentOpen(uri)) {
              documentManager.ensureDocumentOpen(params.file, fileContent.takeIf { it.isNotEmpty() })
              lastSyncTime[uri] = currentTime
          } else if (currentTime - lastSync > syncThrottleMs && fileContent.isNotEmpty()) {
              val currentVersion = documentManager.getDocumentVersion(uri)
              val newVersion = currentVersion + 1
              documentManager.setDocumentVersion(uri, newVersion)
              documentManager.notifyDocumentChange(params.file, fileContent, newVersion)
              lastSyncTime[uri] = currentTime
          }

          if (completionRequestSeq.get() != requestId) {
              KslLogs.debug("completion stale drop after document sync requestId={}", requestId)
              return@coroutineScope CompletionResult(emptyList())
          }

          val lspParams = JsonObject().apply {
              add("textDocument", JsonObject().apply { addProperty("uri", uri) })
              add("position", JsonObject().apply {
                  addProperty("line", params.position.line)
                  addProperty("character", params.position.column)
              })
              add("context", createCompletionContext(params))
          }

          processManager.sendRequest("textDocument/completion", lspParams) { result ->
              launch {
                  try {
                      if (completionRequestSeq.get() != requestId) {
                          KslLogs.debug("completion stale drop before response processing requestId={}", requestId)
                          deferred.complete(CompletionResult(emptyList()))
                          return@launch
                      }

                      if (result == null) {
                          KslLogs.debug("completion result null requestId={}", requestId)
                          deferred.complete(CompletionResult(emptyList()))
                          return@launch
                      }

                      val itemsArray = when {
                          result.has("items") -> result.getAsJsonArray("items")
                          result.isJsonArray -> result.asJsonArray
                          else -> {
                              KslLogs.debug("completion result shape unsupported requestId={}", requestId)
                              deferred.complete(CompletionResult(emptyList()))
                              return@launch
                          }
                      }

                      if (completionRequestSeq.get() != requestId) {
                          KslLogs.debug("completion stale drop before convert requestId={} lspItemCount={}", requestId, itemsArray.size())
                          deferred.complete(CompletionResult(emptyList()))
                          return@launch
                      }

                      val items = completionConverter.convertWithClasspathEnhancement(itemsArray, fileContent, prefix)

                      if (completionRequestSeq.get() != requestId) {
                          KslLogs.debug("completion stale drop after convert requestId={} convertedItemCount={}", requestId, items.size)
                          deferred.complete(CompletionResult(emptyList()))
                          return@launch
                      }

                      KslLogs.debug(
                          "completion completed requestId={} lspItemCount={} convertedItemCount={} prefix='{}'",
                          requestId,
                          itemsArray.size(),
                          items.size,
                          prefix,
                      )
                      deferred.complete(CompletionResult(items))
                  } catch (e: Exception) {
                      KslLogs.error("Error processing completion requestId={}", requestId, e)
                      deferred.complete(CompletionResult(emptyList()))
                  }
              }
          }

          withTimeoutOrNull(COMPLETION_TIMEOUT) { deferred.await() } ?: run {
              KslLogs.debug("completion timed out requestId={}", requestId)
              CompletionResult(emptyList())
          }
      } catch (e: Exception) {
          KslLogs.error("Error during completion requestId={}", requestId, e)
          CompletionResult(emptyList())
      }
  }
  
  private fun extractPrefix(content: String, position: com.tom.rv2ide.models.Position): String {
    val lines = content.split("\n")
    if (position.line < 0 || position.line >= lines.size) return ""

    val line = lines[position.line]
    val col = position.column.coerceAtMost(line.length)

    var start = col
    while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) {
      start--
    }

    return line.substring(start, col)
  }

  suspend fun findReferences(params: ReferenceParams): ReferenceResult =
      withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<ReferenceResult>()

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

        processManager.sendRequest("textDocument/references", lspParams) { result ->
          val locations = convertToLocations(result)
          deferred.complete(ReferenceResult(locations))
        }

        withTimeoutOrNull(5000) { deferred.await() } ?: ReferenceResult(emptyList())
      }

  suspend fun findDefinition(params: DefinitionParams): DefinitionResult =
      withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<DefinitionResult>()

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

        processManager.sendRequest("textDocument/definition", lspParams) { result ->
          val locations = convertToLocations(result)
          deferred.complete(DefinitionResult(locations))
        }

        withTimeoutOrNull(5000) { deferred.await() } ?: DefinitionResult(emptyList())
      }

  suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp =
      withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<SignatureHelp>()

        try {
          documentManager.ensureDocumentOpen(params.file)

          val uri = params.file.toUri().toString()
          if (params.content != null && params.content!!.isNotEmpty()) {
            val currentVersion = documentManager.getDocumentVersion(uri)
            val newVersion = currentVersion + 1
            documentManager.setDocumentVersion(uri, newVersion)
            documentManager.notifyDocumentChange(params.file, params.content.toString(), newVersion)
          }

          // Build context with trigger information
          val context =
              JsonObject().apply {
                addProperty("triggerKind", 2) // 2 = TriggerCharacter, 1 = Invoked
                addProperty("isRetrigger", false)

                // Detect trigger character from content
                if (params.content != null) {
                  val content = params.content.toString()
                  val lines = content.split("\n")
                  if (params.position.line >= 0 && params.position.line < lines.size) {
                    val currentLine = lines[params.position.line]
                    val pos = params.position.column

                    if (pos > 0 && pos <= currentLine.length) {
                      val triggerChar = currentLine[pos - 1]
                      if (triggerChar == '(' || triggerChar == ',') {
                        addProperty("triggerCharacter", triggerChar.toString())
                        KslLogs.debug("Signature help triggered by: '{}'", triggerChar)
                      }
                    }
                  }
                }
              }

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
                add("context", context)
              }

          KslLogs.debug(
              "Requesting signature help at {}:{}",
              params.position.line,
              params.position.column,
          )

          processManager.sendRequest("textDocument/signatureHelp", lspParams) { result ->
            val help = convertToSignatureHelp(result)
            KslLogs.debug("Received {} signature(s)", help.signatures.size)
            deferred.complete(help)
          }

          withTimeoutOrNull(3000) { deferred.await() }
              ?: run {
                KslLogs.warn("Signature help request timed out")
                SignatureHelp(emptyList(), 0, 0)
              }
        } catch (e: Exception) {
          KslLogs.error("Error requesting signature help", e)
          deferred.complete(SignatureHelp(emptyList(), 0, 0))
          SignatureHelp(emptyList(), 0, 0)
        }
      }

  private fun convertToHoverMarkup(result: JsonObject?): MarkupContent {
    if (result == null || !result.has("contents")) {
      return MarkupContent("", MarkupKind.PLAIN)
    }

    return try {
      val contents = result.get("contents")
      when {
        contents == null || contents.isJsonNull -> MarkupContent("", MarkupKind.PLAIN)
        contents.isJsonObject -> {
          val obj = contents.asJsonObject
          when {
            obj.has("language") && obj.has("value") ->
                MarkupContent(
                    "```" +
                        (obj.get("language")?.asString ?: "") +
                        "\n" +
                        (obj.get("value")?.asString ?: "") +
                        "\n```",
                    MarkupKind.MARKDOWN,
                )
            obj.has("value") ->
                MarkupContent(
                    obj.get("value")?.asString ?: "",
                    if (obj.get("kind")?.asString == "markdown") MarkupKind.MARKDOWN else MarkupKind.PLAIN,
                )
            else -> MarkupContent(obj.toString(), MarkupKind.PLAIN)
          }
        }
        contents.isJsonPrimitive -> MarkupContent(contents.asString, MarkupKind.PLAIN)
        contents.isJsonArray -> {
          val text = contents.asJsonArray.joinToString("\n\n") { element ->
            when {
              element == null || element.isJsonNull -> ""
              element.isJsonPrimitive -> element.asString
              element.isJsonObject -> {
                val obj = element.asJsonObject
                when {
                  obj.has("language") && obj.has("value") ->
                      "```" +
                          (obj.get("language")?.asString ?: "") +
                          "\n" +
                          (obj.get("value")?.asString ?: "") +
                          "\n```"
                  obj.has("value") -> obj.get("value")?.asString ?: ""
                  else -> obj.toString()
                }
              }
              else -> element.toString()
            }
          }.trim()
          MarkupContent(text, MarkupKind.MARKDOWN)
        }
        else -> MarkupContent(contents.toString(), MarkupKind.PLAIN)
      }
    } catch (e: Exception) {
      KslLogs.debug("Failed to convert hover payload: {}", e.message)
      MarkupContent("", MarkupKind.PLAIN)
    }
  }

  private fun convertToSignatureHelp(result: JsonObject?): SignatureHelp {
    if (result == null) {
      KslLogs.debug("Signature help result is null")
      return SignatureHelp(emptyList(), 0, 0)
    }

    try {
      val signatures =
          result.getAsJsonArray("signatures")?.mapNotNull { element ->
            try {
              val sig = element.asJsonObject
              val label = sig.get("label")?.asString ?: return@mapNotNull null

              // Handle documentation (can be string or MarkupContent object)
              val documentation =
                  when {
                    sig.has("documentation") -> {
                      val doc = sig.get("documentation")
                      when {
                        doc.isJsonObject -> {
                          val docObj = doc.asJsonObject
                          MarkupContent(
                              docObj.get("value")?.asString ?: "",
                              if (docObj.get("kind")?.asString == "markdown") MarkupKind.MARKDOWN
                              else MarkupKind.PLAIN,
                          )
                        }
                        doc.isJsonPrimitive -> {
                          MarkupContent(doc.asString, MarkupKind.PLAIN)
                        }
                        else -> MarkupContent("", MarkupKind.PLAIN)
                      }
                    }
                    else -> MarkupContent("", MarkupKind.PLAIN)
                  }

              // Parse parameters
              val parameters =
                  sig.getAsJsonArray("parameters")?.mapNotNull { paramElement ->
                    try {
                      val param = paramElement.asJsonObject
                      val paramLabel = param.get("label")?.asString ?: return@mapNotNull null

                      // Handle parameter documentation
                      val paramDoc =
                          when {
                            param.has("documentation") -> {
                              val doc = param.get("documentation")
                              when {
                                doc.isJsonObject -> {
                                  val docObj = doc.asJsonObject
                                  MarkupContent(
                                      docObj.get("value")?.asString ?: "",
                                      if (docObj.get("kind")?.asString == "markdown")
                                          MarkupKind.MARKDOWN
                                      else MarkupKind.PLAIN,
                                  )
                                }
                                doc.isJsonPrimitive -> MarkupContent(doc.asString, MarkupKind.PLAIN)
                                else -> MarkupContent("", MarkupKind.PLAIN)
                              }
                            }
                            else -> MarkupContent("", MarkupKind.PLAIN)
                          }

                      ParameterInformation(label = paramLabel, documentation = paramDoc)
                    } catch (e: Exception) {
                      KslLogs.warn("Failed to parse parameter: {}", e.message)
                      null
                    }
                  } ?: emptyList()

              SignatureInformation(
                  label = label,
                  documentation = documentation,
                  parameters = parameters,
              )
            } catch (e: Exception) {
              KslLogs.warn("Failed to parse signature: {}", e.message)
              null
            }
          } ?: emptyList()

      val activeSignature = result.get("activeSignature")?.asInt ?: 0
      val activeParameter = result.get("activeParameter")?.asInt ?: 0

      KslLogs.debug(
          "Converted signature help: {} signatures, active: {}/{}",
          signatures.size,
          activeSignature,
          activeParameter,
      )

      return SignatureHelp(signatures, activeSignature, activeParameter)
    } catch (e: Exception) {
      KslLogs.error("Error converting signature help", e)
      return SignatureHelp(emptyList(), 0, 0)
    }
  }

  private fun createCompletionContext(params: CompletionParams): JsonObject {
    return JsonObject().apply {
      addProperty("triggerKind", 1)

      if (params.content != null) {
        val content = params.content.toString()
        val lines = content.split("\n")
        if (params.position.line < lines.size) {
          val currentLine = lines[params.position.line]
          val pos = params.position.column

          if (pos > 0 && pos <= currentLine.length && currentLine[pos - 1] == '.') {
            addProperty("triggerCharacter", ".")
          }
        }
      }
    }
  }

  private fun convertToLocations(result: JsonObject?): List<com.tom.rv2ide.models.Location> {
    return result?.asJsonArray?.map { element ->
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
}
