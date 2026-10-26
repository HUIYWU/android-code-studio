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

package com.tom.rv2ide.artificial.agents.local

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.AgentHistory
import com.tom.rv2ide.artificial.agents.AgentMessage
import com.tom.rv2ide.artificial.agents.AgentStreamEvent
import com.tom.rv2ide.artificial.agents.AgentStreamParser
import com.tom.rv2ide.artificial.agents.AgentToolSpec
import com.tom.rv2ide.artificial.agents.AgentTurn
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.agents.OpenAiCompat
import com.tom.rv2ide.artificial.agents.ToolCallAccumulator
import com.tom.rv2ide.artificial.agents.ToolsNotSupportedException
import com.tom.rv2ide.artificial.agents.runCancellable
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.catalog.LocalLlmSettings
import com.tom.rv2ide.artificial.catalog.ModelSources
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class LocalLLM : AIAgent {

  private var baseUrl: String? = null
  private var modelName: String? = null
  private val httpClient = OkHttpClient.Builder()
      .connectTimeout(60, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()
  private val writingRules = WritingRules.Instructions()
  private var projectTreeResult: ProjectTreeResult? = null
  private var fileWriter: AIFileWriter? = null
  override val history = AgentHistory()
  private val modificationHistory = mutableListOf<ModificationAttempt>()
  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3
  private var agents: Agents? = null
  override val providerId = LocalLlmSettings.PROVIDER_ID
  override val providerName = ModelSources.providerName(LocalLlmSettings.PROVIDER_ID)

  companion object {
      fun registerAgent() {
          AIAgentRegistry.register(LocalLlmSettings.PROVIDER_ID, object : AIAgentRegistry.AgentFactory {
              override fun create(context: Context): AIAgent {
                  return LocalLLM()
              }
              
              override fun hasValidApiKey(): Boolean {
                  // Reads the same source as the configuration dialog, so a configured-but-unusable
                  // endpoint (blank, or with stray whitespace) is not reported as valid.
                  val url = LocalLlmSettings.baseUrl()
                  val model = LocalLlmSettings.model()
                  return url != null && model != null
              }
              
              override fun getApiKey(): String? {
                  return "local"
              }
          })
      }
  }
  
  override fun initialize(apiKey: String, context: Context) {
      try {
          agents = Agents(context)
          // Shared with the configuration dialog: the endpoint is stored without a trailing slash
          // there, so appending "/v1/chat/completions" cannot produce a double slash.
          baseUrl = LocalLlmSettings.baseUrl()
          modelName = LocalLlmSettings.model()

          if (baseUrl == null || modelName == null) {
              throw IllegalStateException("Local LLM not configured. Please set base URL and model name.")
          }
      } catch (e: Exception) {
          throw e
      }
  }
  
  override fun isInitialized(): Boolean = baseUrl != null && baseUrl!!.isNotEmpty() && modelName != null && modelName!!.isNotEmpty()
  

  override fun reinitializeWithNewModel(apiKey: String, context: Context) {
    initialize(apiKey, context)
  }

  override fun setContext(context: Context) {
    fileWriter = AIFileWriter(context)
  }

  override fun setProjectData(projectTreeResult: ProjectTreeResult) {
    this.projectTreeResult = projectTreeResult
  }

  override fun clearConversation() {
    history.clear()
    modificationHistory.clear()
    currentAttemptCount = 0
  }

  override fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean) {
    modificationHistory.add(
        ModificationAttempt(
            timestamp = System.currentTimeMillis(),
            filePath = filePath,
            previousContent = oldContent,
            newContent = newContent,
            attemptNumber = currentAttemptCount,
            success = success
        )
    )
  }

  override fun undoLastModification(): Boolean {
    if (modificationHistory.isEmpty()) return false
    
    val lastMod = modificationHistory.lastOrNull { it.success } ?: return false
    
    if (lastMod.previousContent != null) {
      val result = writeFile(lastMod.filePath, lastMod.previousContent)
      if (result is FileWriteResult.Success) {
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        return true
      }
    } else {
      try {
        File(lastMod.filePath).delete()
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        return true
      } catch (e: Exception) {
        return false
      }
    }
    return false
  }

  override fun getModificationHistory(): List<ModificationAttempt> {
    return modificationHistory.toList()
  }

  override fun resetAttemptCount() {
    currentAttemptCount = 0
  }

  override fun incrementAttemptCount() {
    currentAttemptCount++
  }

  override fun getCurrentAttemptCount(): Int = currentAttemptCount

  override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

  private fun isUserRequestingCorrection(message: String): Boolean {
    val correctionKeywords = listOf(
        "wrong", "not what", "mistake", "error", "incorrect", 
        "that's not", "not right", "fix", "undo", "revert",
        "different", "try again", "not working"
    )
    return correctionKeywords.any { message.lowercase().contains(it) }
  }

  override suspend fun generateCode(
      prompt: String,
      context: String?,
      language: String,
      projectStructure: String?,
    ): Result<String> =
      withContext(Dispatchers.IO) {
        try {
          if (baseUrl.isNullOrEmpty() || modelName.isNullOrEmpty()) {
              return@withContext Result.failure(
                  IllegalStateException("Local LLM not configured")
              )
          }

          val fullPrompt = buildFullPrompt(prompt, context)
          val generatedResponse = callLocalLlmApi(fullPrompt)

          if (generatedResponse.isBlank()) {
            return@withContext Result.failure(Exception("Empty response from Local LLM"))
          }

          Result.success(generatedResponse)
        } catch (e: com.tom.rv2ide.artificial.exceptions.RateLimitException) {
          Result.failure(e)
        } catch (e: com.tom.rv2ide.artificial.exceptions.QuotaExceededException) {
          Result.failure(e)
        } catch (e: com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException) {
          Result.failure(e)
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  override suspend fun generateCodeStreaming(
      prompt: String,
      context: String?,
      language: String,
      projectStructure: String?,
      onEvent: (AgentStreamEvent) -> Unit,
  ): Result<String> =
      withContext(Dispatchers.IO) {
        try {
          if (baseUrl.isNullOrEmpty() || modelName.isNullOrEmpty()) {
              return@withContext Result.failure(
                  IllegalStateException("Local LLM not configured")
              )
          }

          val fullPrompt = buildFullPrompt(prompt, context)
          val parser = AgentStreamParser()
          val streamed = StringBuilder()

          streamLocalLlmApi(fullPrompt) { delta, isReasoning ->
            if (isReasoning) {
              onEvent(AgentStreamEvent.ThinkingDelta(delta))
            } else {
              streamed.append(delta)
              parser.accept(delta, onEvent)
            }
          }

          parser.finish(onEvent)
          val generatedResponse = streamed.toString()

          if (generatedResponse.isBlank()) {
            return@withContext Result.failure(Exception("Empty response from Local LLM"))
          }

          Result.success(generatedResponse)
        } catch (e: com.tom.rv2ide.artificial.exceptions.RateLimitException) {
          Result.failure(e)
        } catch (e: com.tom.rv2ide.artificial.exceptions.QuotaExceededException) {
          Result.failure(e)
        } catch (e: com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException) {
          Result.failure(e)
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  override suspend fun generateTurn(
      messages: List<AgentMessage>,
      tools: List<AgentToolSpec>,
      onEvent: (AgentStreamEvent) -> Unit,
  ): Result<AgentTurn> =
      withContext(Dispatchers.IO) {
        try {
          if (baseUrl.isNullOrEmpty() || modelName.isNullOrEmpty()) {
              return@withContext Result.failure(
                  IllegalStateException("Local LLM not configured")
              )
          }

          val requestBody = buildToolRequestBody(messages, tools)
          val text = StringBuilder()
          val reasoningContent = StringBuilder()
          val toolCalls = ToolCallAccumulator()

          streamToolTurn(requestBody) { delta ->
            val content = OpenAiCompat.contentOf(delta)
            if (content.isNotEmpty()) {
              text.append(content)
              onEvent(AgentStreamEvent.TextDelta(content))
            }

            val reasoning = OpenAiCompat.reasoningOf(delta)
            if (reasoning.isNotEmpty()) {
              reasoningContent.append(reasoning)
              onEvent(AgentStreamEvent.ThinkingDelta(reasoning))
            }

            toolCalls.accept(delta.optJSONArray("tool_calls"))
          }

          if (text.isEmpty() && !toolCalls.hasCalls()) {
            return@withContext Result.failure(Exception("Empty response from Local LLM"))
          }

          Result.success(
              AgentTurn(
                text = text.toString(),
                toolCalls = toolCalls.finish(),
                reasoningContent = reasoningContent.toString()
              )
            )
        } catch (e: com.tom.rv2ide.artificial.exceptions.RateLimitException) {
          Result.failure(e)
        } catch (e: com.tom.rv2ide.artificial.exceptions.QuotaExceededException) {
          Result.failure(e)
        } catch (e: com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException) {
          Result.failure(e)
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  private fun buildFullPrompt(prompt: String, context: String?): String {
    val needsCorrection = isUserRequestingCorrection(prompt)

    return buildString {
      append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
      if (projectTreeResult != null) {
        append(projectTreeResult!!.tree)
        append("\n\n")
        append("CRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths like '/storage/emulated/0/project' or 'com.example.yourproject'.\n")
        append("CRITICAL: Look at the actual paths above and use those EXACT paths.\n\n")
      }
      
      if (context != null) {
        append("=== ADDITIONAL CONTEXT ===\n")
        append(context)
        append("\n\n")
      }
      
      val historyEntries = history.snapshot()
      if (historyEntries.isNotEmpty()) {
        append("=== CONVERSATION HISTORY ===\n")
        historyEntries.forEach { msg ->
          append("${msg.role.uppercase()}: ${msg.content}\n\n")
        }
      }

      if (needsCorrection && modificationHistory.isNotEmpty()) {
        append("=== CORRECTION REQUIRED ===\n")
        append("The user indicated the previous modification was WRONG.\n")
        append("Previous failed attempts:\n")
        modificationHistory.takeLast(3).forEach { attempt ->
          append("Attempt ${attempt.attemptNumber}: ${attempt.filePath}\n")
          append("Result: ${if (attempt.success) "Applied but user rejected" else "Failed"}\n\n")
        }
        append("You MUST try a DIFFERENT approach. Do NOT repeat the same solution.\n")
        append("Analyze what went wrong and provide a better solution.\n\n")
      }

      if (currentAttemptCount > 0) {
        append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n")
        append("This is retry attempt number $currentAttemptCount.\n")
        append("Previous attempts did not satisfy the user.\n")
        append("Think carefully and provide a different solution.\n\n")
      }
      
      append("=== USER REQUEST ===\n")
      append(prompt)
    }
  }

  private fun buildRequestBody(fullPrompt: String, stream: Boolean): JSONObject {
    val messages = JSONArray()
    messages.put(JSONObject().apply {
      put("role", "system")
      put("content", writingRules.useThis())
    })
    messages.put(JSONObject().apply {
      put("role", "user")
      put("content", fullPrompt)
    })

    return JSONObject().apply {
      put("model", modelName)
      put("messages", messages)
      put("temperature", 0.7)
      put("stream", stream)
    }
  }

  private fun buildToolRequestBody(
      messages: List<AgentMessage>,
      tools: List<AgentToolSpec>,
  ): JSONObject {
    val requestBody = JSONObject()
    requestBody.put("model", modelName)
    requestBody.put("messages", OpenAiCompat.messagesJson(messages))
    if (tools.isNotEmpty()) {
      requestBody.put("tools", OpenAiCompat.toolsJson(tools))
    }
    requestBody.put("temperature", 0.7)
    requestBody.put("stream", true)

    return requestBody
  }

  private fun buildRequest(requestBody: JSONObject): Request =
      Request.Builder()
          .url("$baseUrl/v1/chat/completions")
          .apply {
            // Most local servers are unauthenticated, but the settings dialog allows a key for
            // the ones that are; sending it here keeps the two consistent. It is read from the
            // same source the dialog writes to, since initialize() does not retain its key.
            LocalLlmSettings.apiKey()
              ?.let { header("Authorization", "Bearer $it") }
          }
          .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
          .build()

  private suspend fun callLocalLlmApi(fullPrompt: String): String {
    val requestBody = buildRequestBody(fullPrompt, stream = false)

    val call = httpClient.newCall(buildRequest(requestBody))
    val response = try {
      call.runCancellable { call.execute() }
    } catch (e: Exception) {
      val errorMessage = e.message ?: ""
      when {
        errorMessage.contains("timeout") ||
        errorMessage.contains("connect") ->
          throw com.tom.rv2ide.artificial.exceptions.RateLimitException(
            "Connection timeout. Please check your local server."
          )
        else -> throw e
      }
    }

    if (!response.isSuccessful) {
      val errorBody = response.body?.string() ?: "Unknown error"
      throw Exception("Local LLM error ${response.code}: $errorBody")
    }

    val responseBody = call.runCancellable { response.body?.string() ?: "" }
    val jsonResponse = JSONObject(responseBody)

    return jsonResponse
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")
  }

  /**
   * Reads the reply as it is produced, handing each fragment to [onDelta] along with whether it is
   * reasoning rather than answer text.
   *
   * The two arrive in separate fields of the same delta, so the distinction is made here and not by
   * parsing: `content` is the reply, `reasoning_content` is what led to it.
   *
   * OpenAI-compatible SSE: one `data: {...}` line per chunk, terminated by `data: [DONE]`.
   */
  private suspend fun streamLocalLlmApi(fullPrompt: String, onDelta: (String, Boolean) -> Unit) {
    val requestBody = buildRequestBody(fullPrompt, stream = true)

    val call = httpClient.newCall(buildRequest(requestBody))
    val response = try {
      call.runCancellable { call.execute() }
    } catch (e: Exception) {
      val errorMessage = e.message ?: ""
      when {
        errorMessage.contains("timeout") ||
        errorMessage.contains("connect") ->
          throw com.tom.rv2ide.artificial.exceptions.RateLimitException(
            "Connection timeout. Please check your local server."
          )
        else -> throw e
      }
    }

    if (!response.isSuccessful) {
      val errorBody = response.body?.string() ?: "Unknown error"
      throw Exception("Local LLM error ${response.code}: $errorBody")
    }

    call.runCancellable {
      response.body?.source()?.use { source ->
        while (!source.exhausted()) {
          val rawLine = source.readUtf8Line() ?: break
          if (!rawLine.startsWith("data:")) continue

          val payload = rawLine.removePrefix("data:").trim()
          if (payload.isEmpty() || payload == "[DONE]") continue

          val deltaNode = try {
            JSONObject(payload)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
          } catch (e: Exception) {
            null
          }

          val rawContent = deltaNode?.opt("content")
          val content =
              if (rawContent == null || rawContent === JSONObject.NULL) "" else rawContent as? String ?: ""
          if (content.isNotEmpty()) onDelta(content, false)

          val rawReasoning = deltaNode?.opt("reasoning_content")
          val reasoning =
              if (rawReasoning == null || rawReasoning === JSONObject.NULL) {
                ""
              } else {
                rawReasoning as? String ?: ""
              }
          if (reasoning.isNotEmpty()) onDelta(reasoning, true)
        }
      }
    }
  }

  /**
   * Streams a prebuilt tool-aware request, handing each whole delta to [onDelta]. Separate from
   * [streamLocalLlmApi] because tool mode reads the same stream for three kinds of fragment.
   */
  private suspend fun streamToolTurn(
      requestBody: JSONObject,
      onDelta: (JSONObject) -> Unit,
  ) {
    val call = httpClient.newCall(buildRequest(requestBody))
    val response = try {
      call.runCancellable { call.execute() }
    } catch (e: Exception) {
      val errorMessage = e.message ?: ""
      when {
        errorMessage.contains("timeout") ||
        errorMessage.contains("connect") ->
          throw com.tom.rv2ide.artificial.exceptions.RateLimitException(
            "Connection timeout. Please check your local server."
          )
        else -> throw e
      }
    }

    if (!response.isSuccessful) {
      val errorBody = response.body?.string() ?: "Unknown error"
      // A server that does not know the `tools` field rejects the request itself; the request then
      // answers through the text protocol instead of failing.
      if (response.code in 400..499 &&
          (errorBody.contains("tool", ignoreCase = true) ||
              errorBody.contains("function", ignoreCase = true))
      ) {
        throw ToolsNotSupportedException("Local LLM endpoint rejected tools: $errorBody")
      }
      throw Exception("Local LLM error ${response.code}: $errorBody")
    }

    call.runCancellable {
      response.body?.source()?.use { source ->
        while (!source.exhausted()) {
          val rawLine = source.readUtf8Line() ?: break
          if (!rawLine.startsWith("data:")) continue

          val payload = rawLine.removePrefix("data:").trim()
          if (payload.isEmpty() || payload == "[DONE]") continue

          val deltaNode = OpenAiCompat.deltaOf(payload) ?: continue
          onDelta(deltaNode)
        }
      }
    }
  }

  override fun writeFile(filePath: String, content: String, append: Boolean): FileWriteResult {
    val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
    return writer.writeFile(filePath, content, append, createBackup = true)
  }
}
