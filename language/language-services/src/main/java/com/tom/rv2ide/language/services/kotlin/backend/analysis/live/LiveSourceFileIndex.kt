package com.tom.rv2ide.language.services.kotlin.backend.analysis.live

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisWrite
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.models.OneHopDocumentEdit
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.publishModificationEvent
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.com.intellij.lang.FileASTNode
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Files
import java.nio.file.Path

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal enum class LiveFileSourceKind {
  ACTIVE_DOCUMENT,
  DISK_FILE,
  MISSING,
}

internal data class LiveFileIdentity(
    val kind: LiveFileSourceKind,
    val version: Int = -1,
    val revision: Long = -1L,
    val size: Long = -1L,
    val lastModifiedMillis: Long = -1L,
)

private data class LiveFileSourceSnapshot(
    val identity: LiveFileIdentity,
    val content: String?,
)

internal class LiveSourceFileIndex(
    val project: Project,
    private val sourceRegistry: LiveSourceFileRegistry,
    val metadataIndex: KotlinSourceMetadataIndex,
) : AutoCloseable {
  private data class VersionedFile(val identity: LiveFileIdentity, val file: KtFile)
  // PSI object identity may change when a declaration is rebuilt by the AST diff; associate the declaration by stable source position and name instead.
  private data class FunctionAnchor(
      val startOffset: Int,
      val name: String?,
  )
  private class Pin(val file: KtFile, val identity: LiveFileIdentity) {
    var count: Int = 0
    var pending: PendingState? = null
  }

  private val currentFiles = ConcurrentHashMap<Path, VersionedFile>()
  private val pins = ConcurrentHashMap<Path, Pin>()
  private val refreshLock = Any()
  private val refreshExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
    Thread(runnable, "acs-kotlin-live-refresh").apply { isDaemon = true }
  }

  lateinit var parser: KtPsiFactory
  private fun logLive(
      action: String,
      path: Path,
      file: KtFile? = null,
      identity: LiveFileIdentity? = null,
      detail: String = "",
  ) {
    KlsLogs.info(
        "Kotlin Analysis live PSI: runtime={}, action={}, path={}, psi={}, identity={}, detail={}",
        System.identityHashCode(this), action, path, file?.let { System.identityHashCode(it) }, identity, detail,
    )
  }

  private fun trackerState(): String {
    val psi = runCatching {
      PsiModificationTracker.getInstance(project).modificationCount
    }.getOrNull()?.toString() ?: "unavailable"
    val fir = runCatching {
      val trackerClass = Class.forName("org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationTracker")
      val tracker = project.getService(trackerClass)
      trackerClass.getMethod("getModificationCount").invoke(tracker)
    }.getOrNull()?.toString() ?: "unavailable"
    return "psiModificationCount=$psi; firInBlockModificationCount=$fir"
  }

  private fun modificationShape(element: PsiElement, inBlockAncestor: PsiElement?): String {
    val parents = generateSequence(element.parent) { it.parent }
        .take(6)
        .joinToString(">") { it.javaClass.simpleName }
    val range = element.textRange?.let { "${it.startOffset}..${it.endOffset}" } ?: "none"
    return "element=${element.javaClass.simpleName}; language=${element.language.id}; range=$range; isWhitespace=${element is PsiWhiteSpace}; isKtElement=${element is KtElement}; parents=$parents; inBlockAncestor=${inBlockAncestor?.javaClass?.simpleName ?: "none"}"
  }

  private fun modificationAnchor(file: KtFile, edit: OneHopDocumentEdit, start: Int): PsiElement {
    // Ordinary body edits use a semantic leaf; body structure transitions use explicit ElementAdded/ElementRemoved below.
    // A non-empty deletion anchors before the deleted range; otherwise the offset may resolve to trailing whitespace.
    val lookupOffset = if (edit.kind == OneHopDocumentEdit.Kind.DELETE && edit.baseEndIndex > edit.baseStartIndex) {
      (start - 1).coerceAtLeast(0)
    } else {
      start
    }
    val candidate = file.findElementAt(lookupOffset) ?: file
    return if (candidate is PsiWhiteSpace) {
      PsiTreeUtil.prevLeaf(candidate) ?: candidate
    } else {
      candidate
    }
  }

  private fun functionAt(file: KtFile, offset: Int): KtNamedFunction? {
    // Function association skips arbitrary trivia; fixed offset windows are invalid when whitespace length is unbounded.
    if (file.textLength == 0) return null
    var leaf = file.findElementAt(offset.coerceIn(0, file.textLength - 1))
    while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment)) {
      leaf = PsiTreeUtil.prevLeaf(leaf) ?: PsiTreeUtil.nextLeaf(leaf)
    }
    return leaf?.let { PsiTreeUtil.getParentOfType(it, KtNamedFunction::class.java, false) }
  }

  private fun functionAnchor(function: KtNamedFunction?): FunctionAnchor? {
    val range = function?.textRange ?: return null
    return FunctionAnchor(range.startOffset, function.name)
  }

  // Reparsed declarations are valid event targets even when the old PSI object has been invalidated.
  private fun matchesFunctionAnchor(function: KtNamedFunction?, anchor: FunctionAnchor?): Boolean {
    if (function == null || anchor == null) return false
    val range = function.textRange ?: return false
    return range.startOffset == anchor.startOffset && function.name == anchor.name
  }

  private fun bodyState(function: KtNamedFunction?): String {
    val body = function?.bodyBlockExpression
    val functionRange = function?.textRange?.let { "${it.startOffset}..${it.endOffset}" } ?: "none"
    val bodyRange = body?.textRange?.let { "${it.startOffset}..${it.endOffset}" } ?: "none"
    return "functionFound=${function != null}; functionValid=${function?.isValid == true}; functionPsi=${function?.let { System.identityHashCode(it) } ?: "none"}; functionRange=$functionRange; bodyFound=${body != null}; bodyPsi=${body?.let { System.identityHashCode(it) } ?: "none"}; bodyRange=$bodyRange"
  }

  private fun flushFirModifications(): String {
    val serviceClass = Class.forName("org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirDeclarationModificationService")
    val service = project.getService(serviceClass) ?: error("FIR declaration modification service unavailable")
    serviceClass.getMethod("flushModifications").invoke(service)
    return serviceClass.name
  }

  fun refreshCurrentKtFile(path: Path): KtFile? {
    val normalized = path.normalize()
    if (!sourceRegistry.isKotlinSource(normalized)) return null
    val source = sourceSnapshot(normalized) ?: return null
    val identity = source.identity
    var removed: RemovedSource? = null
    var shouldRefresh = false
    val existing = synchronized(refreshLock) {
      pins[normalized]?.let { pin ->
        updatePending(normalized, pin, identity)
        return@synchronized pin.file
      }
      currentFiles[normalized]?.takeIf { it.identity == identity }?.let {
        return@synchronized it.file
      }
      if (identity.kind == LiveFileSourceKind.MISSING) {
        removed = detachCurrent(normalized)
        return@synchronized null
      }
      shouldRefresh = true
      null
    }
    removed?.let(::notifyRemoved)
    if (existing != null || !shouldRefresh) return existing
    return refreshLocked(normalized, source)
  }

  private fun detachCurrent(path: Path): RemovedSource? {
    val module = sourceRegistry.moduleForPath(path)
    val current = currentFiles.remove(path)
    sourceRegistry.unregister(path)
    metadataIndex.remove(path)
    return current?.let { RemovedSource(it.file, module) }
  }

  private fun updatePending(path: Path, pin: Pin, identity: LiveFileIdentity) {
    val previousRemoval = when (val pending = pin.pending) {
      is PendingState.Removed -> pending.source
      is PendingState.Refresh -> pending.removed
      null -> null
    }
    if (identity.kind == LiveFileSourceKind.MISSING) {
      val source = previousRemoval ?: detachCurrent(path)
          ?: RemovedSource(pin.file, sourceRegistry.moduleForPath(path))
      pin.pending = PendingState.Removed(source)
    } else if (identity != pin.identity || previousRemoval != null) {
      pin.pending = PendingState.Refresh(previousRemoval)
    }
  }

  fun refreshCurrentKtFileAsync(path: Path) {
    refreshExecutor.submit { refreshCurrentKtFile(path) }
  }

  fun applyDocumentEdit(path: Path, edit: OneHopDocumentEdit?) {
    val normalized = path.normalize()
    if (edit == null) {
      logLive("edit-fallback", normalized, detail = "reason=no-one-hop-edit")
      refreshCurrentKtFile(normalized)
      return
    }
    logLive(
        "edit-received",
        normalized,
        identity = edit.targetIdentity(),
        detail = "base=${edit.baseIdentity()}; target=${edit.targetIdentity()}; range=${edit.baseStartIndex}..${edit.baseEndIndex}; kind=${edit.kind}",
    )
    if (!applyDocumentEditLocked(normalized, edit)) {
      logLive("edit-fallback", normalized, identity = edit.targetIdentity(), detail = "reason=incremental-apply-rejected; full-refresh-disabled")
      // Temporarily disable the full-file refresh fallback so PSI reparse failures remain observable.
      // refreshCurrentKtFile(normalized)
    }
  }

  private fun applyDocumentEditLocked(
      path: Path,
      edit: OneHopDocumentEdit,
  ): Boolean {
    val current = synchronized(refreshLock) { currentFiles[path] } ?: run {
      logLive("edit-fallback", path, identity = edit.targetIdentity(), detail = "reason=no-canonical-psi")
      return false
    }
    val sourceIdentity = sourceIdentity(path)
    if (current.identity != edit.baseIdentity()) {
      logLive("edit-fallback", path, current.file, edit.targetIdentity(), "reason=base-identity-mismatch; current=${current.identity}; base=${edit.baseIdentity()}")
      return false
    }
    if (sourceIdentity != edit.targetIdentity()) {
      logLive("edit-fallback", path, current.file, edit.targetIdentity(), "reason=target-identity-mismatch; source=$sourceIdentity; target=${edit.targetIdentity()}")
      return false
    }
    val virtualFile = current.file.virtualFile ?: run {
      logLive("edit-fallback", path, current.file, edit.targetIdentity(), "reason=no-virtual-file")
      return false
    }
    val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: run {
      logLive("edit-fallback", path, current.file, edit.targetIdentity(), "reason=no-document")
      return false
    }
    if (document.text != current.file.text) {
      logLive("edit-fallback", path, current.file, edit.targetIdentity(), "reason=document-psi-text-mismatch")
      return false
    }
    var applied = false
    var stage = "write-action"
    try {
      CommandProcessor.getInstance().runUndoTransparentAction {
        ApplicationManager.getApplication().runWriteAction {
          project.analysisWrite {
            synchronized(refreshLock) {
            val currentStillMatches =
                currentFiles[path]?.file === current.file && sourceIdentity(path) == edit.targetIdentity()
            val start = edit.baseStartIndex
            val end = edit.baseEndIndex
            if (!currentStillMatches) {
              logLive("edit-fallback", path, current.file, edit.targetIdentity(), "reason=commit-identity-mismatch")
            } else if (start < 0 || end < start || end > document.textLength) {
              logLive("edit-fallback", path, current.file, edit.targetIdentity(), "reason=range-invalid; range=${start}..${end}; documentLength=${document.textLength}")
            } else {
              logLive("edit-apply", path, current.file, edit.baseIdentity(), "range=${start}..${end}; replacementLength=${edit.replacementText.length}; kind=${edit.kind}")
              stage = "replace"
              logLive("edit-replace-start", path, current.file, edit.baseIdentity(), "range=${start}..${end}; replacementLength=${edit.replacementText.length}")
              val oldText = current.file.text
              val functionBefore = functionAt(current.file, start)
              val declarationAnchor = functionAnchor(functionBefore)
              val bodyBefore = functionBefore?.bodyBlockExpression
              logLive("edit-body-before", path, current.file, edit.targetIdentity(), "offset=$start; declarationAnchor=$declarationAnchor; ${bodyState(functionBefore)}")
              document.replaceString(start, end, edit.replacementText)
              val targetText = document.text
              val changedRange = TextRange(start, start + edit.replacementText.length)
              stage = "reparse"
              logLive(
                  "edit-reparse-start",
                  path,
                  current.file,
                  edit.targetIdentity(),
                  "oldLength=${oldText.length}; targetLength=${targetText.length}; range=${changedRange.startOffset}..${changedRange.endOffset}",
              )
              val oldNode = current.file.node as? FileASTNode
                  ?: error("Canonical PSI file node is not a FileASTNode")
              // Apply the AST diff to the existing canonical KtFile; commitDocument() alone leaves it stale in Standalone.
              val diffLog = BlockSupport.getInstance(project).reparseRange(
                  current.file,
                  oldNode,
                  TextRange(start, end),
                  targetText,
                  EmptyProgressIndicator(),
                  oldText,
              )
              stage = "psi-change"
              diffLog.doActualPsiChange(current.file)
              logLive(
                  "edit-reparse-success",
                  path,
                  current.file,
                  edit.targetIdentity(),
                  "documentLength=${document.textLength}; psiLength=${current.file.textLength}",
              )
              if (document.text != targetText || current.file.text != targetText) {
                logLive(
                    "edit-fallback",
                    path,
                    current.file,
                    edit.targetIdentity(),
                    "reason=reparse-text-mismatch; documentLength=${document.textLength}; psiLength=${current.file.textLength}; targetLength=${targetText.length}",
                 )
              } else {
                val structurallyLocatedFunction = functionAt(current.file, start)
                val functionAfter = if (functionBefore?.isValid == true) functionBefore else structurallyLocatedFunction
                val reusedBefore = functionBefore?.isValid == true && functionAfter === functionBefore
                val reparsedSameDeclaration = !reusedBefore && matchesFunctionAnchor(functionAfter, declarationAnchor)
                val functionAssociation = when {
                  reusedBefore -> "REUSED_BEFORE"
                  reparsedSameDeclaration -> "REPARSED_SAME_DECLARATION"
                  functionAfter != null -> "STRUCTURAL_LOOKUP_UNMATCHED"
                  else -> "STRUCTURAL_LOOKUP_MISSING"
                }
                val sameDeclaration = reusedBefore || reparsedSameDeclaration
                val bodyAfter = functionAfter?.bodyBlockExpression
                val transition = when {
                  !sameDeclaration -> "FUNCTION_ASSOCIATION_UNRESOLVED"
                  bodyBefore == null && bodyAfter != null -> "ADDED"
                  bodyBefore != null && bodyAfter == null -> "REMOVED"
                  else -> "NONE"
                }
                logLive("edit-body-after", path, current.file, edit.targetIdentity(), "offset=$start; transition=$transition; functionAssociation=$functionAssociation; sameDeclaration=$sameDeclaration; ${bodyState(functionAfter)}")
                val modificationService = KaSourceModificationService.getInstance(project)
                stage = "modification-service"
                // Body insertion must report the new block; body removal keeps the old block only as removed-element metadata because the AST diff invalidates it.
                // When the declaration PSI was replaced, the file event target forces FIR to classify removal as out-of-block and invalidate the source module.
                // TODO(ACS-ANALYSIS-EXPERIMENT): Verify additional declaration-with-body transitions.
                val modificationEvent = when {
                    sameDeclaration && bodyBefore == null && bodyAfter != null -> {
                      val detail = "event=ElementAdded; element=${bodyAfter.javaClass.simpleName}; eventElement=${bodyAfter.javaClass.simpleName}"
                      logLive("edit-modification-start", path, current.file, edit.targetIdentity(), "$detail; ${trackerState()}")
                      modificationService.handleElementModification(bodyAfter, KaElementModificationType.ElementAdded)
                      detail
                    }
                    sameDeclaration && functionAfter != null && bodyBefore != null && bodyAfter == null -> {
                      // A body-less reparsed function has no FIR body marker; use the valid file target to avoid the deferred in-block queue dropping this change.
                      val removalEventElement = if (reparsedSameDeclaration) current.file else functionAfter
                      val detail = "event=ElementRemoved; element=${bodyBefore.javaClass.simpleName}; eventElement=${removalEventElement.javaClass.simpleName}; moduleInvalidation=${reparsedSameDeclaration}"
                      logLive("edit-modification-start", path, current.file, edit.targetIdentity(), "$detail; ${trackerState()}")
                      modificationService.handleElementModification(removalEventElement, KaElementModificationType.ElementRemoved(bodyBefore))
                      detail
                    }
                    else -> {
                      val changed = modificationAnchor(current.file, edit, start)
                      val inBlockAncestor = runCatching {
                        modificationService.ancestorAffectedByInBlockModification(changed)
                      }.getOrNull()
                      val shape = modificationShape(changed, inBlockAncestor)
                      val detail = "event=Unknown; $shape"
                      logLive("edit-modification-start", path, current.file, edit.targetIdentity(), "$detail; ${trackerState()}")
                      modificationService.handleElementModification(changed, KaElementModificationType.Unknown)
                      detail
                    }
                 }
                 logLive("edit-modification-success", path, current.file, edit.targetIdentity(), "$modificationEvent; ${trackerState()}")
                 // Standalone MockApplication does not dispatch writeActionFinished; the deferred FIR queue must be flushed explicitly before committing the new identity.
                 stage = "fir-flush"
                 logLive("edit-fir-flush-start", path, current.file, edit.targetIdentity(), trackerState())
                 val flushService = flushFirModifications()
                logLive("edit-fir-flush-success", path, current.file, edit.targetIdentity(), "service=$flushService; ${trackerState()}")
                currentFiles[path] = VersionedFile(edit.targetIdentity(), current.file)
                logLive("incremental", path, current.file, edit.targetIdentity(), modificationEvent)
                applied = true
              }
            }
          }
        }
      }
    }
    } catch (t: Throwable) {
      val safeMessage = t.message?.replace('\n', ' ')?.take(160).orEmpty()
      logLive("edit-exception", path, current.file, edit.targetIdentity(), "stage=$stage; exception=${t.javaClass.simpleName}; message=$safeMessage")
    }
    if (!applied) {
      logLive("edit-failed", path, current.file, edit.targetIdentity(), "reason=write-action-no-commit")
    }
    return applied
  }

  fun <T> withLiveKtFile(
      path: Path,
      cancelChecker: ICancelChecker = ICancelChecker.NOOP,
      action: (LiveKtFile) -> T,
  ): T? {
    cancelChecker.abortIfCancelled()
    val normalized = path.normalize()
    val pin = acquirePin(normalized) ?: return null
    return try {
      val live = PinnedLiveKtFile(normalized, this, pin.file, pin.identity)
      val result = action(live)
      if (live.isStale) {
        null
      } else {
        result
      }
    } finally {
      releasePin(normalized)
    }
  }

  fun getCurrentKtFile(path: Path): KtFile? {
    val normalized = path.normalize()
    val current = currentFiles[normalized] ?: return null
    return current.file.takeIf { isCurrent(normalized, current.identity, it) }
  }

  fun isCurrent(path: Path, identity: LiveFileIdentity, file: KtFile): Boolean {
    val normalized = path.normalize()
    val current = currentFiles[normalized] ?: return false
    return current.file === file && current.identity == identity && sourceIdentity(normalized) == identity
  }

  fun getKtFile(virtualFile: VirtualFile): KtFile? {
    val path = sourceRegistry.pathForVirtualFile(virtualFile) ?: runCatching { Path.of(virtualFile.path).normalize() }.getOrNull() ?: return null
    return getCurrentKtFile(path)
  }

  fun currentPsiFilesFor(module: KaSourceModule): List<PsiFile> = synchronized(refreshLock) {
    currentFiles.keys
        .filter { path -> sourceRegistry.moduleForPath(path) === module }
        .mapNotNull(::getCurrentKtFile)
  }

  fun currentKtFiles(): List<KtFile> = synchronized(refreshLock) {
    currentFiles.keys.mapNotNull(::getCurrentKtFile)
  }

  fun currentPaths(): List<Path> = synchronized(refreshLock) {
    currentFiles.keys.filter { getCurrentKtFile(it) != null }
  }

  fun registerDiskFile(path: Path): KtFile? = refreshCurrentKtFile(path)

  fun closeCurrent(path: Path) {
    val normalized = path.normalize()
    refreshCurrentKtFile(normalized)
  }

  fun removeFromSourceIndex(path: Path) {
    val normalized = path.normalize()
    val removed = synchronized(refreshLock) {
      val pin = pins[normalized]
      if (pin != null) {
        val module = sourceRegistry.moduleForPath(normalized)
        val file = currentFiles.remove(normalized)?.file ?: pin.file
        sourceRegistry.unregister(normalized)
        metadataIndex.remove(normalized)
        pin.pending = PendingState.Removed(RemovedSource(file, module))
        return@synchronized null
      }
      val module = sourceRegistry.moduleForPath(normalized)
      val file = currentFiles.remove(normalized)?.file
      sourceRegistry.unregister(normalized)
      metadataIndex.remove(normalized)
      RemovedSource(file, module)
    }
    if (removed != null) notifyRemoved(removed)
  }

  fun removeFromSourceIndexUnder(root: Path) {
    val normalized = root.normalize()
    val paths = synchronized(refreshLock) {
      (currentFiles.keys + metadataIndex.paths() + pins.keys)
          .filter { it == normalized || it.startsWith(normalized) }
          .toSet()
    }
    if (paths.isEmpty()) {
      removeFromSourceIndex(normalized)
    } else {
      paths.forEach(::removeFromSourceIndex)
    }
  }

  fun moveSourceIndex(fromPath: Path, toPath: Path) {
    val from = fromPath.normalize()
    val to = toPath.normalize()
    removeFromSourceIndexUnder(from)
    if (Files.isDirectory(to)) {
      runCatching {
        Files.walk(to).use { stream ->
          stream.filter { Files.isRegularFile(it) }
              .filter { sourceRegistry.isKotlinSource(it) }
              .forEach { refreshCurrentKtFileAsync(it) }
        }
      }
    } else if (sourceRegistry.isKotlinSource(to)) {
      refreshCurrentKtFileAsync(to)
    }
  }

  private fun acquirePin(path: Path): Pin? {
    synchronized(refreshLock) {
      pins[path]?.let {
        it.count++
        return it
      }
    }
    refreshCurrentKtFile(path) ?: return null
    synchronized(refreshLock) {
      pins[path]?.let {
        it.count++
        return it
      }
      val current = currentFiles[path] ?: return null
      return Pin(current.file, current.identity).also {
        it.count = 1
        pins[path] = it
      }
    }
  }

  private fun releasePin(path: Path) {
    val action = synchronized(refreshLock) {
      val pin = pins[path] ?: return@synchronized PinRelease.None
      pin.count--
      if (pin.count > 0) return@synchronized PinRelease.None
      pins.remove(path)
      when (val pending = pin.pending) {
        is PendingState.Removed -> PinRelease.Remove(pending.source)
        is PendingState.Refresh -> PinRelease.Refresh(pending.removed)
        null -> PinRelease.None
      }
    }
    when (action) {
      is PinRelease.Remove -> notifyRemoved(action.source)
      is PinRelease.Refresh -> {
        action.removed?.let(::notifyRemoved)
        if (FileManager.isActive(path) || Files.exists(path)) refreshCurrentKtFileAsync(path)
      }
      PinRelease.None -> Unit
    }
  }

  private fun notifyRemoved(source: RemovedSource) {
    ApplicationManager.getApplication().runWriteAction {
      project.analysisWrite {
        val file = source.file
        val module = source.module
        if (file != null) {
          KaSourceModificationService.getInstance(project)
              .handleElementModification(file, KaElementModificationType.ElementRemoved(file))
        } else if (module != null) {
          project.publishModificationEvent(KotlinModuleOutOfBlockModificationEvent(module))
        }
      }
    }
  }

  private fun refreshLocked(path: Path, source: LiveFileSourceSnapshot): KtFile? {
    val identity = source.identity
    val content = source.content ?: return null
    val newFile = project.analysisRead { parser.createFile(path.fileName.toString(), content) }
    newFile.liveBackingPath = path
    var result: KtFile? = null
    var removed: RemovedSource? = null
    ApplicationManager.getApplication().runWriteAction {
      project.analysisWrite {
        synchronized(refreshLock) {
          // TODO(ACS-ANALYSIS-EXPERIMENT): Verify canonical commit and module invalidation with concurrent refresh and pin lifecycle.
          val latestIdentity = sourceIdentity(path)
          pins[path]?.let { pin ->
            updatePending(path, pin, latestIdentity)
            result = pin.file
            return@synchronized
          }
          if (latestIdentity.kind == LiveFileSourceKind.MISSING) {
            removed = detachCurrent(path)
            return@synchronized
          }
          if (latestIdentity != identity) {
            return@synchronized
          }
          currentFiles[path]?.takeIf { it.identity == identity }?.let { current ->
            result = current.file
            return@synchronized
          }
          val old = currentFiles[path]?.file
          val module = sourceRegistry.moduleForPath(path)
          sourceRegistry.register(path, newFile.viewProvider.virtualFile)
          project.analysisRead {
            metadataIndex.replace(path, newFile)
          }
          currentFiles[path] = VersionedFile(identity, newFile)
           if (old == null) {
             KaSourceModificationService.getInstance(project)
                 .handleElementModification(newFile, KaElementModificationType.Unknown)
           } else if (module != null) {
             project.publishModificationEvent(KotlinModuleOutOfBlockModificationEvent(module))
           } else {
             KaSourceModificationService.getInstance(project)
                 .handleElementModification(old, KaElementModificationType.Unknown)
           }
          result = newFile
        }
      }
    }
    removed?.let(::notifyRemoved)
    return result
  }

  private fun OneHopDocumentEdit.baseIdentity(): LiveFileIdentity =
    LiveFileIdentity(LiveFileSourceKind.ACTIVE_DOCUMENT, version = base.version, revision = base.revision)

  private fun OneHopDocumentEdit.targetIdentity(): LiveFileIdentity =
    LiveFileIdentity(LiveFileSourceKind.ACTIVE_DOCUMENT, version = target.version, revision = target.revision)

  private fun sourceIdentity(path: Path): LiveFileIdentity {
    FileManager.getActiveDocumentSnapshot(path)?.let { snapshot ->
      return LiveFileIdentity(
          kind = LiveFileSourceKind.ACTIVE_DOCUMENT,
          version = snapshot.version,
          revision = snapshot.revision,
      )
    }
    return diskIdentity(path)
  }

  private fun diskIdentity(path: Path): LiveFileIdentity {
    val attributes = try {
      Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
    } catch (_: java.nio.file.NoSuchFileException) {
      return LiveFileIdentity(LiveFileSourceKind.MISSING)
    }
    if (!attributes.isRegularFile) return LiveFileIdentity(LiveFileSourceKind.MISSING)
    return LiveFileIdentity(
        kind = LiveFileSourceKind.DISK_FILE,
        size = attributes.size(),
        lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
    )
  }

  private fun sourceSnapshot(path: Path): LiveFileSourceSnapshot? {
    FileManager.getActiveDocumentSnapshot(path)?.let { snapshot ->
      return LiveFileSourceSnapshot(
          identity = LiveFileIdentity(
              kind = LiveFileSourceKind.ACTIVE_DOCUMENT,
              version = snapshot.version,
              revision = snapshot.revision,
          ),
          content = snapshot.content,
      )
    }
    val identity = diskIdentity(path)
    if (identity.kind == LiveFileSourceKind.MISSING) return LiveFileSourceSnapshot(identity, null)
    val content = try {
      Files.newBufferedReader(path, java.nio.charset.Charset.defaultCharset()).use { it.readText() }
    } catch (_: java.nio.file.NoSuchFileException) {
      return LiveFileSourceSnapshot(LiveFileIdentity(LiveFileSourceKind.MISSING), null)
    } catch (_: java.nio.file.FileSystemException) {
      return null
    }
    if (sourceIdentity(path) != identity) {
      return null
    }
    return LiveFileSourceSnapshot(identity, content)
  }

  override fun close() {
    synchronized(refreshLock) {
      currentFiles.clear()
      pins.clear()
    }
    refreshExecutor.shutdownNow()
  }

  private data class RemovedSource(
      val file: KtFile?,
      val module: KaModule?,
  )

  private sealed interface PendingState {
    data class Refresh(val removed: RemovedSource?) : PendingState
    data class Removed(val source: RemovedSource) : PendingState
  }

  private sealed interface PinRelease {
    object None : PinRelease
    data class Refresh(val removed: RemovedSource?) : PinRelease
    data class Remove(val source: RemovedSource) : PinRelease
  }
}