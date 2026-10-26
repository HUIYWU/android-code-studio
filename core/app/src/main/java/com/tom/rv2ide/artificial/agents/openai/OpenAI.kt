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

package com.tom.rv2ide.artificial.agents.openai

import android.content.Context
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.catalog.ModelRepository
import com.tom.rv2ide.artificial.catalog.ModelSources
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import com.tom.rv2ide.artificial.exceptions.*
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.AgentHistory
import com.tom.rv2ide.artificial.agents.AgentMessage
import com.tom.rv2ide.artificial.agents.AgentStreamEvent
import com.tom.rv2ide.artificial.agents.AgentToolSpec
import com.tom.rv2ide.artificial.agents.AgentTurn
import com.tom.rv2ide.artificial.agents.OpenAiCompat
import com.tom.rv2ide.artificial.agents.ToolCallAccumulator
import com.tom.rv2ide.artificial.agents.runCancellable
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.artificial.agents.ModificationAttempt

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class OpenAI : AIAgent {

  private var apiKey: String? = null
  private val writingRules = WritingRules.Instructions()
  private var projectTreeResult: ProjectTreeResult? = null
  private var fileWriter: AIFileWriter? = null
  override val history = AgentHistory()
  private val modificationHistory = mutableListOf<ModificationAttempt>()
  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3
  private var agents: Agents? = null
  private var selectedModel: String = ModelRepository.getDefaultModel(PROVIDER_ID)
  override val providerId = PROVIDER_ID
  override val providerName = ModelSources.providerName(PROVIDER_ID)

  companion object {
      /** Id shared by the registry, the catalogue and the persisted selection. */
      const val PROVIDER_ID = "openai"

      fun registerAgent() {
          AIAgentRegistry.register(PROVIDER_ID, object : AIAgentRegistry.AgentFactory {
              override fun create(context: Context): AIAgent {
                  return OpenAI()
              }
              
              override fun hasValidApiKey(): Boolean {
                  val key = ApiKey.getOpenAIApiKey()
                  android.util.Log.d("OpenAI", "hasValidApiKey check: ${key != null && key.isNotEmpty()}, key length: ${key?.length ?: 0}")
                  return key != null && key.isNotEmpty()
              }
              
              override fun getApiKey(): String? {
                  val key = ApiKey.getOpenAIApiKey()
                  android.util.Log.d("OpenAI", "getApiKey called, returning key of length: ${key?.length ?: 0}")
                  return key
              }
          })
      }
  }
        
  override fun initialize(apiKey: String, context: Context) {
      try {
          this.apiKey = apiKey
          agents = Agents(context)
          val agentsRef = agents!!
          // Resolved through the catalogue instead of a literal: a stored name the provider no
          // longer offers is replaced by its default, which is what the copies of these fallbacks
          // used to disagree about.
          selectedModel = agentsRef.resolveModel(PROVIDER_ID)
      } catch (e: Exception) {
          throw e
      }
  }

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
          val key = apiKey
              ?: return@withContext Result.failure(
                  IllegalStateException("OpenAI service not initialized")
              )

          val needsCorrection = isUserRequestingCorrection(prompt)

          val fullPrompt = buildString {
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

          val response = callOpenAIAPI(key, fullPrompt)

          if (response.isBlank()) {
            return@withContext Result.failure(Exception("Empty response from AI"))
          }

          Result.success(response)
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
          val key = apiKey
              ?: return@withContext Result.failure(
                  IllegalStateException("OpenAI service not initialized")
              )

           val requestBody = buildToolRequestBody(messages, tools)
           val text = StringBuilder()
           val reasoningContent = StringBuilder()
           val toolCalls = ToolCallAccumulator()

           streamToolTurn(key, requestBody) { delta ->
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
            return@withContext Result.failure(Exception("Empty response from AI"))
          }

          Result.success(
             AgentTurn(
               text = text.toString(),
               toolCalls = toolCalls.finish(),
               reasoningContent = reasoningContent.toString()
             )
           )
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  private suspend fun callOpenAIAPI(apiKey: String, prompt: String): String {
    android.util.Log.d("OpenAI", "Starting API call to OpenAI")
    
    val url = URL("https://api.openai.com/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection
    
    try {
      return connection.runCancellable {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
      
        val messages = JSONArray()
      
        val systemMessage = JSONObject()
        systemMessage.put("role", "system")
        systemMessage.put("content", writingRules.useThis())
        messages.put(systemMessage)
      
        val userMessage = JSONObject()
        userMessage.put("role", "user")
        userMessage.put("content", prompt)
        messages.put(userMessage)
      
        val requestBody = JSONObject()
        requestBody.put("model", selectedModel)
        requestBody.put("messages", messages)
        requestBody.put("temperature", 0.7)
        requestBody.put("max_tokens", 4096)
      
        android.util.Log.d("OpenAI", "Request body: ${requestBody.toString()}")
      
        connection.outputStream.use { os ->
          os.write(requestBody.toString().toByteArray())
        }
      
        val responseCode = connection.responseCode
        android.util.Log.d("OpenAI", "Response code: $responseCode")
      
        if (responseCode != HttpURLConnection.HTTP_OK) {
          val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
          android.util.Log.e("OpenAI", "Error response: $errorStream")
        
          // Parse error response
          try {
            val errorJson = JSONObject(errorStream)
            val errorObj = errorJson.optJSONObject("error")
            val errorMessage = errorObj?.optString("message") ?: errorStream
            val errorType = errorObj?.optString("type") ?: ""
            val errorCode = errorObj?.optString("code") ?: ""
          
            android.util.Log.e("OpenAI", "Error type: $errorType, code: $errorCode, message: $errorMessage")
          
            // Identify specific error types
            when {
              responseCode == 429 || errorType.contains("rate_limit") || errorCode.contains("rate_limit") -> 
                throw com.tom.rv2ide.artificial.exceptions.RateLimitException("OpenAI rate limit exceeded: $errorMessage")
              errorType.contains("insufficient_quota") || errorMessage.contains("quota") || errorMessage.contains("billing") -> 
                throw com.tom.rv2ide.artificial.exceptions.QuotaExceededException("OpenAI quota exceeded: $errorMessage")
              errorType.contains("invalid_api_key") || errorCode.contains("invalid_api_key") -> 
                throw com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException("Invalid OpenAI API key: $errorMessage")
              responseCode == 401 -> 
                throw com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException("OpenAI authentication failed: $errorMessage")
              else -> 
                throw Exception("OpenAI API error ($responseCode) - Type: $errorType, Code: $errorCode, Message: $errorMessage")
            }
          } catch (e: com.tom.rv2ide.artificial.exceptions.RateLimitException) {
            throw e
          } catch (e: com.tom.rv2ide.artificial.exceptions.QuotaExceededException) {
            throw e
          } catch (e: com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException) {
            throw e
          } catch (e: Exception) {
            throw Exception("OpenAI API error ($responseCode): $errorStream")
          }
        }
      
        val responseBody = connection.inputStream.bufferedReader().readText()
        android.util.Log.d("OpenAI", "Success response received, length: ${responseBody.length}")
      
        val jsonResponse = JSONObject(responseBody)
      
        val choices = jsonResponse.getJSONArray("choices")
        if (choices.length() > 0) {
          val firstChoice = choices.getJSONObject(0)
          val message = firstChoice.getJSONObject("message")
          message.getString("content")
        } else {
      
          throw Exception("No response from OpenAI API")
        }
      }
    } catch (e: com.tom.rv2ide.artificial.exceptions.RateLimitException) {
      android.util.Log.e("OpenAI", "Rate limit exception", e)
      throw e
    } catch (e: com.tom.rv2ide.artificial.exceptions.QuotaExceededException) {
      android.util.Log.e("OpenAI", "Quota exceeded exception", e)
      throw e
    } catch (e: com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException) {
      android.util.Log.e("OpenAI", "Invalid API key exception", e)
      throw e
    } catch (e: java.net.SocketTimeoutException) {
      android.util.Log.e("OpenAI", "Timeout exception", e)
      throw Exception("OpenAI request timeout: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      android.util.Log.e("OpenAI", "Network exception", e)
      throw Exception("Network error - cannot reach OpenAI: ${e.message}")
    } catch (e: Exception) {
      android.util.Log.e("OpenAI", "General exception", e)
      throw e
    } finally {
      connection.disconnect()
    }
  }

  private fun buildToolRequestBody(
      messages: List<AgentMessage>,
      tools: List<AgentToolSpec>,
  ): JSONObject {
    val requestBody = JSONObject()
    requestBody.put("model", selectedModel)
    requestBody.put("messages", OpenAiCompat.messagesJson(messages))
    if (tools.isNotEmpty()) {
      requestBody.put("tools", OpenAiCompat.toolsJson(tools))
    }
    requestBody.put("temperature", 0.7)
    requestBody.put("max_tokens", 4096)
    requestBody.put("stream", true)

    return requestBody
  }

  /**
   * Streams a tool-aware chat request, handing each whole delta to [onDelta].
   *
   * OpenAI-compatible SSE: one `data: {...}` line per chunk, terminated by `data: [DONE]`.
   */
  private suspend fun streamToolTurn(
      apiKey: String,
      requestBody: JSONObject,
      onDelta: (JSONObject) -> Unit,
  ) {
    val url = URL("https://api.openai.com/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection

    try {
      connection.runCancellable {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.doOutput = true
        connection.connectTimeout = 30000
        // A stream is idle between chunks, so the read timeout applies per chunk rather than to the
        // whole reply; 30s of silence means the stream has stalled.
        connection.readTimeout = 30000

        connection.outputStream.use { os ->
          os.write(requestBody.toString().toByteArray())
        }

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
          throwApiError(responseCode, connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error")
        }

        connection.inputStream.bufferedReader().use { reader ->
          while (true) {
            val rawLine = reader.readLine() ?: break
            if (!rawLine.startsWith("data:")) continue

            val payload = rawLine.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue

            val deltaNode = OpenAiCompat.deltaOf(payload) ?: continue
            onDelta(deltaNode)
          }
        }
      }
    } catch (e: RateLimitException) {
      throw e
    } catch (e: QuotaExceededException) {
      throw e
    } catch (e: InvalidApiKeyException) {
      throw e
    } catch (e: java.net.SocketTimeoutException) {
      throw Exception("OpenAI request timeout: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      throw Exception("Network error - cannot reach OpenAI: ${e.message}")
    } finally {
      connection.disconnect()
    }
  }

  private fun throwApiError(responseCode: Int, errorBody: String): Nothing {
    try {
      val errorJson = JSONObject(errorBody)
      val errorObj = errorJson.optJSONObject("error")
      val errorMessage = errorObj?.optString("message") ?: errorBody
      val errorType = errorObj?.optString("type") ?: ""
      val errorCode = errorObj?.optString("code") ?: ""

      when {
        responseCode == 429 || errorType.contains("rate_limit") || errorCode.contains("rate_limit") ->
            throw RateLimitException("OpenAI rate limit exceeded: $errorMessage")
        errorType.contains("insufficient_quota") || errorMessage.contains("quota") || errorMessage.contains("billing") ->
            throw QuotaExceededException("OpenAI quota exceeded: $errorMessage")
        errorType.contains("invalid_api_key") || errorCode.contains("invalid_api_key") ->
            throw InvalidApiKeyException("Invalid OpenAI API key: $errorMessage")
        responseCode == 401 ->
            throw InvalidApiKeyException("OpenAI authentication failed: $errorMessage")
        else ->
            throw Exception("OpenAI API error ($responseCode) - Type: $errorType, Code: $errorCode, Message: $errorMessage")
      }
    } catch (e: RateLimitException) {
      throw e
    } catch (e: QuotaExceededException) {
      throw e
    } catch (e: InvalidApiKeyException) {
      throw e
    } catch (e: Exception) {
      throw Exception("OpenAI API error ($responseCode): $errorBody")
    }
  }

  override fun writeFile(filePath: String, content: String, append: Boolean): FileWriteResult {
    val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
    return writer.writeFile(filePath, content, append, createBackup = true)
  }


  override fun isInitialized(): Boolean = apiKey != null
}
