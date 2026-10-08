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
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendContext
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendFactory
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.completion.KotlinJavaCompilerBridge
import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentSync
import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentEventBridge
import com.tom.rv2ide.language.services.kotlin.format.KotlinCodeFormatProvider
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.language.services.kotlin.request.KotlinRequestHandler
import com.tom.rv2ide.language.services.kotlin.workspace.KotlinBackendWorkspaceCoordinator
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
  private val backendSpec = KotlinBackendFactory.createSpec(context)
  private val connection: KotlinBackendConnection = backendSpec.connection

  private val documentSync = KotlinDocumentSync(connection) { backendReady && connection.isReady }
  private val requestHandler = KotlinRequestHandler(connection, documentSync)
  private val documentEventBridge = KotlinDocumentEventBridge(documentSync, connection::supportsDocument)

  private var _client: ILanguageClient? = null
  @Volatile private var backendReady = false
  private var disabledByPreference = false
  private var workspaceCoordinator: KotlinBackendWorkspaceCoordinator? = null
  private var activeWorkspace: IWorkspace? = null


  private lateinit var formatProvider: KotlinCodeFormatProvider


  private lateinit var javaCompilerBridge: KotlinJavaCompilerBridge

  init {
    if (!LSPPreferences.kotlinLspEnabled) {
      disabledByPreference = true
      KlsLogs.info("Kotlin language server is disabled by preference")
    } else {
      if (!org.greenrobot.eventbus.EventBus.getDefault().isRegistered(documentEventBridge)) {
        org.greenrobot.eventbus.EventBus.getDefault().register(documentEventBridge)
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
    if (activeWorkspace === workspace && backendReady && connection.isReady) {
      KlsLogs.info("Kotlin language server workspace setup is already current")
      return
    }

    val replacingWorkspace = activeWorkspace != null && activeWorkspace !== workspace
    activeWorkspace = workspace

    if (!LSPPreferences.kotlinLspEnabled) {
      disabledByPreference = true
      backendReady = false
      KlsLogs.info("Skipping Kotlin language server setup because KLS is disabled by preference")
      return
    }

    workspaceCoordinator?.cleanup()
    workspaceCoordinator = null

    val backendContext = KotlinBackendContext(workspace, KotlinProjectClasspathProvider(workspace))
    val backendConfigurator = backendSpec.createConfigurator(backendContext)
    formatProvider = KotlinCodeFormatProvider(connection, backendConfigurator)
    workspaceCoordinator = KotlinBackendWorkspaceCoordinator(
        workspace,
        backendContext,
        backendConfigurator,
        documentSync::resyncActiveDocuments,
    )
    backendReady = false
    documentSync.clear()
    val currentSetup = workspaceCoordinator ?: return
    val backendStarted =
        if (replacingWorkspace && connection.isReady && connection.isInitialized) {
          backendContext.classpathProvider.invalidateCache()
          connection.refreshEnvironment(backendContext.classpathProvider)
        } else {
          true
        }
    if (!backendStarted) {
      KlsLogs.error("Kotlin backend failed to refresh for the new workspace")
      return
    }
    currentSetup.setup(connection) { success ->
      if (workspaceCoordinator !== currentSetup || activeWorkspace !== workspace) return@setup
      if (!success || !connection.isReady) {
        backendReady = false
        workspaceCoordinator?.cleanup()
        KlsLogs.error("Kotlin language server backend did not become ready")
        return@setup
      }

      backendReady = true
      documentSync.resyncActiveDocuments()

      if (!EventBus.getDefault().isRegistered(this)) {
        EventBus.getDefault().register(this)
      }
    }

    javaCompilerBridge = KotlinJavaCompilerBridge(workspace)
    requestHandler.setJavaCompilerBridge(javaCompilerBridge)

  }

  override fun complete(params: CompletionParams?): CompletionResult {
    if (disabledByPreference || !LSPPreferences.kotlinLspEnabled) {
      return CompletionResult(emptyList())
    }

    return if (backendReady && connection.isReady && params != null && connection.supportsDocument(params.file)) {
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
    return if (backendReady && connection.isReady && !disabledByPreference && LSPPreferences.kotlinLspEnabled && connection.supportsDocument(params.file)) {
      requestHandler.findReferences(params)
    } else {
      ReferenceResult(emptyList())
    }
  }
  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    return if (
        backendReady &&
            connection.isReady &&
            !disabledByPreference &&
            LSPPreferences.kotlinLspEnabled &&
            connection.supportsDocument(params.file)
    ) {
      requestHandler.findDefinition(params)
    } else {
      DefinitionResult(emptyList())
    }
  }

  override suspend fun hover(params: DefinitionParams): MarkupContent {
    return if (
        backendReady &&
            connection.isReady &&
            !disabledByPreference &&
            LSPPreferences.kotlinLspEnabled &&
            connection.supportsDocument(params.file)
    ) {
      requestHandler.hover(params)
    } else MarkupContent("", MarkupKind.PLAIN)
  }


  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    return params.selection
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    return if (
        backendReady &&
            connection.isReady &&
            !disabledByPreference &&
            LSPPreferences.kotlinLspEnabled &&
            connection.supportsDocument(params.file)
    ) {
      requestHandler.signatureHelp(params)
    } else {
      SignatureHelp(emptyList(), 0, 0)
    }
  }

  override suspend fun analyze(file: Path): DiagnosticResult {
    return DiagnosticResult.NO_UPDATE
  }

  override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
    KlsLogs.debug("formatCode called - backendReady: {}, selectedFile: {}, params: {}",
        backendReady,
        selectedFile,
        params != null,
    )

    if (params == null) {
      KlsLogs.warn("Format params is null")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!backendReady || !connection.isReady || disabledByPreference || !LSPPreferences.kotlinLspEnabled) {
      KlsLogs.warn("Server not backendReady or Kotlin language server is disabled")
      return CodeFormatResult(false, mutableListOf())
    }

    // Get the file to format - from params if available, otherwise use selectedFile
    val fileToFormat = selectedFile
    if (fileToFormat == null) {
      KlsLogs.warn("No file selected for formatting")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!connection.supportsDocument(fileToFormat)) {
      KlsLogs.debug("Backend does not support formatting document: {}", fileToFormat)
      return CodeFormatResult(false, mutableListOf())
    }

    KlsLogs.debug("Formatting file: {}", fileToFormat)

    try {
      // Ensure document is opened before formatting
      documentSync.ensureDocumentOpen(fileToFormat)

      // If content is provided in params, sync it first
      if (params.content != null && params.content.toString().isNotEmpty()) {
        val uri = fileToFormat.toUri().toString()
        val currentVersion = documentSync.getDocumentVersion(uri)
        val newVersion = currentVersion + 1
        documentSync.setDocumentVersion(uri, newVersion)
        documentSync.notifyDocumentChange(fileToFormat, params.content.toString(), newVersion)

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
    try {
      org.greenrobot.eventbus.EventBus.getDefault().unregister(documentEventBridge)
      if (EventBus.getDefault().isRegistered(this)) {
        EventBus.getDefault().unregister(this)
      }
    } catch (e: Exception) {
      KlsLogs.warn("Error unregistering from EventBus", e)
    }
    if (!disabledByPreference) {
      connection.close()
    }
    workspaceCoordinator?.cleanup()
    workspaceCoordinator = null
    activeWorkspace = null
    documentSync.clear()
    backendReady = false
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
    if (connection.supportsDocument(event.selectedFile)) {
      documentSync.ensureDocumentOpen(event.selectedFile)
    }
  }
}
