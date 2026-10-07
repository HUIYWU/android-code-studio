/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.clang

import android.content.Context
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.models.*
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.preferences.internal.LSPPreferences
import com.tom.rv2ide.projects.IWorkspace
import java.nio.file.Path
import kotlinx.coroutines.*

class ClangLanguageServer(private val context: Context) : ILanguageServer {

  companion object {
    const val SERVER_ID = "clang"
  }

  private val processManager = ClangServerProcessManager(context)
  private val documentManager = ClangDocumentManager(processManager) { initialized }
  private val requestHandler = ClangRequestHandler(processManager, documentManager)
  private val eventHandler = ClangEventHandler(documentManager)
  private var _client: ILanguageClient? = null
  private var initialized = false
  private var disabledByPreference = false
  private var workspaceSetup: ClangWorkspaceSetup? = null


  private val completionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  init {
    if (!LSPPreferences.clangLspEnabled) {
      disabledByPreference = true
      ClangLogs.info("Clang language server is disabled by preference")
    }
    if (!org.greenrobot.eventbus.EventBus.getDefault().isRegistered(eventHandler)) {
      org.greenrobot.eventbus.EventBus.getDefault().register(eventHandler)
    }
    processManager.setNotificationHandler(ClangNotificationHandler(documentManager))
    processManager.setDiagnosticsCallback { diagnostics ->
      CoroutineScope(Dispatchers.Main).launch {
        _client?.publishDiagnostics(diagnostics)
      }
    }
    ClangLogs.debug("ClangLanguageServer initialization complete")
  }


  override val serverId: String = SERVER_ID
  override val client: ILanguageClient?
    get() = _client

  override fun connectClient(client: ILanguageClient?) {
    this._client = client
    ClangLogs.info("Connected language client: {}", client?.javaClass?.simpleName)
  }

  override fun applySettings(settings: IServerSettings?) {
    ClangLogs.debug("Applied settings: {}", settings)
  }
  override fun setupWorkspace(workspace: IWorkspace) {
    if (!LSPPreferences.clangLspEnabled) {
      disabledByPreference = true
      initialized = false
      ClangLogs.info("Skipping Clang language server setup because clang LSP is disabled by preference")
      return
    }

    disabledByPreference = false
    ClangLogs.info("Setting up workspace: {}", workspace.getProjectDir())
    workspaceSetup = ClangWorkspaceSetup(context, workspace)
    val setupOk = workspaceSetup?.setup(processManager) == true
    initialized = setupOk
    if (setupOk) {
      documentManager.flushPendingOpens()
    } else {
      ClangLogs.warn("Skipping pending didOpen flush because clang workspace setup did not complete successfully")
    }
    ClangLogs.info("Workspace setup complete, initialized={}", initialized)
  }

  // MATCH THE KOTLIN IMPLEMENTATION EXACTLY
  override fun complete(params: CompletionParams?): CompletionResult {
    return if (initialized && !disabledByPreference && LSPPreferences.clangLspEnabled && params != null) {
      runBlocking { withTimeout(3000) { requestHandler.complete(params) } }
    } else {
      CompletionResult(emptyList())
    }
  }
  override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
    return if (initialized && !disabledByPreference && LSPPreferences.clangLspEnabled) {
      requestHandler.findReferences(params)
    } else {
      ReferenceResult(emptyList())
    }
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    return if (initialized && !disabledByPreference && LSPPreferences.clangLspEnabled) {
      requestHandler.findDefinition(params)
    } else {
      DefinitionResult(emptyList())
    }
  }



  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    return params.selection
  }
  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    return if (initialized && !disabledByPreference && LSPPreferences.clangLspEnabled) {
      requestHandler.signatureHelp(params)
    } else {
      SignatureHelp(emptyList(), 0, 0)
    }
  }


  override suspend fun analyze(file: Path): DiagnosticResult {
    // Clangd provides diagnostics automatically via textDocument/publishDiagnostics
    return DiagnosticResult.NO_UPDATE
  }

  override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
    return CodeFormatResult(false, mutableListOf())
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    ClangLogs.warn("LSP failure: type={}, error={}", failure?.type, failure?.error?.message)
    return false
  }
  override fun shutdown() {
    ClangLogs.info("Shutting down Clang Language Server...")
    completionScope.cancel()
    try {
      org.greenrobot.eventbus.EventBus.getDefault().unregister(eventHandler)
    } catch (e: Exception) {
      ClangLogs.warn("Error unregistering from EventBus", e)
    }

    documentManager.closeAllDocuments()
    processManager.shutdown()

    initialized = false
    ClangLogs.info("Clang Language Server shutdown complete")
  }

}
