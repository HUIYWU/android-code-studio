package com.tom.rv2ide.language.services.kotlin.backend.analysis.live

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisWrite
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.AnalysisResolutionScopeProvider
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.publishModificationEvent
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtFile
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
      currentFiles[normalized]?.takeIf { it.identity == identity }?.let { return@synchronized it.file }
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
      if (live.isStale) null else result
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
      pins[path]?.let { it.count++; return it }
    }
    refreshCurrentKtFile(path) ?: return null
    synchronized(refreshLock) {
      pins[path]?.let { it.count++; return it }
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
        AnalysisResolutionScopeProvider.getInstance(project).invalidateAll()
      }
    }
  }

  private fun refreshLocked(path: Path, source: LiveFileSourceSnapshot): KtFile? {
    val identity = source.identity
    val content = source.content ?: return null
    val newFile = project.analysisRead { parser.createFile(path.fileName.toString(), content) }
    newFile.liveBackingPath = path
    var committed = false
    ApplicationManager.getApplication().runWriteAction {
      project.analysisWrite {
        synchronized(refreshLock) {
          // TODO(ACS-ANALYSIS-EXPERIMENT): Revalidate the source identity under the canonical lock order before publishing.
          if (sourceIdentity(path) != identity) return@synchronized
          val old = currentFiles[path]?.file
          sourceRegistry.register(path, newFile.viewProvider.virtualFile)
          project.analysisRead {
            metadataIndex.replace(path, newFile)
          }
          currentFiles[path] = VersionedFile(identity, newFile)
          KaSourceModificationService.getInstance(project)
              .handleElementModification(old ?: newFile, KaElementModificationType.Unknown)
          AnalysisResolutionScopeProvider.getInstance(project).invalidateAll()
          committed = true
        }
      }
    }
    return newFile.takeIf { committed }
  }

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
    val content = Files.newBufferedReader(path, java.nio.charset.Charset.defaultCharset()).use { it.readText() }
    if (sourceIdentity(path) != identity) return null
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