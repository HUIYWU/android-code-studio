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
package com.tom.rv2ide.language.services.kotlin

import android.content.Context
import com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.language.services.kotlin.compiler.KotlinCompilerService
import com.tom.rv2ide.language.services.kotlin.etc.LspFeatures
import com.tom.rv2ide.language.services.kotlin.providers.KotlinCodeFormatProvider
import com.tom.rv2ide.preferences.internal.LSPPreferences
import com.tom.rv2ide.lsp.models.*
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.IWorkspace
import java.nio.file.Path
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinLanguageServer(private val context: Context) : ILanguageServer {

  companion object {
    const val SERVER_ID = "kotlin"
  }

  private var selectedFile: java.nio.file.Path? = null
  private val backendSpec = KotlinLspBackendFactory.createSpec(context)
  private val processManager: KotlinLspConnection = backendSpec.connection
  private val backendConfigurator: KotlinLspBackendConfigurator = backendSpec.configurator

  private val documentManager = KotlinDocumentManager(processManager) { initialized && processManager.isReady }
  private val requestHandler = KotlinRequestHandler(processManager, documentManager)
  private val eventHandler = KotlinEventHandler(documentManager)

  private var _client: ILanguageClient? = null
  @Volatile private var initialized = false
  private var disabledByPreference = false
  private var workspaceSetup: KotlinWorkspaceSetup? = null

  private val importAnalyzer = KotlinImportAnalyzer()
  private var compilerService: KotlinCompilerService? = null
  private val quickFixHandler by lazy { KotlinImportQuickFix(documentManager, importAnalyzer) }

  private lateinit var formatProvider: KotlinCodeFormatProvider

  private val completionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  private val autoImportHandler by lazy { KotlinCompletionAutoImport(documentManager) }

  private lateinit var javaCompilerBridge: KotlinJavaCompilerBridge

  init {
    if (!LSPPreferences.kotlinLspEnabled) {
      disabledByPreference = true
      KslLogs.info("Kotlin language server is disabled by preference")
    } else {
      if (!org.greenrobot.eventbus.EventBus.getDefault().isRegistered(eventHandler)) {
        org.greenrobot.eventbus.EventBus.getDefault().register(eventHandler)
      }

      processManager.setDiagnosticsCallback { diagnostics ->
        KslLogs.debug(
            "KLS diagnostics forwarded from server: file={} count={} summary={}",
            diagnostics.file,
            diagnostics.diagnostics.size,
            summarizeDiagnosticsForTrace(diagnostics.diagnostics),
        )
        _client?.publishDiagnostics(
            diagnostics.copy(channel = DiagnosticResult.CHANNEL_SERVER)
        )

      }
    }
  }

  override val serverId: String = SERVER_ID
  override val client: ILanguageClient?
    get() = _client

  override fun connectClient(client: ILanguageClient?) {
    this._client = client
    KslLogs.debug("Connected language client: {}", client?.javaClass?.simpleName)
  }

  override fun applySettings(settings: IServerSettings?) {
    KslLogs.debug("Applied settings: {}", settings)
  }

  override fun setupWorkspace(workspace: IWorkspace) {
    if (!LSPPreferences.kotlinLspEnabled) {
      disabledByPreference = true
      initialized = false
      KslLogs.info("Skipping Kotlin language server setup because KLS is disabled by preference")
      return
    }

    formatProvider = KotlinCodeFormatProvider(processManager)
    workspaceSetup = KotlinWorkspaceSetup(context, workspace, backendConfigurator, backendSpec.id)
    initialized = false
    workspaceSetup?.setup(processManager) { success ->
      if (!success || !processManager.isReady) {
        initialized = false
        workspaceSetup?.cleanup()
        KslLogs.error("Kotlin language server backend did not become ready")
        return@setup
      }

      initialized = true
      documentManager.flushPendingOpens()

      if (!EventBus.getDefault().isRegistered(this)) {
        EventBus.getDefault().register(this)
      }
    }

    javaCompilerBridge = KotlinJavaCompilerBridge(workspace)
    requestHandler.setJavaCompilerBridge(javaCompilerBridge)

    // Get compiler service and update import analyzer
    compilerService = findCompilerService(workspace)
    importAnalyzer.updateImportCache(compilerService)
  }

  /** Invalidate cache and trigger reindexing Call this when dependencies change or project syncs */
  fun invalidateCacheAndReindex() {
    KslLogs.info("Invalidating index cache and triggering reindex...")
    workspaceSetup?.getIndexCache()?.clearCache()

  }

  /** Clear all project caches (useful for maintenance) */
  fun clearAllCaches() {
    KslLogs.info("Clearing all KLS caches...")
    workspaceSetup?.getIndexCache()?.clearAllCaches()
  }

  /** Get cache statistics */
  fun getCacheInfo(): String {
    return workspaceSetup?.getIndexCache()?.getCacheStats() ?: "No workspace setup"
  }

  override fun complete(params: CompletionParams?): CompletionResult {
    if (disabledByPreference || !LSPPreferences.kotlinLspEnabled) {
      return CompletionResult(emptyList())
    }

    return if (initialized && processManager.isReady && params != null) {
      // Use async instead of blocking
      runBlocking {
        withTimeout(3000) {
          val result = async(Dispatchers.Default) { requestHandler.complete(params) }
          result.await()
        }
      }
    } else {
      CompletionResult(emptyList())
    }
  }

  override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
    return if (initialized && processManager.isReady && !disabledByPreference && LSPPreferences.kotlinLspEnabled) {
      requestHandler.findReferences(params)
    } else {
      ReferenceResult(emptyList())
    }
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    return if (initialized && processManager.isReady && !disabledByPreference && LSPPreferences.kotlinLspEnabled) {
      requestHandler.findDefinition(params)
    } else {
      DefinitionResult(emptyList())
    }
  }

  override suspend fun hover(params: DefinitionParams): MarkupContent {
    return if (initialized && processManager.isReady && !disabledByPreference && LSPPreferences.kotlinLspEnabled) {
      requestHandler.hover(params)
    } else MarkupContent("", MarkupKind.PLAIN)
  }

  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    return params.selection
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    return if (initialized && processManager.isReady && !disabledByPreference && LSPPreferences.kotlinLspEnabled) {
      requestHandler.signatureHelp(params)
    } else {
      SignatureHelp(emptyList(), 0, 0)
    }
  }

  override suspend fun analyze(file: Path): DiagnosticResult {
    return DiagnosticResult.NO_UPDATE
  }

  private fun findCompilerService(workspace: IWorkspace): KotlinCompilerService? {
    val mainModule =
        workspace
            .getSubProjects()
            .filterIsInstance<com.tom.rv2ide.projects.android.AndroidModule>()
            .firstOrNull { it.isApplication }
            ?: workspace
                .getSubProjects()
                .filterIsInstance<com.tom.rv2ide.projects.android.AndroidModule>()
                .firstOrNull()

    return mainModule?.let { KotlinCompilerProvider.get(it) }
  }

  /**
   * Handles diagnostic click for quick fixes
   *
   * @param file The file containing the diagnostic
   * @param range The range of the diagnostic
   * @return true if quick fix was applied
   */
  fun handleDiagnosticClick(file: Path, range: Range): Boolean {
    return quickFixHandler.applyImportFix(file, range)
  }

  /** Gets available import options for a diagnostic */
  fun getImportOptions(file: Path, range: Range): List<String> {
    return quickFixHandler.getImportOptions(file, range)
  }

  override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
    KslLogs.debug("formatCode called - initialized: {}, selectedFile: {}, params: {}",
        initialized,
        selectedFile,
        params != null,
    )

    if (params == null) {
      KslLogs.warn("Format params is null")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!initialized || !processManager.isReady || disabledByPreference || !LSPPreferences.kotlinLspEnabled) {
      KslLogs.warn("Server not initialized or Kotlin language server is disabled")
      return CodeFormatResult(false, mutableListOf())
    }

    // Get the file to format - from params if available, otherwise use selectedFile
    val fileToFormat = selectedFile
    if (fileToFormat == null) {
      KslLogs.warn("No file selected for formatting")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!(fileToFormat.toString().endsWith(".kt") || fileToFormat.toString().endsWith(".kts"))) {
      KslLogs.debug("Not a Kotlin file: {}", fileToFormat)
      return CodeFormatResult(false, mutableListOf())
    }

    KslLogs.debug("Formatting file: {}", fileToFormat)

    try {
      // Ensure document is opened before formatting
      documentManager.ensureDocumentOpen(fileToFormat)

      // If content is provided in params, sync it first
      if (params.content != null && params.content.toString().isNotEmpty()) {
        val uri = fileToFormat.toUri().toString()
        val currentVersion = documentManager.getDocumentVersion(uri)
        val newVersion = currentVersion + 1
        documentManager.setDocumentVersion(uri, newVersion)
        documentManager.notifyDocumentChange(fileToFormat, params.content.toString(), newVersion)

        // Give server a moment to process the change
        Thread.sleep(100)
      }

      val result = formatProvider.format(fileToFormat, params)

      return result
    } catch (e: Exception) {
      KslLogs.error("Error during format", e)
      return CodeFormatResult(false, mutableListOf())
    }
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    KslLogs.error("LSP failure: type={}, error={}", failure?.type, failure?.error?.message)
    return false
  }

  override fun shutdown() {
    KslLogs.info("Shutting down Kotlin Language Server...")
    completionScope.cancel()
    try {
      org.greenrobot.eventbus.EventBus.getDefault().unregister(eventHandler)
      if (EventBus.getDefault().isRegistered(this)) {
        EventBus.getDefault().unregister(this)
      }
    } catch (e: Exception) {
      KslLogs.warn("Error unregistering from EventBus", e)
    }
    if (!disabledByPreference) {
      processManager.shutdown()
    }
    importAnalyzer.clearCache()
    initialized = false
    KslLogs.info("Kotlin Language Server shutdown complete")
  }
  private fun summarizeDiagnosticsForTrace(diagnostics: List<DiagnosticItem>, limit: Int = 3): String {
    if (diagnostics.isEmpty()) return "[]"
    return diagnostics
        .take(limit)
        .joinToString(prefix = "[", postfix = if (diagnostics.size > limit) ", ...]" else "]") { diagnostic ->
          val code = diagnostic.code.ifBlank { "<no-code>" }
          val source = diagnostic.source.ifBlank { "<no-source>" }
          val message = diagnostic.message.replace("\n", " ").take(80)
          "$code|$source|$message"
        }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onFileSelected(event: DocumentSelectedEvent) {
    if (disabledByPreference || !LSPPreferences.kotlinLspEnabled || !processManager.isReady) {
      return
    }

    KslLogs.debug("=== FILE SELECTED EVENT: {}", event.selectedFile)
    selectedFile = event.selectedFile
    if (
        event.selectedFile.toString().endsWith(".kt") ||
            event.selectedFile.toString().endsWith(".kts")
    ) {
      documentManager.ensureDocumentOpen(event.selectedFile)
    }
  }
}
