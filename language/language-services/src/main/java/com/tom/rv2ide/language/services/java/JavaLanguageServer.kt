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
package com.tom.rv2ide.language.services.java

import com.tom.rv2ide.common.logging.IdeLogConfig
import androidx.annotation.RestrictTo
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent
import com.tom.rv2ide.eventbus.events.file.FileCreationEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.language.compiler.javac.fs.CacheFSInfoSingleton
import com.tom.rv2ide.language.compiler.javac.fs.CachingJarFileSystemProvider.clearCache
import com.tom.rv2ide.language.compiler.javac.fs.CachingJarFileSystemProvider.clearCachesForPaths
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.internal.model.CachedCompletion
import com.tom.rv2ide.language.services.java.actions.JavaCodeActionsMenu
import com.tom.rv2ide.language.services.java.compiler.JavaCompilerService
import com.tom.rv2ide.language.services.java.compiler.SourceFileManager
import com.tom.rv2ide.language.services.java.kotlin.KotlinClassOutputProvider
import com.tom.rv2ide.language.services.java.kotlin.KotlinJvmTypeIndex
import com.tom.rv2ide.language.services.java.models.JavaServerSettings
import com.tom.rv2ide.language.services.java.providers.CodeFormatProvider
import com.tom.rv2ide.language.services.java.providers.CompletionProvider
import com.tom.rv2ide.language.services.java.providers.DefinitionProvider
import com.tom.rv2ide.language.services.java.diagnostics.DiagnosticsAttempt
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsAnalyzer
import com.tom.rv2ide.language.services.java.diagnostics.PublishDecision
import com.tom.rv2ide.language.services.java.providers.JavaHoverProvider
import com.tom.rv2ide.language.services.java.providers.JavaSelectionProvider
import com.tom.rv2ide.language.services.java.providers.ReferenceProvider
import com.tom.rv2ide.language.services.java.providers.SignatureProvider
import com.tom.rv2ide.language.services.java.providers.snippet.JavaSnippetRepository.init
import com.tom.rv2ide.language.services.java.diagnostics.DebounceTimer
import com.tom.rv2ide.language.services.java.semantic.JavaModuleScope
import com.tom.rv2ide.language.services.java.semantic.JavaModuleScopes
import com.tom.rv2ide.lsp.api.CompletionCancellation
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.language.services.java.utils.CancelChecker.Companion.isCancelled
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

  private val diagnosticsAnalyzer: JavaDiagnosticsAnalyzer
  private var lastHeavyModuleCleanupAt: Long = 0L
  override var client: ILanguageClient? = null
    private set

  private var _settings: IServerSettings? = null
  @Volatile private var selectedFile: Path? = null
  private val timer = DebounceTimer { runScheduledAnalysis() }
  private val analysisRunning = AtomicBoolean(false)

  /**
   * Instrumentation-only seam for the scheduling boundary.
   *
   * Production leaves this null and follows the real analyze/provider-yield path below. It lets the
   * Android test exercise Handler debounce, retry scheduling, and main-thread publication without
   * coupling those timing assertions to Tooling/javac setup, which is covered separately on JVM.
   */
  @RestrictTo(RestrictTo.Scope.TESTS)
  internal var diagnosticsAttemptOverride: (suspend (Path) -> DiagnosticsAttempt)? = null
  private val cachedCompletion = AtomicReference(CachedCompletion.EMPTY)
  private var lastChangeDelta = 0

  val settings: IServerSettings
    get() {
      return _settings ?: JavaServerSettings.getInstance().also { _settings = it }
    }

  override val serverId: String = SERVER_ID

  companion object {

    const val SERVER_ID = "ide.lsp.java"
    private const val LARGE_CHANGE_DELTA_FOR_DIAGNOSTIC_DEBOUNCE = 2_000
    private const val LARGE_CHANGE_ANALYZE_INTERVAL_MS = 1_500L

    /** Short re-arm used after a pass yielded, so diagnostics retry without a full debounce cycle. */
    private const val RETRY_DELAY_MS = 150L
    private const val HEAVY_COMPOSITE_IDLE_EVICTION_MS = 5 * 60_000L
    private val log = LoggerFactory.getLogger(JavaLanguageServer::class.java)
  }

  init {
    diagnosticsAnalyzer = JavaDiagnosticsAnalyzer()

    applySettings(JavaServerSettings.getInstance())

    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }

    init()
  }

  override fun shutdown() {
    JavaModuleScopes.destroyAll()
    SourceFileManager.clearCache()
    KotlinJvmTypeIndex.clear()
    KotlinClassOutputProvider.clearCache()
    CacheFSInfoSingleton.clearCache()
    clearCache()
    EventBus.getDefault().unregister(this)
    timer.close()
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
    JavaModuleScopes.destroyAll()
    cachedCompletion.set(CachedCompletion.EMPTY)
    diagnosticsAnalyzer.clear()

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
    scheduleDiagnostics()
  }
  override fun complete(params: CompletionParams?): CompletionResult {
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

    val semanticSession = getModuleScope(request.file)
    // No module session means there is no stable environment generation to put in a share key.
    // Preserve the old synchronous NO_MODULE_COMPILER behavior for that exceptional path.
    if (semanticSession == null) {
      return executeCompletion(request, null, null)
    }

    val epoch = semanticSession.epoch
    val key =
        JavaModuleScope.CompletionRequestKey.create(
            request.file,
            request.documentVersion,
            request.documentRevision,
            epoch,
            request.position.requireIndex().toLong(),
            request.prefix,
        )
    val (state, created) = semanticSession.acquireCompletion(key)
    val subscriberLease = state.attachSubscriber()
    if (IdeLogConfig.shouldLogInfo()) {
      log.info(
          "Completion in-flight {} file={} version={} revision={} epoch={} cursor={} prefixLength={} subscribers={}",
          if (created) "leader" else "follower",
          key.file,
          key.documentVersion,
          key.documentRevision,
          key.epoch,
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
          val result = executeCompletion(workerRequest, semanticSession, epoch)
          state.complete(result)
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "Completion worker terminal=completed file={} version={} revision={} epoch={} cursor={} itemCount={} durationMs={}",
                key.file,
                key.documentVersion,
                key.documentRevision,
                key.epoch,
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
                "Completion worker terminal={} file={} version={} revision={} epoch={} cursor={} errorType={} durationMs={}",
                if (isCancelled(error)) "cancelled" else "failed",
                key.file,
                key.documentVersion,
                key.documentRevision,
                key.epoch,
                key.cursorIndex,
                error.javaClass.simpleName,
                System.currentTimeMillis() - startedAt,
            )
          }
        } finally {
          CompletionCancellation.restore(previousChecker)
          semanticSession.removeCompletion(state)
        }
      }
    }
    return deferred
  }

  /** Runs only on the independent completion worker (or the legacy no-module fallback path). */
  private fun executeCompletion(
      request: CompletionParams,
      semanticSession: JavaModuleScope?,
      epoch: Long?,
  ): CompletionResult {
    val foregroundLease = semanticSession?.beginForegroundSemantic()
    try {
      if (diagnosticsAnalyzer.isAnalyzing()) {
        diagnosticsAnalyzer.cancel()
      }
      val compiler = semanticSession?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
      val completionProvider =
          CompletionProvider(compiler, settings, cachedCompletion.get()) { completion ->
            updateCachedCompletion(completion, request, semanticSession, epoch)
          }
      val result = completionProvider.complete(request)
      val currentDocument =
          isCurrentDocument(request.file, request.documentVersion, request.documentRevision)
      val currentSession = isCurrentScope(semanticSession, epoch)
      if (!currentDocument || !currentSession) {
        log.warn(
            "Completion dropped after execution file={} version={} revision={} epoch={} documentCurrent={} sessionCurrent={} itemCount={} cached={}",
            request.file,
            request.documentVersion,
            request.documentRevision,
            epoch,
            currentDocument,
            currentSession,
            result.items.size,
            result.isCached,
        )
        return CompletionResult.EMPTY
      }
      if (result.items.isEmpty()) {
        log.warn(
            "Completion produced no items file={} cursor={} prefix={} version={} revision={} epoch={} cached={}",
            request.file,
            request.position.requireIndex(),
            request.prefix,
            request.documentVersion,
            request.documentRevision,
            epoch,
            result.isCached,
        )
      }
      return result
    } finally {
      foregroundLease?.close()
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
    val semanticSession = getModuleScope(params.file)
    val foregroundLease = semanticSession?.beginForegroundSemantic()
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
    val semanticSession = getModuleScope(params.file)
    val foregroundLease = semanticSession?.beginForegroundSemantic()
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
    val semanticSession = getModuleScope(params.file)
    val foregroundLease = semanticSession?.beginForegroundSemantic()
    try {
      val compiler = semanticSession?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
      return JavaSelectionProvider(compiler).expandSelection(params)
    } finally {
      foregroundLease?.close()
    }
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    if (
        !settings.signatureHelpEnabled() ||
            !isCurrentDocument(params.file, params.documentVersion, params.documentRevision)
    ) {
      return SignatureHelp(emptyList(), -1, -1)
    }
    val semanticSession = getModuleScope(params.file)
    // Preserve legacy behavior where no module session/environment identity is available. Unknown
    // document stamps are likewise kept on the non-shared compatibility path: a strict key must
    // never merge requests whose source snapshot cannot be proven identical.
    if (
        semanticSession == null ||
            params.documentVersion < 0 ||
            params.documentRevision < 0L ||
            params.position.requireIndex() < 0
    ) {
      return executeSignatureHelp(params, semanticSession, semanticSession?.epoch)
    }

    val epoch = semanticSession.epoch
    val key =
        JavaModuleScope.SignatureRequestKey.create(
            params.file,
            params.documentVersion,
            params.documentRevision,
            epoch,
            params.position.requireIndex().toLong(),
        )
    val (state, created) = semanticSession.acquireSignatureHelp(key)
    val subscriberLease = state.attachSubscriber()
    if (IdeLogConfig.shouldLogInfo()) {
      log.info(
          "Signature Help in-flight {} file={} version={} revision={} epoch={} cursor={} subscribers={}",
          if (created) "leader" else "follower",
          key.file,
          key.documentVersion,
          key.documentRevision,
          key.epoch,
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
          val result = executeSignatureHelp(workerParams, semanticSession, epoch)
          state.complete(result)
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "Signature Help worker terminal=completed file={} version={} revision={} epoch={} cursor={} signatureCount={} durationMs={}",
                key.file,
                key.documentVersion,
                key.documentRevision,
                key.epoch,
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
                "Signature Help worker terminal={} file={} version={} revision={} epoch={} cursor={} errorType={} durationMs={}",
                if (isCancelled(error)) "cancelled" else "failed",
                key.file,
                key.documentVersion,
                key.documentRevision,
                key.epoch,
                key.cursorIndex,
                error.javaClass.simpleName,
                System.currentTimeMillis() - startedAt,
            )
          }
        } finally {
          semanticSession.removeSignatureHelp(state)
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
      semanticSession: JavaModuleScope?,
      epoch: Long?,
  ): SignatureHelp {
    val foregroundLease = semanticSession?.beginForegroundSemantic()
    try {
      val compiler = semanticSession?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
      val result = SignatureProvider(compiler, params.cancelChecker).signatureHelp(params)
      return if (
          isCurrentDocument(params.file, params.documentVersion, params.documentRevision) &&
              isCurrentScope(semanticSession, epoch)
      ) {
        result
      } else {
        SignatureHelp(emptyList(), -1, -1)
      }
    } finally {
      foregroundLease?.close()
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
    val semanticSession = getModuleScope(params.file)
    val foregroundLease = semanticSession?.beginForegroundSemantic()
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

    return diagnosticsAnalyzer.analyze(file)
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
        JavaModuleScopes.destroyAll()
        true
      }
    }
  }

  private fun getModuleScope(file: Path?): JavaModuleScope? {
    if (!DocumentUtils.isJavaFile(file)) {
      return null
    }
    val workspace = getInstance().getWorkspace() ?: return null
    val module = workspace.findModuleForFile(file!!) ?: return null
    workspace.ensureModuleActivated(module)
    return JavaModuleScopes.forModule(module)
  }

  private fun isCurrentScope(
      session: JavaModuleScope?,
      epoch: Long?,
  ): Boolean {
    return session == null ||
        (epoch != null && JavaModuleScopes.isCurrent(session, epoch))
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun getCompiler(file: Path?): JavaCompilerService {
    return getModuleScope(file)?.compiler() ?: JavaCompilerService.NO_MODULE_COMPILER
  }

  private fun updateCachedCompletion(
      completion: CachedCompletion,
      request: CompletionParams,
      session: JavaModuleScope?,
      epoch: Long?,
  ) {
    Objects.requireNonNull(completion)
    // CompletionProvider invokes this callback before complete() returns. Recheck request identity
    // here so a slower stale request cannot overwrite the cache subsequently used by the newest
    // editor version.
    if (
        !isCurrentDocument(request.file, request.documentVersion, request.documentRevision) ||
            !isCurrentScope(session, epoch)
    ) {
      log.info(
          "Completion cache write skipped for stale request file={} version={} revision={} epoch={}",
          request.file,
          request.documentVersion,
          request.documentRevision,
          epoch,
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

  private fun scheduleDiagnostics() {
    if (VMUtils.isJvm()) {
      return
    }
    // Pure debounce: every scheduled change pushes the deadline out, and a pass that is still
    // running simply re-arms the timer. No extra request state is involved.
    timer.interval =
        if (abs(lastChangeDelta) >= LARGE_CHANGE_DELTA_FOR_DIAGNOSTIC_DEBOUNCE) {
          LARGE_CHANGE_ANALYZE_INTERVAL_MS
        } else {
          DebounceTimer.DEFAULT_INTERVAL_MS
        }
    timer.restart()
  }

  /** Re-arms the timer with a short delay after a pass yielded to foreground work. */
  private fun scheduleRetry() {
    if (VMUtils.isJvm()) {
      return
    }
    timer.interval = RETRY_DELAY_MS
    timer.restart()
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
    lastChangeDelta = event.changeDelta

    // EventBus delivery is asynchronous. Cancel only states whose event revision is provably
    // older than this change; a delayed older event must not kill a newer completion worker.
    // This does not need module resolution, which may temporarily fail during close/rename flows.
    JavaModuleScopes.cancelOlderThan(event.changedFile, event.revision)
    scheduleDiagnostics()
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileSelected(event: DocumentSelectedEvent) {
    selectedFile = event.selectedFile
    // Kotlin source changes can occur while its editor is selected. Once the user returns to an
    // open Java file, force a full diagnostics pass so stale Kotlin ABI/classpath state is never
    // retained merely because no Java document change was made.
    if (DocumentUtils.isJavaFile(event.selectedFile)) {
      lastChangeDelta = 0
      scheduleDiagnostics()
    }
  }
  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileOpened(event: DocumentOpenEvent) {
    selectedFile = event.openedFile
    lastChangeDelta = 0

    // Some editor flows intentionally omit the eager text payload for very large files to avoid an
    // additional full-buffer String allocation during open. Recover the initial snapshot from the
    // active document cache when that happens so Java analysis still sees the correct content.
    if (event.text.isBlank()) {
      event.text = com.tom.rv2ide.projects.FileManager.getDocumentContents(event.openedFile)
    }

    scheduleDiagnostics()
  }


  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileClosed(event: DocumentCloseEvent) {
    JavaModuleScopes.cancelForFile(event.closedFile)
    diagnosticsAnalyzer.forget(event.closedFile)

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
    runScheduledAnalysis()
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
      JavaModuleScopes.destroy(module)
    } else {
      // A deletion is commonly delivered after its file can no longer be resolved to a module.
      // Prefer a conservative global reset over retaining an ABI stub for a deleted Kotlin type.
      KotlinJvmTypeIndex.clear()
      JavaModuleScopes.destroyAll()
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
      lastChangeDelta = 0
      scheduleDiagnostics()
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onLazyModuleEvicted(event: LazyModuleEvictedEvent) {
    JavaModuleScopes.destroy(event.module)
    // Cached completion candidates may depend on the evicted module's classpath/type index.
    cachedCompletion.set(CachedCompletion.EMPTY)
  }

  private fun runScheduledAnalysis() {
    val fileToAnalyze = selectedFile ?: return
    if (client == null) {
      return
    }
    // The debounce timer is the queue: a schedule that fires while a pass is running re-arms the
    // timer instead of being tracked by a second flag, so no trailing-request state is needed.
    if (!analysisRunning.compareAndSet(false, true)) {
      if (IdeLogConfig.shouldLogInfo()) {
        log.info("Analyze coalesced while running file={}", fileToAnalyze)
      }
      scheduleDiagnostics()
      return
    }
    CoroutineScope(Dispatchers.Default).launch {
      try {
        maybeEvictIdleHeavyCompositeModules()
        val attempt =
            diagnosticsAttemptOverride?.invoke(fileToAnalyze)
                ?: run {
                  val result = analyze(fileToAnalyze)
                  DiagnosticsAttempt(
                      result = result,
                      retryAfterYield = diagnosticsAnalyzer.consumeYieldRetry(result),
                  )
                }
        val result = attempt.result
        val isCurrentTarget = selectedFile == fileToAnalyze
        val dispatch =
            PublishDecision.decide(
                result = result,
                isCurrentTarget = isCurrentTarget,
                retryAfterYield = attempt.retryAfterYield,
            )
        if (IdeLogConfig.shouldLogInfo()) {
          log.info(
              "Diagnostics dispatch action={} file={} currentTarget={} resultKind={} retryAfterYield={}",
              dispatch,
              fileToAnalyze,
              isCurrentTarget,
              if (result == DiagnosticResult.NO_UPDATE) "no-update" else "result",
              attempt.retryAfterYield,
          )
        }
        when (dispatch) {
          PublishDecision.Action.DROP -> return@launch
          PublishDecision.Action.RETRY -> {
            if (IdeLogConfig.shouldLogInfo()) {
              log.info("Analyze retry scheduled after foreground yield file={}", fileToAnalyze)
            }
            scheduleRetry()
            return@launch
          }
          PublishDecision.Action.PUBLISH -> Unit
        }
        withContext(Dispatchers.Main) {
          if (selectedFile == fileToAnalyze) {
            client?.publishDiagnostics(result)
          } else if (IdeLogConfig.shouldLogInfo()) {
            log.info("Analyze publish skipped because the target changed file={}", fileToAnalyze)
          }
        }
      } finally {
        analysisRunning.set(false)
      }
    }
  }
}
