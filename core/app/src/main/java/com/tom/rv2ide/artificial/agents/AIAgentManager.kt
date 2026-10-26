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

package com.tom.rv2ide.artificial.agents

import android.content.Context
import com.tom.rv2ide.artificial.agents.google.Gemini
import com.tom.rv2ide.artificial.agents.openai.OpenAI
import com.tom.rv2ide.artificial.agents.anthropic.Anthropic
import com.tom.rv2ide.artificial.agents.grok.Grok
import com.tom.rv2ide.artificial.agents.deepseek.DeepSeek
import com.tom.rv2ide.artificial.agents.local.LocalLLM
import com.tom.rv2ide.artificial.agents.tools.CopyFileTool
import com.tom.rv2ide.artificial.agents.tools.CreateFileTool
import com.tom.rv2ide.artificial.agents.tools.DeleteFileTool
import com.tom.rv2ide.artificial.agents.tools.EditFileTool
import com.tom.rv2ide.artificial.agents.tools.FileExistsTool
import com.tom.rv2ide.artificial.agents.tools.FindFilesTool
import com.tom.rv2ide.artificial.agents.tools.ListFilesTool
import com.tom.rv2ide.artificial.agents.tools.MakeDirectoryTool
import com.tom.rv2ide.artificial.agents.tools.MoveFileTool
import com.tom.rv2ide.artificial.agents.tools.ReadFilePartTool
import com.tom.rv2ide.artificial.agents.tools.ReadFileTool
import com.tom.rv2ide.artificial.agents.tools.SearchTool
import com.tom.rv2ide.artificial.agents.tools.ToolExecutor
import com.tom.rv2ide.artificial.agents.tools.WriteFileTool
import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import com.tom.rv2ide.artificial.project.awareness.ProjectData
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.secrets.ApiKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog

class AIAgentManager(private val context: Context) {

    private val permissionManager = AIPermissionManager(context)
    private var currentProjectRoot: File? = null
    private var currentProjectTree: String? = null

    private var currentProviderId: String = DEFAULT_PROVIDER_ID
    private var currentAgent: AIAgent? = null
    private val providerSwitchDialog = ProviderSwitchDialog(context)

    /** Prose streamed for the request in flight, so an interrupt can record what was shown. */
    private val partialResponse = StringBuilder()

    /** Whether the in-flight request's turn has already been recorded to the agent's history. */
    private var turnRecorded = false

    private val _currentModelName = MutableStateFlow("")
    private val _currentProviderName = MutableStateFlow("")

    private val _contextUsage = MutableStateFlow(ContextUsage(0, 0))

    /** History size against the compression limit, for the composer's usage indicator. */
    val contextUsage: StateFlow<ContextUsage> = _contextUsage.asStateFlow()

    /**
     * Provider/model currently in effect, for display.
     *
     * Observables rather than getters because the sidebar shows them in a chip that has to follow
     * changes made elsewhere — the settings page, or an automatic provider switch during a request.
     * [getCurrentModelName] / the provider remain available for one-off reads.
     */
    val currentModelName: StateFlow<String> = _currentModelName.asStateFlow()
    val currentProviderName: StateFlow<String> = _currentProviderName.asStateFlow()

    init {
        Gemini.registerAgent()
        OpenAI.registerAgent()
        Anthropic.registerAgent()
        Grok.registerAgent()
        DeepSeek.registerAgent()
        LocalLLM.registerAgent()
        
        permissionManager.setFileWriteEnabled(true)
        permissionManager.setRequireConfirmation(false)
        
        // Restore the provider the user selected in the settings screen instead of always starting
        // on Gemini. The selection was persisted by Agents.setProvider() but never read back here,
        // so a user who picked e.g. DeepSeek silently went back to Gemini on every launch.
        // If the persisted provider cannot be initialized (e.g. its API key was removed), fall back
        // to the default so the agent stays usable.
        val persistedProvider = Agents(context).getProvider()
        if (!setProvider(persistedProvider)) {
            setProvider(DEFAULT_PROVIDER_ID)
        }
    }
    
    fun getCurrentAgent(): AIAgent? = currentAgent

    fun setProvider(providerId: String): Boolean {
        android.util.Log.d("AIAgentManager", "setProvider called with: $providerId")
        
        val factory = AIAgentRegistry.getFactory(providerId)
        if (factory == null) {
            android.util.Log.e("AIAgentManager", "No factory found for provider: $providerId")
            return false
        }
        
        if (!factory.hasValidApiKey()) {
            android.util.Log.e("AIAgentManager", "No valid API key for provider: $providerId")
            return false
        }
        
        currentProviderId = providerId
        currentAgent = factory.create(context)
        android.util.Log.d("AIAgentManager", "Agent created: ${currentAgent != null}")
        
        factory.getApiKey()?.let { apiKey ->
            android.util.Log.d("AIAgentManager", "Initializing agent with API key")
            currentAgent?.initialize(apiKey, context)
            currentAgent?.setContext(context)

            
            android.util.Log.d("AIAgentManager", "Agent initialized: ${currentAgent?.isInitialized()}")
        }

        // Recorded here because this is the single place a provider actually becomes active. It used
        // to be a side effect of `Agents.setModel`, which no longer touches the selection, so without
        // this the provider chosen from the error dialog or by auto-switch would not survive a
        // restart.
        val initialized = currentAgent?.isInitialized() ?: false
        if (initialized) {
            Agents(context).setProvider(providerId)
        }

        // Publish for the sidebar's model chip. Cleared when the agent failed to initialise, so the
        // chip cannot keep showing the previous provider's model as if it were still in effect.
        _currentProviderName.value = if (initialized) {
            currentAgent?.providerName ?: providerId
        } else {
            ""
        }
        _currentModelName.value = if (initialized) {
            Agents(context).getAgent() ?: ""
        } else {
            ""
        }

        refreshContextUsage()

        return initialized
    }

    fun getCurrentProviderId(): String = currentProviderId
    
    fun getCurrentProviderName(): String {
        return currentAgent?.providerName ?: "Unknown"
    }
    
    fun getAvailableProviders(): List<ProviderInfo> {
        return AIAgentRegistry.getAvailableProviders().mapNotNull { providerId ->
            val factory = AIAgentRegistry.getFactory(providerId)
            val agent = factory?.create(context)
            agent?.let {
                ProviderInfo(
                    id = it.providerId,
                    name = it.providerName,
                    isAvailable = factory.hasValidApiKey()
                )
            }
        }
    }

    fun setProjectRoot(projectPath: String): Boolean {
        val projectRoot = File(projectPath)
        if (!projectRoot.exists()) return false

        currentProjectRoot = projectRoot
        permissionManager.addAllowedDirectory(projectRoot.absolutePath)

        return true
    }

    fun clearConversation() {
        // Dropped so an interrupt still in flight cannot write its partial answer back into
        // the cleared history.
        partialResponse.setLength(0)
        currentAgent?.clearConversation()
        refreshContextUsage()
    }

    suspend fun executeRequest(userRequest: String, activeFile: String?, callback: AIAgentCallback) {
        // Master switch. The AI Agent must be explicitly enabled before anything is sent to a
        // provider; previously the "ai_agent_enabled" preference only disabled the API key fields
        // in the settings screen and had no effect on the actual request path.
        if (!ApiKey.isAIAgentEnabled()) {
            callback.onError(
                "AI Agent is disabled.\n\nTurn it on in the AI sidebar settings to continue."
            )
            return
        }

        var success = false
        var providerSwitched = false

        currentAgent?.resetAttemptCount()

        partialResponse.setLength(0)
        turnRecorded = false

        // Before the request, not after: the provider is about to be handed the messages, and an
        // interrupt while the summary is produced then cancels the whole request.
        maybeCompressContext(callback)

        callback.onProcessing("Analyzing your request...")

        currentProjectRoot?.let { root ->
            val projectTree = ProjectData().showProjectTree(root)
            currentProjectTree = projectTree.tree
            currentAgent?.setProjectData(projectTree)
        }

        while (!success && (currentAgent?.canRetry() == true)) {
            try {
                val currentAttempt = currentAgent?.getCurrentAttemptCount() ?: 0

                if (currentAttempt > 0 && !providerSwitched) {
                    callback.onRetry(currentAttempt, "Thinking differently...")
                    delay(1000)
                }

                val toolOutcome = runToolLoop(userRequest, activeFile, callback)
                if (toolOutcome != ToolLoopOutcome.UNSUPPORTED) {
                    success = true
                    continue
                }

                val previousFileStates = captureCurrentFileStates()

                val reasoning = StringBuilder()
                val result = currentAgent?.generateCodeStreaming(
                    prompt = annotateOpenFile(userRequest, activeFile),
                    context = null,
                    language = "kotlin",
                    projectStructure = null,
                    onEvent = { event ->
                        if (event is AgentStreamEvent.ThinkingDelta) {
                            reasoning.append(event.text)
                        }
                        // Same as the tool loop: keep the prose so an interrupt can record it.
                        if (event is AgentStreamEvent.TextDelta) {
                            partialResponse.append(event.text)
                        }
                        callback.onStreamEvent(event)
                    }
                ) ?: Result.failure(Exception("No agent initialized"))

                // Recorded by the driver rather than by the provider: a code completion calls the
                // provider directly and must not enter the conversation history.
                result.onSuccess { response ->
                    currentAgent?.history?.recordTurn(userRequest, response, activeFile)
                    turnRecorded = true
                }

                result.fold(
                    onSuccess = { response ->
                        val modifications =
                            processModifications(response, reasoning.toString(), previousFileStates, callback)
                        val fileChanges = modifications.filterIsInstance<AgentSegment.FileChange>()

                        if (fileChanges.isNotEmpty()) {
                            callback.onProcessing("Modifying files...")
                            val allSuccessful = fileChanges.all { it.writeResult is FileWriteResult.Success }

                            if (allSuccessful) {
                                val results = fileChanges.map { mod ->
                                    val isNewFile = !previousFileStates.containsKey(mod.filePath)
                                    ModificationResult(
                                        filePath = mod.filePath,
                                        content = mod.content,
                                        success = true,
                                        message = "Modified successfully",
                                        isNewFile = isNewFile,
                                        previousContent = mod.previousContent
                                    )
                                }

                                val summary = createSummary(results)
                                callback.onSuccess(modifications, results, summary)
                                success = true
                            } else {
                                callback.onProcessing("Some files failed. Retrying...")
                                currentAgent?.incrementAttemptCount()
                                delay(1500)
                            }
                        } else {
                            val relevant = modifications.filterIsInstance<AgentSegment.Text>()
                            if (relevant.isEmpty()) {
                                callback.onProcessing("No files were modified. Retrying...")
                                currentAgent?.incrementAttemptCount()
                                delay(1500)
                            } else {
                                val summary = ModificationSummary(0, 0, 0, 0, 0, emptyList())
                                callback.onTextResponse(modifications, summary)
                                success = true
                            }
                        }
                    },
                  onFailure = { error ->
                      android.util.Log.e("AIAgentManager", "Error occurred: ${error.message}", error)
                      
                      val shouldSwitchProvider = error is com.tom.rv2ide.artificial.exceptions.RateLimitException ||
                                                error is com.tom.rv2ide.artificial.exceptions.QuotaExceededException ||
                                                error is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException ||
                                                error is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
                      
                      if (shouldSwitchProvider && !providerSwitched) {
                          val currentProviderName = currentAgent?.providerName ?: "Unknown"
                          val errorMsg = error.message ?: "Unknown error"
                          
                          if (providerSwitchDialog.isAutoSwitchEnabled()) {
                              val alternativeProvider = getAlternativeProvider()
                              if (alternativeProvider != null) {
                                  callback.onProcessing("⚠️ $currentProviderName: $errorMsg")
                                  callback.onProcessing("🔄 Auto-switching to another provider...")
                                  delay(1500)
                                  
                                  if (setProvider(alternativeProvider)) {
                                      providerSwitched = true
                                      currentAgent?.resetAttemptCount()
                                      
                                      val newProviderName = currentAgent?.providerName ?: "Unknown"
                                      callback.onProcessing("✅ Switched to $newProviderName")
                                  } else {
                                      val errorDisplay = formatErrorMessage(error)
                                      callback.onError("$errorDisplay\n\n❌ Failed to switch providers.")
                                      success = true
                                  }
                              } else {
                                  val errorDisplay = formatErrorMessage(error)
                                  callback.onError("$errorDisplay\n\n❌ No alternative providers available.")
                                  success = true
                              }
                          } else {
                              val errorDisplay = formatErrorMessage(error)
                              callback.onError("PROVIDER_SWITCH_REQUIRED::$errorDisplay")
                              success = true
                          }
                      } else if ((currentAgent?.canRetry() == true) && !providerSwitched) {
                          callback.onRetry(
                              currentAgent?.getCurrentAttemptCount() ?: 0,
                              "Error: ${error.message?.take(50) ?: "Unknown error"}. Retrying..."
                          )
                          currentAgent?.incrementAttemptCount()
                          delay(1500)
                      } else {
                          val errorDisplay = formatErrorMessage(error)
                          callback.onError(errorDisplay)
                          success = true
                      }
                  }
                )
            } catch (e: CancellationException) {
                recordInterruptedTurn(userRequest, activeFile)
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AIAgentManager", "Exception occurred: ${e.message}", e)
                
                if (currentAgent?.canRetry() == true) {
                    callback.onRetry(
                        currentAgent?.getCurrentAttemptCount() ?: 0,
                        "Exception: ${e.message?.take(50) ?: "Unknown"}. Trying again..."
                    )
                    currentAgent?.incrementAttemptCount()
                    try {
                        delay(1500)
                    } catch (e: CancellationException) {
                        recordInterruptedTurn(userRequest, activeFile)
                        throw e
                    }
                } else {
                    val errorDisplay = formatErrorMessage(e)
                    callback.onError(errorDisplay)
                    success = true
                }
            }
        }

        refreshContextUsage()

        if (!success) {
          val attemptCount = currentAgent?.getCurrentAttemptCount() ?: 0
          val agentName = currentAgent?.providerName ?: "No agent initialized"
          callback.onError("Failed after $attemptCount attempts with $agentName.\n\nPlease check your API key and try again.")
          undoLastModification()
        }
    }

    /**
     * Keeps the answer of a request that was interrupted mid-flight.
     *
     * Without this, an interrupt would drop the turn from history entirely: only a completed
     * turn is recorded normally. The prose that was already shown is what the next message follows.
     */
    private fun recordInterruptedTurn(userRequest: String, activeFile: String?) {
        if (turnRecorded || partialResponse.isEmpty()) return
        currentAgent?.history?.recordTurn(userRequest, partialResponse.toString(), activeFile)
        turnRecorded = true
        refreshContextUsage()
    }

    /**
     * Folds the older turns into a summary once the history has outgrown its limit.
     *
     * A failure is silent: the request then runs with the full history, exactly as it did before
     * compression existed, and a provider problem is reported by the request itself.
     */
    private suspend fun maybeCompressContext(callback: AIAgentCallback) {
        val agent = currentAgent ?: return
        val plan = agent.history.compressionPlan(Agents(context).getContextCharLimit()) ?: return

        callback.onProcessing(COMPRESSING_STATUS)
        val summary = agent.summarize(plan.text)
        // A cancelled job must not read as a failed summary: the interrupt has to reach the request,
        // and nothing may be folded into a history the user just stopped.
        currentCoroutineContext().ensureActive()
        val folded = summary.getOrNull()?.trim().orEmpty()
        if (folded.isEmpty()) return

        agent.history.applyCompression(folded, plan.foldedTurns)
        refreshContextUsage()
        callback.onContextCompressed(folded)
    }

    /**
     * Compresses the history on the user's request, whatever its size.
     *
     * Returns the summary that was stored, or null when there was nothing to fold or the summary
     * could not be produced. Nothing is reported to the transcript from here: the caller drives the
     * UI for it, unlike the automatic path.
     */
    suspend fun compressContextNow(): String? {
        val agent = currentAgent ?: return null
        val plan = agent.history.compressionPlan(
            limitChars = Agents(context).getContextCharLimit(),
            force = true
        ) ?: return null

        val summary = agent.summarize(plan.text)
        currentCoroutineContext().ensureActive()
        val folded = summary.getOrNull()?.trim().orEmpty()
        if (folded.isEmpty()) return null

        agent.history.applyCompression(folded, plan.foldedTurns)
        refreshContextUsage()
        return folded
    }

    /** Whether the history holds turns old enough to be folded by the manual trigger. */
    fun canCompressContext(): Boolean = (currentAgent?.history?.foldableTurns() ?: 0) > 0

    /** Republished after every history change; the settings also call it when the limit is edited. */
    fun refreshContextUsage() {
        _contextUsage.value = ContextUsage(
            usedChars = currentAgent?.history?.usedChars() ?: 0,
            limitChars = Agents(context).getContextCharLimit()
        )
    }

    private enum class ToolLoopOutcome {
        SUCCESS,
        FAILURE,
        /** The provider cannot call tools; answer through the text protocol instead. */
        UNSUPPORTED
    }

    private sealed interface TurnRequestResult {
        data class Success(val turn: AgentTurn) : TurnRequestResult
        object Unsupported : TurnRequestResult
        data class Failed(val error: Throwable) : TurnRequestResult
    }

    /**
     * Answers one request through the provider's tool-calling API.
     *
     * Tool traffic stays inside the request: only the user message and the final answer are recorded
     * to [AgentHistory], so a follow-up request does not replay every file that was read. Writes made
     * before a failure are not rolled back — each one was a deliberate, visible step.
     */
    private suspend fun runToolLoop(
        userRequest: String,
        activeFile: String?,
        callback: AIAgentCallback
    ): ToolLoopOutcome {
        val agent = currentAgent ?: return ToolLoopOutcome.UNSUPPORTED
        val toolExecutor = createToolExecutor(callback)

        val messages = mutableListOf<AgentMessage>()
        messages.add(AgentMessage.System(buildToolSystemPrompt()))
        messages.addAll(agent.history.snapshot())
        messages.add(AgentMessage.User(annotateOpenFile(userRequest, activeFile)))

        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round++
            when (val requested = requestToolTurn(agent, messages, toolExecutor.specs, callback)) {
                is TurnRequestResult.Unsupported -> return ToolLoopOutcome.UNSUPPORTED
                is TurnRequestResult.Failed -> {
                    callback.onError(formatErrorMessage(requested.error))
                    return ToolLoopOutcome.FAILURE
                }
                is TurnRequestResult.Success -> {
                    val turn = requested.turn
                    if (turn.toolCalls.isEmpty()) {
                        agent.history.recordTurn(userRequest, turn.text, activeFile)
                        turnRecorded = true
                        return ToolLoopOutcome.SUCCESS
                    }

                    messages.add(
                        AgentMessage.Assistant(
                            content = turn.text,
                            toolCalls = turn.toolCalls,
                            reasoningContent = turn.reasoningContent
                        )
                    )
                    for (call in turn.toolCalls) {
                        // An interrupt between calls stops the rest of the queue.
                        currentCoroutineContext().ensureActive()
                        val showsRow = call.name !in FILE_ROW_TOOLS
                        if (showsRow) {
                            callback.onToolCallStarted(
                                call.id,
                                call.name,
                                toolExecutor.summarize(call),
                                call.arguments
                            )
                        }
                        val result = toolExecutor.execute(call)
                        if (showsRow) {
                            callback.onToolCallFinished(call.id, result.content, result.isError)
                        }
                        messages.add(AgentMessage.Tool(call.id, result.content, result.isError))
                    }
                }
            }
        }

        callback.onError("The agent kept calling tools and never produced an answer; giving up.")
        return ToolLoopOutcome.FAILURE
    }

    /**
     * One round, with its own transient retry: [messages] are preserved, so a retry never repeats
     * work the earlier attempt already did.
     */
    private suspend fun requestToolTurn(
        agent: AIAgent,
        messages: List<AgentMessage>,
        tools: List<AgentToolSpec>,
        callback: AIAgentCallback
    ): TurnRequestResult {
        var lastError: Throwable? = null
        repeat(TOOL_TURN_ATTEMPTS) { attempt ->
            val result = try {
                agent.generateTurn(messages, tools) { event ->
                    // Keep the prose so an interrupt can still record the half-finished turn.
                    if (event is AgentStreamEvent.TextDelta) {
                        partialResponse.append(event.text)
                    }
                    callback.onStreamEvent(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            result.fold(
                onSuccess = { return TurnRequestResult.Success(it) },
                onFailure = { error ->
                    if (error is ToolsNotSupportedException) {
                        return TurnRequestResult.Unsupported
                    }
                    if (error is RateLimitException || error is QuotaExceededException ||
                        error is InsufficientBalanceException || error is InvalidApiKeyException
                    ) {
                        return TurnRequestResult.Failed(error)
                    }
                    lastError = error
                }
            )

            if (attempt < TOOL_TURN_ATTEMPTS - 1) {
                delay(TOOL_RETRY_DELAY_MS)
            }
        }
        return TurnRequestResult.Failed(lastError ?: Exception("Tool request failed"))
    }

    private fun createToolExecutor(callback: AIAgentCallback): ToolExecutor {
        val allowedDirectories = permissionManager.getAllowedDirectories()
        val isAllowed: (String) -> Boolean = { path ->
            isPathWithinDirectories(path, allowedDirectories)
        }

        val canonicalRoots = allowedDirectories.mapTo(mutableSetOf()) { canonicalPathOf(File(it)) }
        val performWrite: suspend (String, String, Boolean) -> FileWriteResult = { path, content, append ->
            performToolWrite(path, content, append, callback)
        }

        return ToolExecutor(
            listOf(
                ReadFileTool(isAllowed),
                ReadFilePartTool(isAllowed),
                WriteFileTool(isAllowed, performWrite),
                EditFileTool(isAllowed, performWrite),
                CreateFileTool(isAllowed, performWrite),
                DeleteFileTool(isAllowed, canonicalRoots),
                MoveFileTool(isAllowed),
                CopyFileTool(isAllowed),
                FileExistsTool(isAllowed),
                MakeDirectoryTool(isAllowed),
                FindFilesTool(isAllowed, currentProjectRoot),
                ListFilesTool(isAllowed, currentProjectRoot),
                SearchTool(isAllowed, currentProjectRoot)
            )
        )
    }

    /**
     * Tool mode's counterpart of [writeFileBlock]: reports the row, resolves what the file held
     * before this write, records the attempt for undo, and finalises the row as soon as the write
     * finishes rather than at the end of the answer.
     */
    private suspend fun performToolWrite(
        filePath: String,
        content: String,
        append: Boolean,
        callback: AIAgentCallback
    ): FileWriteResult {
        val fileName = File(filePath).name
        callback.onFileModifying(filePath, fileName)

        val previousContent = readCurrentContent(filePath)
        val writeResult = currentAgent?.writeFile(filePath, content, append)
            ?: FileWriteResult.Error("No agent initialized")
        val success = writeResult is FileWriteResult.Success
        val newContent = if (append) (previousContent ?: "") + content else content
        currentAgent?.recordModification(filePath, previousContent, newContent, success)

        callback.onFileChangeCompleted(filePath, success, previousContent, newContent)
        return writeResult
    }

    private fun buildToolSystemPrompt(): String {
        val rules = WritingRules.Instructions().toolMode()
        val tree = currentProjectTree ?: return rules
        return buildString {
            append(rules)
            append("\n\n=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
            append(tree)
        }
    }

    /**
     * Canonical containment: the tools accept paths produced by a model, so a string prefix check
     * alone would let `..` walk out of the project.
     */
    private fun isPathWithinDirectories(path: String, allowedDirectories: Set<String>): Boolean {
        val candidate = canonicalPathOf(File(path))
        return allowedDirectories.any { directory ->
            val base = canonicalPathOf(File(directory))
            candidate == base || candidate.startsWith(base.trimEnd('/') + "/")
        }
    }

    private fun canonicalPathOf(file: File): String =
        try {
            file.canonicalPath
        } catch (e: IOException) {
            file.absolutePath
        }

    private fun getAlternativeProvider(): String? {
        val availableProviders = AIAgentRegistry.getAvailableProviders()
        return availableProviders.firstOrNull { it != currentProviderId }
    }

    private suspend fun processModifications(
        response: String,
        reasoning: String,
        previousFileStates: Map<String, String>,
        callback: AIAgentCallback
    ): List<AgentSegment> {
        val segments = mutableListOf<AgentSegment>()
        val prose = StringBuilder()

        fun flushProse() {
            if (prose.isNotBlank()) segments.add(AgentSegment.Text(prose.toString()))
            prose.setLength(0)
        }

        if (reasoning.isNotBlank()) {
            flushProse()
            segments.add(AgentSegment.Thinking(reasoning))
        }

        AgentStreamParser().parseAll(response).forEach { event ->
            when (event) {
                is AgentStreamEvent.TextDelta -> prose.append(event.text)
                // Reasoning arrives on its own event and was added above; parseAll never yields it.
                is AgentStreamEvent.ThinkingDelta -> Unit
                is AgentStreamEvent.FileCompleted -> {
                    flushProse()
                    segments.add(writeFileBlock(event.filePath, event.content, previousFileStates, callback))
                }
            }
        }
        flushProse()

        return segments
    }

    private suspend fun writeFileBlock(
        filePath: String,
        content: String,
        previousFileStates: Map<String, String>,
        callback: AIAgentCallback
    ): AgentSegment.FileChange {
        val fileName = File(filePath).name
        callback.onFileModifying(filePath, fileName)

        val previousContent = resolvePreviousContent(filePath, previousFileStates)
        val writeResult = currentAgent?.writeFile(filePath, content)
            ?: FileWriteResult.Error("No agent initialized")

        val success = writeResult is FileWriteResult.Success
        currentAgent?.recordModification(filePath, previousContent, content, success)

        callback.onFileModified(filePath, fileName, success)
        delay(300)

        return AgentSegment.FileChange(filePath, content, previousContent, writeResult)
    }

    /**
     * Resolves the content the file held immediately before this write.
     *
     * Serves both the diff shown in the transcript and the state [AIAgent.recordModification] stores
     * for undo, which happen to want the same thing: whatever the file was just before the write.
     *
     * The captured snapshot only covers a subset of extensions (see [captureCurrentFileStates]),
     * yet the agent may legitimately rewrite files outside of it (e.g. `gradle.properties` or
     * `libs.versions.toml`). Returning `null` for such an existing file is wrong: [undoLastModification]
     * interprets `null` as "the agent created this file" and deletes it, which silently destroys the
     * user's own file (and [executeRequest] triggers an undo automatically when it gives up).
     *
     * So the snapshot is only a cache: on a miss, read the file from disk. `null` is then returned
     * only when the file genuinely did not exist before the write.
     */
    private fun resolvePreviousContent(
        filePath: String,
        previousFileStates: Map<String, String>
    ): String? {
        previousFileStates[filePath]?.let { return it }
        return readCurrentContent(filePath)
    }

    private fun readCurrentContent(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null

        return try {
            file.readText()
        } catch (e: Exception) {
            // Cannot read it, but it does exist: report empty content instead of `null` so that undo
            // restores (empties) the file rather than deleting it.
            ""
        }
    }

    private fun formatErrorMessage(error: Throwable): String {
        val errorMessage = error.message ?: "Unknown error occurred"
        val stackTrace = error.stackTraceToString().take(500)
        val providerName = currentAgent?.providerName ?: "Unknown"
        
        return when (error) {
            is com.tom.rv2ide.artificial.exceptions.RateLimitException -> 
                "⚠️ RATE LIMIT EXCEEDED\n\nThe API rate limit has been exceeded.\nPlease wait a few minutes before trying again.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.QuotaExceededException -> 
                "⚠️ QUOTA EXCEEDED\n\nYour API quota has been exhausted.\nPlease check your billing or upgrade your plan.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException -> 
                "💳 INSUFFICIENT BALANCE\n\nYour account balance is too low to process this request.\nPlease add credits or upgrade your plan.\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException -> 
                "❌ INVALID API KEY\n\nThe API key is invalid or expired.\nPlease update your API key in the configuration.\n\nDetails: $errorMessage"
            is java.net.UnknownHostException ->
                "🌐 NETWORK ERROR\n\nCould not connect to the API server.\nPlease check your internet connection.\n\nDetails: $errorMessage"
            is java.net.SocketTimeoutException ->
                "⏱️ TIMEOUT ERROR\n\nThe request took too long to complete.\nPlease try again.\n\nDetails: $errorMessage"
            is org.json.JSONException ->
                "📄 JSON PARSING ERROR\n\nFailed to parse API response.\nThe API may be experiencing issues.\n\nDetails: $errorMessage"
            else -> 
                "❌ ERROR OCCURRED\n\nProvider: $providerName\nError Type: ${error.javaClass.simpleName}\n\nMessage: $errorMessage\n\nStack Trace (first 500 chars):\n$stackTrace"
        }
    }

    fun showProviderErrorDialogFromFragment(
        activity: android.app.Activity,
        errorMessage: String,
        onProviderSelected: (String) -> Unit
    ) {
        val currentProviderName = currentAgent?.providerName ?: "Unknown"
        val availableProviders = getAvailableProviders()
            .filter { it.id != currentProviderId && it.isAvailable }
            .map { Pair(it.id, it.name) }
        
        providerSwitchDialog.showProviderErrorDialog(
            currentProviderName,
            errorMessage,
            availableProviders,
            onProviderSelected = { providerId ->
                setProvider(providerId)
                val agents = Agents(context)
                // Use the provider's declared default rather than "first item of the list": the
                // order of a fetched catalogue is not something we control.
                agents.setModel(providerId, agents.getDefaultModelForProvider(providerId))
                // `setProvider` above already initialized the agent, but with the model that was
                // current at the time; the default written just now has to be picked up.
                reinitializeWithSelectedModel()
                onProviderSelected(providerId)
            },
            onEnableAutoSwitch = {
                val alternativeProvider = getAlternativeProvider()
                if (alternativeProvider != null) {
                    setProvider(alternativeProvider)
                    val agents = Agents(context)
                    agents.setModel(
                        alternativeProvider,
                        agents.getDefaultModelForProvider(alternativeProvider)
                    )
                    reinitializeWithSelectedModel()
                }
            }
        )
    }
    
    fun isAutoSwitchEnabled(): Boolean {
        return providerSwitchDialog.isAutoSwitchEnabled()
    }
    
    fun setAutoSwitch(enabled: Boolean) {
        providerSwitchDialog.setAutoSwitch(enabled)
    }

    private fun createSummary(results: List<ModificationResult>): ModificationSummary {
        val successful = results.count { it.success }
        val failed = results.count { !it.success }
        val newFiles = results.count { it.isNewFile }
        val modifiedFiles = results.count { !it.isNewFile }

        val fileDetails = results.map { result ->
            FileDetail(
                fileName = File(result.filePath).name,
                filePath = result.filePath,
                status = if (result.success) FileStatus.SUCCESS else FileStatus.FAILED,
                changeType = if (result.isNewFile) ChangeType.CREATED else ChangeType.MODIFIED
            )
        }

        return ModificationSummary(
            totalFiles = results.size,
            successfulFiles = successful,
            failedFiles = failed,
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            fileDetails = fileDetails
        )
    }

    private fun captureCurrentFileStates(): Map<String, String> {
        val states = mutableMapOf<String, String>()
        val projectRoot = currentProjectRoot ?: return states

        if (!projectRoot.exists()) return states

        projectRoot.walkTopDown()
            .filter { it.isFile }
            .filter {
                it.extension in listOf("kt", "java", "xml", "gradle", "kts") &&
                !it.path.contains("/build/") &&
                !it.path.contains("/.gradle/")
            }
            .forEach { file ->
                try {
                    states[file.absolutePath] = file.readText()
                } catch (e: Exception) {
                }
            }

        return states
    }

    fun undoLastModification(): Boolean {
        return currentAgent?.undoLastModification() ?: false
    }

    fun reinitializeWithSelectedModel() {
        val factory = AIAgentRegistry.getFactory(currentProviderId)
        factory?.getApiKey()?.let { apiKey ->
            currentAgent?.reinitializeWithNewModel(apiKey, context)
        }
        // The model may have changed (settings page), and may also have been switched to something
        // else by the provider itself; republish so the chip stays truthful.
        _currentModelName.value = Agents(context).getAgent() ?: ""
        _currentProviderName.value = currentAgent?.providerName ?: _currentProviderName.value
        refreshContextUsage()
    }

    fun getCurrentModelName(): String {
        val agents = Agents(context)
        return agents.getAgent()
    }
    
    fun getConversationHistory(): List<UnifiedModificationAttempt> {
        return currentAgent?.getModificationHistory()?.map {
            UnifiedModificationAttempt(
                timestamp = it.timestamp,
                filePath = it.filePath,
                previousContent = it.previousContent,
                newContent = it.newContent,
                attemptNumber = it.attemptNumber,
                success = it.success
            )
        } ?: emptyList()
    }

    interface AIAgentCallback {
        fun onProcessing(message: String)
        fun onFileModifying(filePath: String, fileName: String)
        fun onFileModified(filePath: String, fileName: String, success: Boolean)

        /**
         * Prose as the provider produces it, before the reply is complete.
         *
         * Only text is reported here: a file block is not delimited until its closing line has been
         * read, and it is written (with its diff) only once the whole reply is available.
         */
        fun onStreamEvent(event: AgentStreamEvent) {}

        /** A tool call is starting; [summary] is a short display form of [arguments]. */
        fun onToolCallStarted(callId: String, toolName: String, summary: String, arguments: String) {}

        /** The outcome of the call announced by [onToolCallStarted]; completes its pending row. */
        fun onToolCallFinished(callId: String, result: String, isError: Boolean) {}

        /**
         * The history was folded into [summary]; the model now receives the summary instead of the
         * turns it replaced, and the transcript shows it as its own entry.
         */
        fun onContextCompressed(summary: String) {}

        /**
         * Tool mode's per-write finalisation: the row is completed as soon as the write finishes,
         * instead of waiting for the whole answer. The fallback path finalises through [onSuccess].
         */
        fun onFileChangeCompleted(
            filePath: String,
            success: Boolean,
            previousContent: String?,
            newContent: String
        ) {}

        /**
         * The reply, in the order the agent produced it: prose and file writes interleaved.
         *
         * [modifications] is the full sequence, so a caller that renders the transcript preserves
         * the agent's ordering; [results] and [summary] describe the writes alone.
         */
        fun onSuccess(
            modifications: List<AgentSegment>,
            results: List<ModificationResult>,
            summary: ModificationSummary
        )
        fun onTextResponse(modifications: List<AgentSegment>, summary: ModificationSummary)
        fun onError(message: String)
        fun onRetry(attemptNumber: Int, message: String)
    }

    data class ModificationResult(
        val filePath: String,
        val content: String,
        val success: Boolean,
        val message: String,
        val isNewFile: Boolean = false,
        val previousContent: String? = null
    )

    data class ModificationSummary(
        val totalFiles: Int,
        val successfulFiles: Int,
        val failedFiles: Int,
        val newFiles: Int,
        val modifiedFiles: Int,
        val fileDetails: List<FileDetail>
    )

    data class FileDetail(
        val fileName: String,
        val filePath: String,
        val status: FileStatus,
        val changeType: ChangeType
    )

    enum class FileStatus { SUCCESS, FAILED }
    enum class ChangeType { CREATED, MODIFIED }
    
    data class ProviderInfo(
        val id: String,
        val name: String,
        val isAvailable: Boolean
    )

    /** History size against the compression limit, for the composer's usage indicator. */
    data class ContextUsage(val usedChars: Int, val limitChars: Int)

    companion object {
        /** Provider used for the very first launch, before the user picks one. */
        private const val DEFAULT_PROVIDER_ID = "gemini"

        /** Tool-calling rounds a single request may run before it is given up on. */
        private const val MAX_TOOL_ROUNDS = 12

        /** Attempts at a single round before its failure is surfaced. */
        private const val TOOL_TURN_ATTEMPTS = 3

        private const val TOOL_RETRY_DELAY_MS = 1000L

        /** Progress line shown while the older turns are being folded into a summary. */
        const val COMPRESSING_STATUS = "Compressing context..."

        /** Tools whose calls are rendered as file rows, with a diff, instead of tool rows. */
        private val FILE_ROW_TOOLS = setOf(WriteFileTool.NAME, EditFileTool.NAME, CreateFileTool.NAME)
    }
}

data class UnifiedModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)