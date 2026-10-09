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

package com.tom.rv2ide.language.services.kotlin.request

import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.*
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.completion.KotlinCompletionConverter
import com.tom.rv2ide.language.services.kotlin.completion.KotlinJavaCompilerBridge
import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentSync
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequestValidator
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

internal class KotlinRequestHandler(
    private val connection: KotlinBackendConnection,
    private val documentManager: KotlinDocumentSync,
    private val validator: KotlinSemanticRequestValidator,
) {

  companion object {
    private const val COMPLETION_TIMEOUT = 10000L
  }

  private val completionConverter = KotlinCompletionConverter()

  private val completionRequestSeq = AtomicLong(0)

  fun setJavaCompilerBridge(bridge: KotlinJavaCompilerBridge) {
    completionConverter.setJavaCompilerBridge(bridge)
  }

  suspend fun hover(request: KotlinSemanticRequest): MarkupContent =
      withContext(Dispatchers.IO) {

        if (!validator.isCurrent(request)) {
          return@withContext MarkupContent("", MarkupKind.PLAIN)
        }
        val deferred = CompletableDeferred<MarkupContent>()

        // Hover is re-enabled. This path previously had to be hard-disabled after
        // repeated server-side crashes during expression analysis leaked into normal
        // editing sessions and hurt diagnostics/completion stability.
        //
        // Current direction: keep hover available, fail soft on bad responses, and
        // continue narrowing the remaining server-side root cause instead of hiding
        // the feature behind a permanent product switch.

        try {
          documentManager.syncActiveDocument(request.file)
          if (!validator.isCurrent(request)) return@withContext MarkupContent()
          val uri = request.file.toUri().toString()
          val lspParams = JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", uri) })
            add("position", JsonObject().apply {
              addProperty("line", request.line)
              addProperty("character", request.column)
            })
          }

          connection.sendRequest("textDocument/hover", lspParams) { result ->
            if (!validator.isCurrent(request)) {
              deferred.complete(MarkupContent())
              return@sendRequest
            }
            try {
              deferred.complete(convertToHoverMarkup(result))
            } catch (e: Exception) {
              KlsLogs.debug("Failed to parse hover response: {}", e.message)
              deferred.complete(MarkupContent("", MarkupKind.PLAIN))
            }
          }

          val result = withTimeoutOrNull(3000) { deferred.await() }
          if (validator.isCurrent(request)) {
            result ?: MarkupContent("", MarkupKind.PLAIN)
          } else MarkupContent("", MarkupKind.PLAIN)
        } catch (e: java.util.concurrent.CancellationException) {
          throw e
        } catch (e: Exception) {
          KlsLogs.debug("Hover request failed: {}", e.message)
          MarkupContent("", MarkupKind.PLAIN)
        }
      }

  suspend fun complete(request: KotlinSemanticRequest): CompletionResult = coroutineScope {

      fun isCurrentRequest(): Boolean =
          validator.isCurrent(request)
      if (!isCurrentRequest()) return@coroutineScope CompletionResult(emptyList())


      val requestId = completionRequestSeq.incrementAndGet()

      val initialContent = request.snapshot.content
      val initialPrefix = request.prefix ?: extractPrefix(initialContent, request.position())
      KlsLogs.debug(
          "completion request start requestId={} file={} line={} column={} prefix='{}' contentLength={}",
          requestId,
          request.file,
          request.line,
          request.column,
          initialPrefix,
          initialContent.length,
      )

      delay(50L) // Reduced from 100L

      if (completionRequestSeq.get() != requestId || !isCurrentRequest()) {
          KlsLogs.debug("completion stale drop before sync requestId={}", requestId)
          return@coroutineScope CompletionResult(emptyList())
      }

      return@coroutineScope try {
          val deferred = CompletableDeferred<CompletionResult>()

          val fileContent = request.snapshot.content
          val prefix = request.prefix ?: extractPrefix(fileContent, request.position())

          val uri = request.file.toUri().toString()

          if (completionRequestSeq.get() != requestId || !isCurrentRequest()) {
              KlsLogs.debug("completion stale drop before document sync requestId={}", requestId)
              return@coroutineScope CompletionResult(emptyList())
          }

          documentManager.syncActiveDocument(request.file)

          if (completionRequestSeq.get() != requestId || !isCurrentRequest()) {
              KlsLogs.debug("completion stale drop after document sync requestId={}", requestId)
              return@coroutineScope CompletionResult(emptyList())
          }

          val lspParams = JsonObject().apply {
              add("textDocument", JsonObject().apply { addProperty("uri", uri) })
              add("position", JsonObject().apply {
                  addProperty("line", request.line)
                  addProperty("character", request.column)
              })
              add("context", createCompletionContext(request))
          }

          connection.sendRequest("textDocument/completion", lspParams) { result ->
              launch {
                  try {
                      if (completionRequestSeq.get() != requestId || !isCurrentRequest()) {
                          KlsLogs.debug("completion stale drop before response processing requestId={}", requestId)
                          deferred.complete(CompletionResult(emptyList()))
                          return@launch
                      }

                      if (result == null) {
                          KlsLogs.debug("completion result null requestId={}", requestId)
                          deferred.complete(CompletionResult(emptyList()))
                          return@launch
                      }

                       val itemsArray = when {
                           result.has("items") -> result.getAsJsonArray("items")
                           result.has("result") && result.get("result").isJsonArray ->
                               result.getAsJsonArray("result")
                           result.isJsonArray -> result.asJsonArray
                           else -> {
                               KlsLogs.debug("completion result shape unsupported requestId={}", requestId)
                               deferred.complete(CompletionResult(emptyList()))
                               return@launch
                           }
                       }

                      if (completionRequestSeq.get() != requestId || !isCurrentRequest()) {
                          KlsLogs.debug("completion stale drop before convert requestId={} lspItemCount={}", requestId, itemsArray.size())
                          deferred.complete(CompletionResult(emptyList()))
                          return@launch
                      }

                      val items = completionConverter.convertWithClasspathEnhancement(itemsArray, fileContent, prefix)

                      if (completionRequestSeq.get() != requestId || !isCurrentRequest()) {
                          KlsLogs.debug("completion stale drop after convert requestId={} convertedItemCount={}", requestId, items.size)
                          deferred.complete(CompletionResult(emptyList()))
                          return@launch
                      }

                      KlsLogs.debug(
                          "completion completed requestId={} lspItemCount={} convertedItemCount={} prefix='{}'",
                          requestId,
                          itemsArray.size(),
                          items.size,
                          prefix,
                      )
                      deferred.complete(CompletionResult(items))
                   } catch (e: java.util.concurrent.CancellationException) {
                       throw e
                   } catch (e: Exception) {
                       KlsLogs.error("Error processing completion requestId={}", requestId, e)
                      deferred.complete(CompletionResult(emptyList()))
                  }
              }
          }

          withTimeoutOrNull(COMPLETION_TIMEOUT) { deferred.await() } ?: run {
              KlsLogs.debug("completion timed out requestId={}", requestId)
              CompletionResult(emptyList())
          }
      } catch (e: java.util.concurrent.CancellationException) {
        throw e
      } catch (e: Exception) {
          KlsLogs.error("Error during completion requestId={}", requestId, e)
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

  suspend fun findReferences(request: KotlinSemanticRequest): ReferenceResult =
      withContext(Dispatchers.IO) {

        if (!validator.isCurrent(request)) {
          return@withContext ReferenceResult(emptyList())
        }
        val deferred = CompletableDeferred<ReferenceResult>()

        documentManager.syncActiveDocument(request.file)
          if (!validator.isCurrent(request)) return@withContext ReferenceResult(emptyList())

        val lspParams =
            JsonObject().apply {
              add(
                  "textDocument",
                  JsonObject().apply { addProperty("uri", request.file.toUri().toString()) },
              )
              add(
                  "position",
                  JsonObject().apply {
                    addProperty("line", request.line)
                    addProperty("character", request.column)
                  },
              )
              add(
                  "context",
                  JsonObject().apply {
                    addProperty("includeDeclaration", request.includeDeclaration)
                  },
              )
            }

        connection.sendRequest("textDocument/references", lspParams) { result ->
            if (!validator.isCurrent(request)) {
              deferred.complete(ReferenceResult(emptyList()))
              return@sendRequest
            }
          val locations = convertToLocations(result)
          deferred.complete(ReferenceResult(locations))
        }

        val result = withTimeoutOrNull(5000) { deferred.await() }
        if (validator.isCurrent(request)) {
          result ?: ReferenceResult(emptyList())
        } else ReferenceResult(emptyList())
      }

  suspend fun findDefinition(request: KotlinSemanticRequest): DefinitionResult =
      withContext(Dispatchers.IO) {

        if (!validator.isCurrent(request)) {
          return@withContext DefinitionResult(emptyList())
        }
        val deferred = CompletableDeferred<DefinitionResult>()

        documentManager.syncActiveDocument(request.file)
          if (!validator.isCurrent(request)) return@withContext DefinitionResult(emptyList())

        val lspParams =
            JsonObject().apply {
              add(
                  "textDocument",
                  JsonObject().apply { addProperty("uri", request.file.toUri().toString()) },
              )
              add(
                  "position",
                  JsonObject().apply {
                    addProperty("line", request.line)
                    addProperty("character", request.column)
                  },
              )
            }

        connection.sendRequest("textDocument/definition", lspParams) { result ->
            if (!validator.isCurrent(request)) {
              deferred.complete(DefinitionResult(emptyList()))
              return@sendRequest
            }
          val locations = convertToLocations(result)
          deferred.complete(DefinitionResult(locations))
        }

        val result = withTimeoutOrNull(5000) { deferred.await() }
        if (validator.isCurrent(request)) {
          result ?: DefinitionResult(emptyList())
        } else DefinitionResult(emptyList())
      }

  suspend fun signatureHelp(request: KotlinSemanticRequest): SignatureHelp =
      withContext(Dispatchers.IO) {

        if (!validator.isCurrent(request)) {
          return@withContext SignatureHelp(emptyList(), -1, -1)
        }
        val deferred = CompletableDeferred<SignatureHelp>()

        try {
          documentManager.syncActiveDocument(request.file)
          if (!validator.isCurrent(request)) return@withContext SignatureHelp(emptyList(), 0, 0)
          // Build context with trigger information
          val context =
              JsonObject().apply {
                addProperty("triggerKind", 2) // 2 = TriggerCharacter, 1 = Invoked
                addProperty("isRetrigger", false)

                // Detect trigger character from content
                if (request.snapshot.content.isNotEmpty()) {
                  val content = request.snapshot.content
                  val lines = content.split("\n")
                  if (request.line >= 0 && request.line < lines.size) {
                    val currentLine = lines[request.line]
                    val pos = request.column

                    if (pos > 0 && pos <= currentLine.length) {
                      val triggerChar = currentLine[pos - 1]
                      if (triggerChar == '(' || triggerChar == ',') {
                        addProperty("triggerCharacter", triggerChar.toString())
                        KlsLogs.debug("Signature help triggered by: '{}'", triggerChar)
                      }
                    }
                  }
                }
              }

          val lspParams =
              JsonObject().apply {
                add(
                    "textDocument",
                    JsonObject().apply { addProperty("uri", request.file.toUri().toString()) },
                )
                add(
                    "position",
                    JsonObject().apply {
                      addProperty("line", request.line)
                      addProperty("character", request.column)
                    },
                )
                add("context", context)
              }

          KlsLogs.debug(
              "Requesting signature help at {}:{}",
              request.line,
              request.column,
          )

          connection.sendRequest("textDocument/signatureHelp", lspParams) { result ->
            if (!validator.isCurrent(request)) {
              deferred.complete(SignatureHelp(emptyList(), 0, 0))
              return@sendRequest
            }
            val help = convertToSignatureHelp(result)
            KlsLogs.debug("Received {} signature(s)", help.signatures.size)
            deferred.complete(help)
          }

          val result = withTimeoutOrNull(3000) { deferred.await() }
          if (validator.isCurrent(request)) {
            result ?: SignatureHelp(emptyList(), 0, 0)
          } else SignatureHelp(emptyList(), 0, 0)
        } catch (e: java.util.concurrent.CancellationException) {
          throw e
        } catch (e: Exception) {
          KlsLogs.error("Error requesting signature help", e)
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
      KlsLogs.debug("Failed to convert hover payload: {}", e.message)
      MarkupContent("", MarkupKind.PLAIN)
    }
  }

  private fun convertToSignatureHelp(result: JsonObject?): SignatureHelp {
    if (result == null) {
      KlsLogs.debug("Signature help result is null")
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
                      KlsLogs.warn("Failed to parse parameter: {}", e.message)
                      null
                    }
                  } ?: emptyList()

              SignatureInformation(
                  label = label,
                  documentation = documentation,
                  parameters = parameters,
              )
            } catch (e: Exception) {
              KlsLogs.warn("Failed to parse signature: {}", e.message)
              null
            }
          } ?: emptyList()

      val activeSignature = result.get("activeSignature")?.asInt ?: 0
      val activeParameter = result.get("activeParameter")?.asInt ?: 0

      KlsLogs.debug(
          "Converted signature help: {} signatures, active: {}/{}",
          signatures.size,
          activeSignature,
          activeParameter,
      )

      return SignatureHelp(signatures, activeSignature, activeParameter)
    } catch (e: Exception) {
      KlsLogs.error("Error converting signature help", e)
      return SignatureHelp(emptyList(), 0, 0)
    }
  }

  private fun createCompletionContext(request: KotlinSemanticRequest): JsonObject {
    return JsonObject().apply {
      addProperty("triggerKind", 1)

      if (request.snapshot.content.isNotEmpty()) {
        val content = request.snapshot.content
        val lines = content.split("\n")
        if (request.line < lines.size) {
          val currentLine = lines[request.line]
          val pos = request.column

          if (pos > 0 && pos <= currentLine.length && currentLine[pos - 1] == '.') {
            addProperty("triggerCharacter", ".")
          }
        }
      }
    }
  }

  private fun convertToLocations(result: JsonObject?): List<com.tom.rv2ide.models.Location> {
    val locations = result?.getAsJsonArray("result") ?: return emptyList()
    return locations.map { element ->
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
    }
  }
}
