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
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendFactory
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspConnection
import com.tom.rv2ide.language.services.kotlin.compiler.KotlinCompilerProvider
import com.tom.rv2ide.language.services.kotlin.compiler.KotlinCompilerService
import com.tom.rv2ide.language.services.kotlin.completion.KotlinJavaCompilerBridge
import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentManager
import com.tom.rv2ide.language.services.kotlin.document.KotlinEventHandler
import com.tom.rv2ide.language.services.kotlin.format.KotlinCodeFormatProvider
import com.tom.rv2ide.language.services.kotlin.imports.KotlinImportAnalyzer
import com.tom.rv2ide.language.services.kotlin.imports.KotlinImportQuickFix
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.language.services.kotlin.request.KotlinRequestHandler
import com.tom.rv2ide.language.services.kotlin.workspace.KotlinWorkspaceSetup
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
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
  private val connection: KotlinLspConnection = backendSpec.connection
  private val backendConfigurator: KotlinLspBackendConfigurator = backendSpec.configurator

  private val documentManager = KotlinDocumentManager(connection) { initialized && connection.isReady }
  private val requestHandler = KotlinRequestHandler(connection, documentManager)
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

  private lateinit var javaCompilerBridge: KotlinJavaCompilerBridge

  init {
    if (!LSPPreferences.kotlinLspEnabled) {
      disabledByPreference = true
      KlsLogs.info("Kotlin language server is disabled by preference")
    } else {
      if (!org.greenrobot.eventbus.EventBus.getDefault().isRegistered(eventHandler)) {
        org.greenrobot.eventbus.EventBus.getDefault().register(eventHandler)
      }

      connection.setDiagnosticsCallback { diagnostics ->
        KlsLogs.debug(
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
    KlsLogs.debug("Connected language client: {}", client?.javaClass?.simpleName)
  }

  override fun applySettings(settings: IServerSettings?) {
    KlsLogs.debug("Applied settings: {}", settings)
  }

  override fun setupWorkspace(workspace: IWorkspace) {
    if (!LSPPreferences.kotlinLspEnabled) {
      disabledByPreference = true
      initialized = false
      KlsLogs.info("Skipping Kotlin language server setup because KLS is disabled by preference")
      return
    }

    formatProvider = KotlinCodeFormatProvider(connection)
    workspaceSetup = KotlinWorkspaceSetup(context, workspace, backendConfigurator, backendSpec.id)
    initialized = false
    workspaceSetup?.setup(connection) { success ->
      if (!success || !connection.isReady) {
        initialized = false
        workspaceSetup?.cleanup()
        KlsLogs.error("Kotlin language server backend did not become ready")
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

  override fun complete(params: CompletionParams?): CompletionResult {
    if (disabledByPreference || !LSPPreferences.kotlinLspEnabled) {
      return CompletionResult(emptyList())
    }

    return if (initialized && connection.isReady && params != null) {
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
    return if (initialized && connection.isReady && !disabledByPreference && LSPPreferences.kotlinLspEnabled) {
      requestHandler.findReferences(params)
    } else {
      ReferenceResult(emptyList())
    }
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    return if (initialized && connection.isReady && !disabledByPreference && LSPPreferences.kotlinLspEnabled) {
      requestHandler.findDefinition(params)
    } else {
      DefinitionResult(emptyList())
    }
  }

  override suspend fun hover(params: DefinitionParams): MarkupContent {
    return if (initialized && connection.isReady && !disabledByPreference && LSPPreferences.kotlinLspEnabled) {
      requestHandler.hover(params)
    } else MarkupContent("", MarkupKind.PLAIN)
  }

  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    return params.selection
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    return if (initialized && connection.isReady && !disabledByPreference && LSPPreferences.kotlinLspEnabled) {
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
    KlsLogs.debug("formatCode called - initialized: {}, selectedFile: {}, params: {}",
        initialized,
        selectedFile,
        params != null,
    )

    if (params == null) {
      KlsLogs.warn("Format params is null")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!initialized || !connection.isReady || disabledByPreference || !LSPPreferences.kotlinLspEnabled) {
      KlsLogs.warn("Server not initialized or Kotlin language server is disabled")
      return CodeFormatResult(false, mutableListOf())
    }

    // Get the file to format - from params if available, otherwise use selectedFile
    val fileToFormat = selectedFile
    if (fileToFormat == null) {
      KlsLogs.warn("No file selected for formatting")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!(fileToFormat.toString().endsWith(".kt") || fileToFormat.toString().endsWith(".kts"))) {
      KlsLogs.debug("Not a Kotlin file: {}", fileToFormat)
      return CodeFormatResult(false, mutableListOf())
    }

    KlsLogs.debug("Formatting file: {}", fileToFormat)

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
      KlsLogs.error("Error during format", e)
      return CodeFormatResult(false, mutableListOf())
    }
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    KlsLogs.error("LSP failure: type={}, error={}", failure?.type, failure?.error?.message)
    return false
  }

  override fun shutdown() {
    KlsLogs.info("Shutting down Kotlin Language Server...")
    completionScope.cancel()
    try {
      org.greenrobot.eventbus.EventBus.getDefault().unregister(eventHandler)
      if (EventBus.getDefault().isRegistered(this)) {
        EventBus.getDefault().unregister(this)
      }
    } catch (e: Exception) {
      KlsLogs.warn("Error unregistering from EventBus", e)
    }
    if (!disabledByPreference) {
      connection.shutdown()
    }
    importAnalyzer.clearCache()
    initialized = false
    KlsLogs.info("Kotlin Language Server shutdown complete")
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
    if (disabledByPreference || !LSPPreferences.kotlinLspEnabled || !connection.isReady) {
      return
    }

    KlsLogs.debug("=== FILE SELECTED EVENT: {}", event.selectedFile)
    selectedFile = event.selectedFile
    if (
        event.selectedFile.toString().endsWith(".kt") ||
            event.selectedFile.toString().endsWith(".kts")
    ) {
      documentManager.ensureDocumentOpen(event.selectedFile)
    }
  }
}
