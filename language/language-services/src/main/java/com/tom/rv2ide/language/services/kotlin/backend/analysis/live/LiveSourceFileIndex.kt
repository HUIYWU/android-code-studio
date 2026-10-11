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

internal class LiveSourceFileIndex(
    val project: Project,
    private val sourceRegistry: LiveSourceFileRegistry,
    val metadataIndex: KotlinSourceMetadataIndex,
) : AutoCloseable {
  private data class VersionedFile(val version: Int, val file: KtFile)
  private class Pin(val file: KtFile, val version: Int) {
    var count: Int = 0
    @Volatile var refreshOwed: Boolean = false
    @Volatile var removalOwed: Boolean = false
  }

  private val currentFiles = ConcurrentHashMap<Path, VersionedFile>()
  private val pins = ConcurrentHashMap<Path, Pin>()
  private val refreshLock = Any()
  private val refreshExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
    Thread(runnable, "acs-kotlin-live-refresh").apply { isDaemon = true }
  }

  lateinit var parser: KtPsiFactory

  fun activeVersion(path: Path): Int? = FileManager.getActiveDocumentSnapshot(path.normalize())?.version

  fun refreshCurrentKtFile(path: Path): KtFile? {
    val normalized = path.normalize()
    if (!sourceRegistry.isKotlinSource(normalized)) return null
    val version = activeVersion(normalized) ?: DISK_VERSION
    synchronized(refreshLock) {
      pins[normalized]?.let { pin ->
        if (version != DISK_VERSION && version != pin.version) pin.refreshOwed = true
        return pin.file
      }
      currentFiles[normalized]?.takeIf { it.version == version }?.let { return it.file }
      return refreshLocked(normalized, version)
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
    try {
      return action(PinnedLiveKtFile(normalized, this, pin.file, pin.version))
    } finally {
      releasePin(normalized)
    }
  }

  fun getCurrentKtFile(path: Path): KtFile? {
    val normalized = path.normalize()
    pins[normalized]?.let { return it.file }
    return currentFiles[normalized]?.file
  }

  fun getKtFile(virtualFile: VirtualFile): KtFile? {
    val path = sourceRegistry.pathForVirtualFile(virtualFile) ?: runCatching { Path.of(virtualFile.path).normalize() }.getOrNull() ?: return null
    return getCurrentKtFile(path)
  }

  fun currentPsiFilesFor(module: KaSourceModule): List<PsiFile> =
      synchronized(refreshLock) {
        currentFiles.entries
            .filter { (path, _) -> sourceRegistry.moduleForPath(path) === module }
            .map { it.value.file }
      }

  fun currentKtFiles(): List<KtFile> = synchronized(refreshLock) {
    buildList {
      addAll(currentFiles.values.map { it.file })
      addAll(pins.values.map { it.file })
    }.distinct()
  }

  fun currentPaths(): List<Path> = synchronized(refreshLock) {
    (currentFiles.keys + pins.keys).distinct()
  }

  fun registerDiskFile(path: Path): KtFile? = refreshCurrentKtFile(path)

  fun closeCurrent(path: Path) {
    val normalized = path.normalize()
    synchronized(refreshLock) {
      pins[normalized]?.let {
        it.refreshOwed = true
        return
      }
    }
    refreshCurrentKtFile(normalized)
  }

  fun removeFromSourceIndex(path: Path) {
    val normalized = path.normalize()
    val removed = synchronized(refreshLock) {
      val pin = pins[normalized]
      if (pin != null) {
        pin.refreshOwed = false
        pin.removalOwed = true
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
      return Pin(current.file, current.version).also {
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
      when {
        pin.removalOwed -> {
          val module = sourceRegistry.moduleForPath(path)
          val file = currentFiles.remove(path)?.file
          sourceRegistry.unregister(path)
          metadataIndex.remove(path)
          PinRelease.Remove(RemovedSource(file, module))
        }
        pin.refreshOwed -> PinRelease.Refresh
        else -> PinRelease.None
      }
    }
    when (action) {
      is PinRelease.Remove -> notifyRemoved(action.source)
      PinRelease.Refresh -> if (FileManager.isActive(path) || Files.exists(path)) refreshCurrentKtFileAsync(path)
      PinRelease.None -> Unit
    }
  }

  private fun notifyRemoved(source: RemovedSource) {
    project.analysisWrite {
      ApplicationManager.getApplication().runWriteAction {
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

  private fun refreshLocked(path: Path, version: Int): KtFile? {
    val content = runCatching { FileManager.getDocumentContents(path) }.getOrNull() ?: return null
    val old = currentFiles[path]?.file
    val newFile = project.analysisRead { parser.createFile(path.fileName.toString(), content) }
    newFile.liveBackingPath = path
    project.analysisWrite {
      sourceRegistry.register(path, newFile.viewProvider.virtualFile)
      metadataIndex.replace(path, newFile)
      currentFiles[path] = VersionedFile(version, newFile)
      ApplicationManager.getApplication().runWriteAction {
        KaSourceModificationService.getInstance(project)
            .handleElementModification(old ?: newFile, KaElementModificationType.Unknown)
        AnalysisResolutionScopeProvider.getInstance(project).invalidateAll()
      }
    }
    return newFile
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

  private sealed interface PinRelease {
    object None : PinRelease
    object Refresh : PinRelease
    data class Remove(val source: RemovedSource) : PinRelease
  }

  companion object {
    private const val DISK_VERSION = -1
  }
}