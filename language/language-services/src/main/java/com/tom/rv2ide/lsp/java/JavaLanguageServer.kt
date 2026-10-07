/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java

import com.tom.rv2ide.common.logging.IdeLogConfig
import androidx.annotation.RestrictTo
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent
import com.tom.rv2ide.eventbus.events.file.FileCreationEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.javac.services.fs.CacheFSInfoSingleton
import com.tom.rv2ide.javac.services.fs.CachingJarFileSystemProvider.clearCache
import com.tom.rv2ide.javac.services.fs.CachingJarFileSystemProvider.clearCachesForPaths
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.internal.model.CachedCompletion
import com.tom.rv2ide.lsp.java.actions.JavaCodeActionsMenu
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.java.compiler.SourceFileManager
import com.tom.rv2ide.lsp.java.kotlin.KotlinClassOutputProvider
import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmTypeIndex
import com.tom.rv2ide.lsp.java.models.JavaServerSettings
import com.tom.rv2ide.lsp.java.providers.CodeFormatProvider
import com.tom.rv2ide.lsp.java.providers.CompletionProvider
import com.tom.rv2ide.lsp.java.providers.DefinitionProvider
import com.tom.rv2ide.lsp.java.providers.JavaDiagnosticProvider
import com.tom.rv2ide.lsp.java.providers.JavaHoverProvider
import com.tom.rv2ide.lsp.java.providers.JavaSelectionProvider
import com.tom.rv2ide.lsp.java.providers.ReferenceProvider
import com.tom.rv2ide.lsp.java.providers.SignatureProvider
import com.tom.rv2ide.lsp.java.providers.snippet.JavaSnippetRepository.init
import com.tom.rv2ide.lsp.java.utils.AnalyzeTimer
import com.tom.rv2ide.progress.CompletionCancellation
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.lsp.java.utils.CancelChecker.Companion.isCancelled
import com.tom.rv2ide.lsp.models.CodeFormatResult
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.FailureType
import com.tom.rv2ide.lsp.models.FormatCodeParams
import com.tom.rv2ide.lsp.models.LSPFailure
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.lsp.util.LSPEditorActions
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.FileManager.getActiveDocumentCount
import com.tom.rv2ide.projects.IProjectManager.Companion.getInstance
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.events.LazyModuleActivatedEvent
import com.tom.rv2ide.projects.events.LazyModuleEvictedEvent
import com.tom.rv2ide.utils.DocumentUtils
import com.tom.rv2ide.utils.VMUtils
import java.nio.file.Path
import java.util.Objects
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

class JavaLanguageServer : ILanguageServer {

  private val diagnosticProvider: JavaDiagnosticProvider?
  private var lastHeavyModuleCleanupAt: Long = 0L
  override var client: ILanguageClient? = null
    private set

  private var _settings: IServerSettings? = null
  private var selectedFile: Path? = null
  private val timer = AnalyzeTimer { analyzeSelected() }
  private val analyzeGeneration = AtomicLong(0)
  private val analyzeLaunchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
  private val analyzeRerunRequested = java.util.concurrent.atomic.AtomicBoolean(false)
  private val lastInteractiveRequestAt = AtomicLong(0)

  /**
   * Instrumentation-only seam for the scheduling boundary.
   *
   * Production leaves this null and follows the real analyze/provider-yield path below. It lets the
   * Android test exercise Handler debounce, retry scheduling, and main-thread publication without
   * coupling those timing assertions to Tooling/javac setup, which is covered separately on JVM.
   */
  @RestrictTo(RestrictTo.Scope.TESTS)
  internal var scheduledDiagnosticAttemptOverride: (suspend (Path) -> ScheduledDiagnosticAttempt)? = null
  private val cachedCompletion = AtomicReference(CachedCompletion.EMPTY)
  private var lastJavaChangeDelta = 0

  val settings: IServerSettings
    get() {
      return _settings ?: JavaServerSettings.getInstance().also { _settings = it }
    }

  override val serverId: String = SERVER_ID

  companion object {

    const val SERVER_ID = "ide.lsp.java"
    private const val LARGE_CHANGE_DELTA_FOR_DIAGNOSTIC_DEBOUNCE = 2_000
    private const val LARGE_CHANGE_ANALYZE_INTERVAL_MS = 1_500L
    private const val SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS = 1_200L
    private const val HEAVY_COMPOSITE_IDLE_EVICTION_MS = 5 * 60_000L
    private val log = LoggerFactory.getLogger(JavaLanguageServer::class.java)
  }

  init {
    diagnosticProvider = JavaDiagnosticProvider()

    applySettings(JavaServerSettings.getInstance())

    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }

    init()
  }

  override fun shutdown() {
    JavaSemanticSessions.destroyAll()
    SourceFileManager.clearCache()
    KotlinJvmTypeIndex.clear()
    KotlinClassOutputProvider.clearCache()
    CacheFSInfoSingleton.clearCache()
    clearCache()
    EventBus.getDefault().unregister(this)
    timer.cancel()
  }

  override fun connectClient(client: ILanguageClient?) {
    this.client = client
  }

  override fun applySettings(settings: IServerSettings?) {
    this._settings = settings
  }

  override fun setupWorkspace(workspace: IWorkspace) {
    LSPEditorActions.ensureActionsMenuRegistered(JavaCodeActionsMenu)

    // Once we have workspace initialized
    // Destory the NO_MODULE_COMPILER instance
    JavaCompilerService.NO_MODULE_COMPILER.destroy()

    // Clear cached file managers and Kotlin source/class-output symbol snapshots.
    SourceFileManager.clearCache()
    KotlinJvmTypeIndex.clear()
    KotlinClassOutputProvider.clearCache()

    // Clear cached JAR file system for R.jar
    // Using the cached instance will result in completions not being updated for updated resources
    // TODO Clearing caches for JAR files ending with '/R.jar' is probably not a good idea
    //    Maybe this could be improved by using data from the AndroidModule workspace model
    clearCachesForPaths { path: String -> path.endsWith("/R.jar") }

    // Clear module semantic sessions and their cached reusable compilers. Completion candidates are
    // classpath-sensitive as well, so no cached result may cross a workspace environment reset.
    JavaSemanticSessions.destroyAll()
    cachedCompletion.set(CachedCompletion.EMPTY)

    // Cache classpath locations for eagerly active modules only.
    for (subModule in workspace.getSubProjects()) {
      if (subModule !is ModuleProject || subModule.path == workspace.getRootProject().path) {
        continue
      }
      if (subModule.isLazyCompositeBuildModule()) {
        log.info("Skipping SourceFileManager warm-up for lazy composite module: {}", subModule.path)
        continue
      }
      SourceFileManager.forModule(subModule)
    }
    startOrRestartAnalyzeTimer()
  }
  override fun complete(params: CompletionParams?): CompletionResult {
    lastInteractiveRequestAt.set(System.currentTimeMillis())
    val request = params ?: return CompletionResult.EMPTY
    if (!isCurrentDocument(request.file, request.documentVersion, request.documentRevision)) {
      log.warn(
          "Completion dropped before execution because document is stale file={} requestVersion={} requestRevision={} activeDocument={}",
          request.file,
          request.documentVersion,
          request.documentRevision,
          describeActiveDocument(request.file),
      )
      return CompletionResult.EMPTY
    }
    if (!settings.completionsEnabled() || !DocumentUtils.isJavaFile(request.file)) {
      log.warn(
          "Completion disabled or unsupported file={} completionsEnabled={} canComplete={} version={} revision={}",
          request.file,
          settings.completionsEnabled(),
          DocumentUtils.isJavaFile(request.file),
          request.documentVersion,
          request.documentRevision,
      )
      return CompletionResult.EMPTY
    }

    val semanticSession = getSemanticSession(request.file)
    // No module session means there is no stable environment generation to put in a share key.
    // Preserve the old synchronous NO_MODULE_COMPILER behavior for that exceptional path.
    if (semanticSession == null) {
      return executeCompletion(request, null, null)
    }

    val environmentGeneration = semanticSession.environmentGeneration
    val key =
        JavaSemanticSession.CompletionRequestKey.create(
            request.file,
            request.documentVersion,
            request.documentRevision,
            environmentGeneration,
            request.position.requireIndex().toLong(),
            request.prefix,
        )
    val (state, created) = semanticSession.acquireInFlightCompletion(key)
    val subscriberLease = state.attachSubscriber()
    if (IdeLogConfig.shouldLogInfo()) {
      log.info(
          "Completion in-flight {} file={} version={} revision={} environmentGeneration={} cursor={} prefixLength={} subscribers={}",
          if (created) "leader" else "follower",
          key.file,
          key.documentVersion,
          key.documentRevision,
          key.environmentGeneration,
          key.cursorIndex,
          key.prefix.length,
          state.subscriberCount,
      )
    }
    val deferred = CompletionResult()
    deferred.deferredResult = state.result
    deferred.deferredDetach = { subscriberLease.close() }

    if (created) {
      val workerRequest = copyCompletionRequest(request, state.workerCancelChecker)
      CoroutineScope(Dispatchers.Default).launch {
        val startedAt = System.currentTimeMillis()
        val previousChecker = CompletionCancellation.install(state.workerCancelChecker)
        try {
          state.workerCancelChecker.abortIfCancelled()
          val result = executeCompletion(workerRequest, semanticSession, environmentGeneration)
          state.complete(result)
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "Completion worker terminal=completed file={} version={} revision={} environmentGeneration={} cursor={} itemCount={} durationMs={}",
                key.file,
                key.documentVersion,
                key.documentRevision,
                key.environmentGeneration,
                key.cursorIndex,
                result.items.size,
                System.currentTimeMillis() - startedAt,
            )
          }
        } catch (error: Throwable) {
          state.fail(error)
          if (IdeLogConfig.shouldLogInfo()) {
            // Subscriber-driven cancellation is an expected terminal state during rapid edits,
            // not a provider/compiler failure. Keep it distinct for pressure-test metrics.
            log.info(
                "Completion worker terminal={} file={} version={} revision={} environmentGeneration={} cursor={} errorType={} durationMs={}",
                if (isCancelled(error)) "cancelled" else "failed",
                key.file,
                key.documentVersion,
                key.documentRevision,
                key.environmentGeneration,
                key.cursorIndex,
                error.javaClass.simpleName,
                System.currentTimeMillis() - startedAt,
            )
          }
        } finally {
          CompletionCancellation.restore(previousChecker)
          semanticSession.removeInFlightCompletion(state)
        }
      }
    }
    return deferred
  }

  /** Runs only on the independent completion worker (or the legacy no-module fallback path). */
  private fun executeCompletion(
      request: CompletionParams,
      semanticSession: JavaSemanticSession?,
      environmentGeneration: Long?,
  ): CompletionResult {
    val interactiveLease = semanticSession?.beginInteractiveRequest()
    try {
      if (diagnosticProvider!!.isAnalyzing()) {
        diagnosticProvider.cancel()
      }
      val compiler = semanticSession?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
      val completionProvider =
          CompletionProvider(compiler, settings, cachedCompletion.get()) { completion ->
            updateCachedCompletion(completion, request, semanticSession, environmentGeneration)
          }
      val result = completionProvider.complete(request)
      val currentDocument =
          isCurrentDocument(request.file, request.documentVersion, request.documentRevision)
      val currentSession = isCurrentSemanticSession(semanticSession, environmentGeneration)
      if (!currentDocument || !currentSession) {
        log.warn(
            "Completion dropped after execution file={} version={} revision={} environmentGeneration={} documentCurrent={} sessionCurrent={} itemCount={} cached={}",
            request.file,
            request.documentVersion,
            request.documentRevision,
            environmentGeneration,
            currentDocument,
            currentSession,
            result.items.size,
            result.isCached,
        )
        return CompletionResult.EMPTY
      }
      if (result.items.isEmpty()) {
        log.warn(
            "Completion produced no items file={} cursor={} prefix={} version={} revision={} environmentGeneration={} cached={}",
            request.file,
            request.position.requireIndex(),
            request.prefix,
            request.documentVersion,
            request.documentRevision,
            environmentGeneration,
            result.isCached,
        )
      }
      return result
    } finally {
      interactiveLease?.close()
    }
  }

  /** Copies all mutable request fields before the UI CompletionThread returns. */
  private fun copyCompletionRequest(
      source: CompletionParams,
      cancelChecker: com.tom.rv2ide.progress.ICancelChecker,
  ): CompletionParams {
    return CompletionParams(
            com.tom.rv2ide.models.Position(
                source.position.line,
                source.position.column,
                source.position.requireIndex(),
            ),
            source.file,
            cancelChecker,
        )
        .also {
          it.content = source.content?.toString()
          it.prefix = source.prefix
          it.documentVersion = source.documentVersion
          it.documentRevision = source.documentRevision
        }
  }

  override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
    if (!settings.referencesEnabled()) {
      return ReferenceResult(emptyList())
    }
    val semanticSession = getSemanticSession(params.file)
    val foregroundLease = semanticSession?.beginUserActionRequest()
    try {
      val compiler = semanticSession?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
      return ReferenceProvider(compiler, params.cancelChecker).findReferences(params)
    } finally {
      foregroundLease?.close()
    }
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    if (!settings.definitionsEnabled()) {
      return DefinitionResult(emptyList())
    }
    val semanticSession = getSemanticSession(params.file)
    val foregroundLease = semanticSession?.beginUserActionRequest()
    try {
      val compiler = semanticSession?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
      return DefinitionProvider(compiler, settings, params.cancelChecker).findDefinition(params)
    } finally {
      foregroundLease?.close()
    }
  }

  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    if (!settings.smartSelectionsEnabled()) {
      return params.selection
    }
    val semanticSession = getSemanticSession(params.file)
    val foregroundLease = semanticSession?.beginUserActionRequest()
    try {
      val compiler = semanticSession?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
      return JavaSelectionProvider(compiler).expandSelection(params)
    } finally {
      foregroundLease?.close()
    }
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    lastInteractiveRequestAt.set(System.currentTimeMillis())
    if (
        !settings.signatureHelpEnabled() ||
            !isCurrentDocument(params.file, params.documentVersion, params.documentRevision)
    ) {
      return SignatureHelp(emptyList(), -1, -1)
    }
    val semanticSession = getSemanticSession(params.file)
    // Preserve legacy behavior where no module session/environment identity is available. Unknown
    // document stamps are likewise kept on the non-shared compatibility path: a strict key must
    // never merge requests whose source snapshot cannot be proven identical.
    if (
        semanticSession == null ||
            params.documentVersion < 0 ||
            params.documentRevision < 0L ||
            params.position.requireIndex() < 0
    ) {
      return executeSignatureHelp(params, semanticSession, semanticSession?.environmentGeneration)
    }

    val environmentGeneration = semanticSession.environmentGeneration
    val key =
        JavaSemanticSession.SignatureRequestKey.create(
            params.file,
            params.documentVersion,
            params.documentRevision,
            environmentGeneration,
            params.position.requireIndex().toLong(),
        )
    val (state, created) = semanticSession.acquireInFlightSignatureHelp(key)
    val subscriberLease = state.attachSubscriber()
    if (IdeLogConfig.shouldLogInfo()) {
      log.info(
          "Signature Help in-flight {} file={} version={} revision={} environmentGeneration={} cursor={} subscribers={}",
          if (created) "leader" else "follower",
          key.file,
          key.documentVersion,
          key.documentRevision,
          key.environmentGeneration,
          key.cursorIndex,
          state.subscriberCount,
      )
    }
    if (created) {
      val workerParams = copySignatureHelpParams(params, state.workerCancelChecker)
      CoroutineScope(Dispatchers.Default).launch {
        val startedAt = System.currentTimeMillis()
        try {
          state.workerCancelChecker.abortIfCancelled()
          val result = executeSignatureHelp(workerParams, semanticSession, environmentGeneration)
          state.complete(result)
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "Signature Help worker terminal=completed file={} version={} revision={} environmentGeneration={} cursor={} signatureCount={} durationMs={}",
                key.file,
                key.documentVersion,
                key.documentRevision,
                key.environmentGeneration,
                key.cursorIndex,
                result.signatures.size,
                System.currentTimeMillis() - startedAt,
            )
          }
        } catch (error: Throwable) {
          state.fail(error)
          if (IdeLogConfig.shouldLogInfo()) {
            // Keep expected coroutine/subscriber cancellation separate from a semantic failure.
            log.info(
                "Signature Help worker terminal={} file={} version={} revision={} environmentGeneration={} cursor={} errorType={} durationMs={}",
                if (isCancelled(error)) "cancelled" else "failed",
                key.file,
                key.documentVersion,
                key.documentRevision,
                key.environmentGeneration,
                key.cursorIndex,
                error.javaClass.simpleName,
                System.currentTimeMillis() - startedAt,
            )
          }
        } finally {
          semanticSession.removeInFlightSignatureHelp(state)
        }
      }
    }
    return awaitSignatureHelp(state.result, subscriberLease)
  }

  private suspend fun awaitSignatureHelp(
      result: java.util.concurrent.CompletableFuture<SignatureHelp>,
      subscriberLease: AutoCloseable,
  ): SignatureHelp =
      suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { subscriberLease.close() }
        result.whenComplete { value, error ->
          try {
            if (error == null && value != null) {
              continuation.resume(value)
            } else if (error != null) {
              continuation.resumeWithException(error)
            } else {
              continuation.resume(SignatureHelp(emptyList(), -1, -1))
            }
          } finally {
            subscriberLease.close()
          }
        }
      }

  private fun executeSignatureHelp(
      params: SignatureHelpParams,
      semanticSession: JavaSemanticSession?,
      environmentGeneration: Long?,
  ): SignatureHelp {
    val interactiveLease = semanticSession?.beginInteractiveRequest()
    try {
      val compiler = semanticSession?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
      val result = SignatureProvider(compiler, params.cancelChecker).signatureHelp(params)
      return if (
          isCurrentDocument(params.file, params.documentVersion, params.documentRevision) &&
              isCurrentSemanticSession(semanticSession, environmentGeneration)
      ) {
        result
      } else {
        SignatureHelp(emptyList(), -1, -1)
      }
    } finally {
      interactiveLease?.close()
    }
  }

  private fun copySignatureHelpParams(
      source: SignatureHelpParams,
      cancelChecker: com.tom.rv2ide.progress.ICancelChecker,
  ): SignatureHelpParams {
    return SignatureHelpParams(
            source.file,
            com.tom.rv2ide.models.Position(
                source.position.line,
                source.position.column,
                source.position.requireIndex(),
            ),
            source.content?.toString(),
            cancelChecker,
        )
        .also {
          it.documentVersion = source.documentVersion
          it.documentRevision = source.documentRevision
        }
  }

  private fun isCurrentDocument(file: Path, version: Int, revision: Long): Boolean {
    val snapshot = FileManager.getActiveDocumentSnapshot(file)
        ?: return version < 0 && revision < 0L
    if (version >= 0 && snapshot.version != version) {
      return false
    }
    return revision < 0L || snapshot.revision == revision
  }

  /** Avoid logging active source text when diagnosing request/version ordering races. */
  private fun describeActiveDocument(file: Path): String {
    val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return "none"
    return "version=${snapshot.version}, revision=${snapshot.revision}, length=${snapshot.content.length}"
  }

  override suspend fun hover(params: DefinitionParams): com.tom.rv2ide.lsp.models.MarkupContent {
    val semanticSession = getSemanticSession(params.file)
    val foregroundLease = semanticSession?.beginUserActionRequest()
    try {
      val compiler = semanticSession?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
      return JavaHoverProvider(compiler, params.cancelChecker).hover(params)
    } finally {
      foregroundLease?.close()
    }
  }

  override suspend fun analyze(file: Path): DiagnosticResult {
    if (!settings.diagnosticsEnabled() || !DocumentUtils.isJavaFile(file)) {
      return DiagnosticResult.NO_UPDATE
    }

    if (!settings.codeAnalysisEnabled()) {
      return DiagnosticResult.NO_UPDATE
    }

    val workspace = getInstance().getWorkspace() ?: return DiagnosticResult.NO_UPDATE
    val module = workspace.findModuleForFile(file, false) ?: return DiagnosticResult.NO_UPDATE
    if (module.isHeavyCompositeBuildModule() && !module.hasBeenIndexed()) {
      workspace.ensureModuleActivated(module)
      log.info("Deferring analysis until heavy composite module activation completes: module={} file={}", module.path, file)
      return DiagnosticResult.NO_UPDATE
    }

    return diagnosticProvider!!.analyze(file)
  }

  override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
    return CodeFormatProvider(settings).format(params)
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    return when (failure!!.type) {
      FailureType.COMPLETION -> {
        if (isCancelled(failure.error)) {
          return true
        }
        JavaSemanticSessions.destroyAll()
        true
      }
    }
  }

  private fun getSemanticSession(file: Path?): JavaSemanticSession? {
    if (!DocumentUtils.isJavaFile(file)) {
      return null
    }
    val workspace = getInstance().getWorkspace() ?: return null
    val module = workspace.findModuleForFile(file!!) ?: return null
    workspace.ensureModuleActivated(module)
    return JavaSemanticSessions.forModule(module)
  }

  private fun isCurrentSemanticSession(
      session: JavaSemanticSession?,
      environmentGeneration: Long?,
  ): Boolean {
    return session == null ||
        (environmentGeneration != null && JavaSemanticSessions.isCurrent(session, environmentGeneration))
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun getCompiler(file: Path?): JavaCompilerService {
    return getSemanticSession(file)?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
  }

  private fun updateCachedCompletion(
      completion: CachedCompletion,
      request: CompletionParams,
      session: JavaSemanticSession?,
      environmentGeneration: Long?,
  ) {
    Objects.requireNonNull(completion)
    // CompletionProvider invokes this callback before complete() returns. Recheck request identity
    // here so a slower stale request cannot overwrite the cache subsequently used by the newest
    // editor version.
    if (
        !isCurrentDocument(request.file, request.documentVersion, request.documentRevision) ||
            !isCurrentSemanticSession(session, environmentGeneration)
    ) {
      log.info(
          "Completion cache write skipped for stale request file={} version={} revision={} environmentGeneration={}",
          request.file,
          request.documentVersion,
          request.documentRevision,
          environmentGeneration,
      )
      return
    }
    cachedCompletion.set(completion)
  }

  private fun maybeEvictIdleHeavyCompositeModules() {
    val now = System.currentTimeMillis()
    if (now - lastHeavyModuleCleanupAt < 60_000L) {
      return
    }
    lastHeavyModuleCleanupAt = now
    val workspace = getInstance().getWorkspace() ?: return
    workspace.getSubProjects()
      .filterIsInstance<ModuleProject>()
      .filter { it.isHeavyCompositeBuildModule() }
      .forEach { module ->
        val evicted = module.evictIfIdle(HEAVY_COMPOSITE_IDLE_EVICTION_MS)
        if (evicted) {
          log.info("Idle heavy composite module eviction observed by JavaLanguageServer: {}", module.path)
        }
      }
  }

  private fun shouldForceAnalyzeTimerRestart(): Boolean {
    val sinceInteractive = System.currentTimeMillis() - lastInteractiveRequestAt.get()
    return sinceInteractive < 0 || sinceInteractive >= SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS
  }

  private fun startOrRestartAnalyzeTimer(forceRestart: Boolean = true) {
    if (VMUtils.isJvm()) {
      return
    }
    val nextGeneration = analyzeGeneration.incrementAndGet()
    val baseInterval =
        if (abs(lastJavaChangeDelta) >= LARGE_CHANGE_DELTA_FOR_DIAGNOSTIC_DEBOUNCE) {
          LARGE_CHANGE_ANALYZE_INTERVAL_MS
        } else {
          AnalyzeTimer.DEFAULT_INTERVAL
        }
    val now = System.currentTimeMillis()
    val sinceInteractive = now - lastInteractiveRequestAt.get()
    val interactiveDelay =
        when {
          sinceInteractive < 0 -> 0L
          sinceInteractive < SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS ->
              SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS - sinceInteractive
          else -> 0L
        }
    val schedule =
        AnalyzeScheduleDecision.decide(
            timerStarted = timer.isStarted,
            currentIntervalMs = timer.interval,
            baseIntervalMs = baseInterval.toLong(),
            interactiveDelayMs = interactiveDelay,
            forceRestart = forceRestart,
        )
    if (timer.interval != schedule.intervalMs) {
      timer.interval = schedule.intervalMs
    }
    when (schedule.action) {
      AnalyzeScheduleDecision.Action.START -> timer.start()
      AnalyzeScheduleDecision.Action.RESTART -> timer.restart()
      AnalyzeScheduleDecision.Action.KEEP -> Unit
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onContentChange(event: DocumentChangeEvent) {
    if (DocumentUtils.isKotlinFile(event.changedFile)) {
      invalidateKotlinAbi(event.changedFile, clearFileManagers = false)
      return
    }
    if (!DocumentUtils.isJavaFile(event.changedFile)) {
      return
    }
    lastJavaChangeDelta = event.changeDelta

    // EventBus delivery is asynchronous. Cancel only states whose event revision is provably
    // older than this change; a delayed older event must not kill a newer completion worker.
    // This does not need module resolution, which may temporarily fail during close/rename flows.
    JavaSemanticSessions.cancelInFlightCompletionsOlderThan(event.changedFile, event.revision)
    startOrRestartAnalyzeTimer(forceRestart = shouldForceAnalyzeTimerRestart())
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileSelected(event: DocumentSelectedEvent) {
    selectedFile = event.selectedFile
    // Kotlin source changes can occur while its editor is selected. Once the user returns to an
    // open Java file, force a full diagnostics pass so stale Kotlin ABI/classpath state is never
    // retained merely because no Java document change was made.
    if (DocumentUtils.isJavaFile(event.selectedFile)) {
      lastJavaChangeDelta = 0
      startOrRestartAnalyzeTimer(forceRestart = true)
    }
  }
  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileOpened(event: DocumentOpenEvent) {
    selectedFile = event.openedFile
    lastJavaChangeDelta = 0

    // Some editor flows intentionally omit the eager text payload for very large files to avoid an
    // additional full-buffer String allocation during open. Recover the initial snapshot from the
    // active document cache when that happens so Java analysis still sees the correct content.
    if (event.text.isBlank()) {
      event.text = com.tom.rv2ide.projects.FileManager.getDocumentContents(event.openedFile)
    }

    startOrRestartAnalyzeTimer(forceRestart = shouldForceAnalyzeTimerRestart())
  }


  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileClosed(event: DocumentCloseEvent) {
    JavaSemanticSessions.cancelInFlightCompletionsForFile(event.closedFile)
    diagnosticProvider?.clearTimestamp(event.closedFile)

    if (getActiveDocumentCount() == 0) {
      selectedFile = null
      timer.cancel()
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onLazyModuleActivated(event: LazyModuleActivatedEvent) {
    val fileToAnalyze = selectedFile ?: return
    if (!DocumentUtils.isJavaFile(fileToAnalyze)) {
      return
    }
    if (!event.module.isFromThisModule(fileToAnalyze)) {
      return
    }
    log.info("Re-analyzing selected file after lazy module activation: module={} file={}", event.module.path, fileToAnalyze)
    analyzeSelected()
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onKotlinFileCreated(event: FileCreationEvent) {
    if (DocumentUtils.isKotlinFile(event.file.toPath())) {
      invalidateKotlinAbi(event.file.toPath(), clearFileManagers = false)
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onKotlinFileDeleted(event: FileDeletionEvent) {
    if (event.file.extension == "kt" || event.file.extension == "kts") {
      invalidateKotlinAbi(event.file.toPath(), clearFileManagers = false)
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onKotlinFileRenamed(event: FileRenameEvent) {
    if (event.file.extension == "kt" || event.file.extension == "kts"
        || DocumentUtils.isKotlinFile(event.newFile.toPath())) {
      invalidateKotlinAbi(event.file.toPath(), clearFileManagers = false)
      invalidateKotlinAbi(event.newFile.toPath(), clearFileManagers = false)
    }
  }

  private fun invalidateKotlinAbi(kotlinFile: Path, clearFileManagers: Boolean) {
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) {
      return
    }
    val workspace = getInstance().getWorkspace()
    val module = workspace?.findModuleForFile(kotlinFile.toFile(), false)
        ?: workspace?.findModuleForFile(kotlinFile.toFile(), true)
    if (module != null) {
      KotlinJvmTypeIndex.invalidate(module)
      JavaSemanticSessions.destroy(module)
    } else {
      // A deletion is commonly delivered after its file can no longer be resolved to a module.
      // Prefer a conservative global reset over retaining an ABI stub for a deleted Kotlin type.
      KotlinJvmTypeIndex.clear()
      JavaSemanticSessions.destroyAll()
    }
    KotlinClassOutputProvider.clearCache()
    cachedCompletion.set(CachedCompletion.EMPTY)
    if (clearFileManagers) {
      SourceFileManager.clearCache()
    }
    val currentJavaFile = selectedFile
    val schedulesJavaDiagnostics = currentJavaFile != null
        && DocumentUtils.isJavaFile(currentJavaFile)
        && (module == null || module.isFromThisModule(currentJavaFile))
    if (schedulesJavaDiagnostics) {
      lastJavaChangeDelta = 0
      startOrRestartAnalyzeTimer(forceRestart = true)
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onLazyModuleEvicted(event: LazyModuleEvictedEvent) {
    JavaSemanticSessions.destroy(event.module)
    // Cached completion candidates may depend on the evicted module's classpath/type index.
    cachedCompletion.set(CachedCompletion.EMPTY)
  }

  private fun analyzeSelected() {
    val fileToAnalyze = selectedFile
    if (fileToAnalyze == null || client == null) {
      return
    }
    val now = System.currentTimeMillis()
    val sinceInteractive = now - lastInteractiveRequestAt.get()
    if (sinceInteractive in 0 until SIGNATURE_HELP_DIAGNOSTIC_GRACE_MS) {
      startOrRestartAnalyzeTimer(forceRestart = false)
      return
    }
    val requestedGeneration = analyzeGeneration.get()
    if (!analyzeLaunchInFlight.compareAndSet(false, true)) {
      analyzeRerunRequested.set(true)
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
          "Analyze coalesced while in flight requestedGeneration={} currentGeneration={} file={}",
          requestedGeneration,
          analyzeGeneration.get(),
          fileToAnalyze,
        )
      }
      return
    }
    CoroutineScope(Dispatchers.Default).launch {
      try {
        if (requestedGeneration != analyzeGeneration.get()) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
              "Analyze skipped before start due to newer request requestedGeneration={} currentGeneration={} file={}",
              requestedGeneration,
              analyzeGeneration.get(),
              fileToAnalyze,
            )
          }
          return@launch
        }
        maybeEvictIdleHeavyCompositeModules()
        if (requestedGeneration != analyzeGeneration.get()) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
              "Analyze skipped after pre-work due to newer request requestedGeneration={} currentGeneration={} file={}",
              requestedGeneration,
              analyzeGeneration.get(),
              fileToAnalyze,
            )
          }
          return@launch
        }
        val attempt =
            scheduledDiagnosticAttemptOverride?.invoke(fileToAnalyze)
                ?: run {
                  val result = analyze(fileToAnalyze)
                  ScheduledDiagnosticAttempt(
                      result = result,
                      retryAfterInteractiveYield = diagnosticProvider?.consumeInteractiveYield(result) == true,
                  )
                }
        val result = attempt.result
        val currentGeneration = analyzeGeneration.get()
        // A stale attempt must not consume a yield belonging to the latest diagnostics generation.
        val retryAfterInteractiveYield =
            requestedGeneration == currentGeneration && attempt.retryAfterInteractiveYield
        val dispatch =
            DiagnosticDispatchDecision.decide(
                result = result,
                requestedGeneration = requestedGeneration,
                currentGeneration = currentGeneration,
                retryAfterInteractiveYield = retryAfterInteractiveYield,
            )
        if (IdeLogConfig.shouldLogInfo()) {
          log.info(
              "Diagnostics dispatch action={} file={} requestedGeneration={} currentGeneration={} resultKind={} retryAfterInteractiveYield={}",
              dispatch,
              fileToAnalyze,
              requestedGeneration,
              currentGeneration,
              if (result == DiagnosticResult.NO_UPDATE) "no-update" else "result",
              retryAfterInteractiveYield,
          )
        }
        when (dispatch) {
          DiagnosticDispatchDecision.Action.DROP -> return@launch
          DiagnosticDispatchDecision.Action.RETRY -> {
            if (IdeLogConfig.shouldLogInfo()) {
              log.info(
                  "Analyze retry scheduled after interactive yield requestedGeneration={} file={}",
                  requestedGeneration,
                  fileToAnalyze,
              )
            }
            startOrRestartAnalyzeTimer(forceRestart = true)
            return@launch
          }
          DiagnosticDispatchDecision.Action.PUBLISH -> Unit
        }
        withContext(Dispatchers.Main) {
          if (requestedGeneration == analyzeGeneration.get()) {
            client?.publishDiagnostics(result)
          } else if (IdeLogConfig.shouldLogInfo()) {
            log.info(
              "Analyze publish skipped due to newer request requestedGeneration={} currentGeneration={} file={}",
              requestedGeneration,
              analyzeGeneration.get(),
              fileToAnalyze,
            )
          }
        }
      } finally {
        analyzeLaunchInFlight.set(false)
        if (analyzeRerunRequested.compareAndSet(true, false)) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
              "Analyze trailing rerun requested currentGeneration={} selectedFile={}",
              analyzeGeneration.get(),
              selectedFile,
            )
          }
          analyzeSelected()
        }
      }
    }
  }
}

/** One scheduled diagnostics execution and whether it yielded a one-shot retry request. */
@RestrictTo(RestrictTo.Scope.TESTS)
internal data class ScheduledDiagnosticAttempt(
    val result: DiagnosticResult,
    val retryAfterInteractiveYield: Boolean,
)

/**
 * Pure result dispatch decision kept beside the server that owns generation and debounce state.
 */
internal object DiagnosticDispatchDecision {
  enum class Action {
    DROP,
    RETRY,
    PUBLISH,
  }

  fun decide(
      result: DiagnosticResult,
      requestedGeneration: Long,
      currentGeneration: Long,
      retryAfterInteractiveYield: Boolean,
  ): Action =
      when {
        requestedGeneration != currentGeneration -> Action.DROP
        result == DiagnosticResult.NO_UPDATE && retryAfterInteractiveYield -> Action.RETRY
        result == DiagnosticResult.NO_UPDATE -> Action.DROP
        else -> Action.PUBLISH
      }
}

/** Platform-independent debounce choice kept beside the server that owns AnalyzeTimer. */
internal object AnalyzeScheduleDecision {
  enum class Action {
    KEEP,
    START,
    RESTART,
  }

  data class Plan(
      val intervalMs: Long,
      val action: Action,
  )

  fun decide(
      timerStarted: Boolean,
      currentIntervalMs: Long,
      baseIntervalMs: Long,
      interactiveDelayMs: Long,
      forceRestart: Boolean,
  ): Plan {
    require(baseIntervalMs > 0L) { "baseIntervalMs must be positive" }
    require(interactiveDelayMs >= 0L) { "interactiveDelayMs must not be negative" }
    val intervalMs = maxOf(baseIntervalMs, interactiveDelayMs)
    val action =
        when {
          !timerStarted -> Action.START
          forceRestart || currentIntervalMs < intervalMs -> Action.RESTART
          else -> Action.KEEP
        }
    return Plan(intervalMs, action)
  }
}
