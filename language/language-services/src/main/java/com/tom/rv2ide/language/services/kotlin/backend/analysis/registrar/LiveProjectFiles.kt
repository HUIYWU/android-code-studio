package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisProjectContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaClassFileType
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.JavaImplicitClassUtil
import org.jetbrains.kotlin.com.intellij.util.Processor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

// TODO(ACS-ANALYSIS-EXPERIMENT): Validate scopes and archives; JPMS root.findChild still bypasses active module-info.java mappings.
internal class LiveProjectFiles(
    context: KotlinAnalysisProjectContext,
    private val registry: LiveSourceFileRegistry,
) : AutoCloseable {
  internal class ProcessStats {
    var roots: Int = 0
    var snapshotHits: Int = 0
    var snapshotBuilds: Int = 0
    var snapshotBuildUs: Long = 0L
    var entriesSeen: Int = 0
    var duplicateEntries: Int = 0
    var activeOverrides: Int = 0
    var missingVirtualFiles: Int = 0
    var scopeRejected: Int = 0
    var directories: Int = 0
    var javaSources: Int = 0
    var javaClasses: Int = 0
    var otherFiles: Int = 0
    var activeFiles: Int = 0
    var activeScopeRejected: Int = 0
    var processed: Int = 0
  }

  private data class Entry(val path: Path, val archiveEntry: String? = null) {
    fun virtualFile(): VirtualFile? = if (archiveEntry == null) {
      StandardFileSystems.local().findFileByPath(path.toString())
    } else {
      StandardFileSystems.jar().findFileByPath("$path!/$archiveEntry")
    }
  }

  private data class Snapshot(
      val byName: Map<String, List<Entry>>,
      val javaByJvmName: Map<String, List<Entry>>,
  )

  private val contentRoots = minimalRoots(context.modules.flatMap { module ->
    listOf(module.projectDirectory) + module.sourceRoots + module.generatedSourceRoots + module.moduleOutputRoots
  })
  private val libraryRoots = context.modules.flatMap { module ->
    module.binaryRoots + module.bootClasspaths + module.libraryAttachments.flatMap { it.binaryPaths + it.sourcePaths }
  }.map { it.toAbsolutePath().normalize() }.distinct()
  private val snapshots = HashMap<Path, Snapshot>()
  private val revision = AtomicLong()
  private var closed = false

  val fileSetRevision: Long
    get() = revision.get()

  val modificationStamp: Long
    get() = fileSetRevision + registry.fileMappingRevision

  fun invalidate(path: Path? = null): Int {
    return synchronized(snapshots) {
      val normalized = path?.toAbsolutePath()?.normalize()
      val removedSnapshots = if (normalized == null) {
        val count = snapshots.size
        snapshots.clear()
        count
      } else {
        val removed = snapshots.keys.count { root -> root.startsWith(normalized) || normalized.startsWith(root) }
        snapshots.keys.removeAll { root -> root.startsWith(normalized) || normalized.startsWith(root) }
        removed
      }
      revision.incrementAndGet()
      removedSnapshots
    }
  }

  fun process(
      scope: GlobalSearchScope?,
      names: Set<String>?,
      processor: Processor<in VirtualFile>,
  ): Boolean = process(scope, names, null, null, processor)

  fun processJavaFilesWithJvmName(
      scope: GlobalSearchScope,
      name: String,
      processor: Processor<in VirtualFile>,
  ): Boolean = process(scope, null, name, null, processor)

  internal fun processWithStats(
      scope: GlobalSearchScope?,
      processor: Processor<in VirtualFile>,
      stats: ProcessStats,
  ): Boolean = process(scope, null, null, stats, processor)

  private fun process(
      scope: GlobalSearchScope?,
      names: Set<String>?,
      javaJvmName: String?,
      stats: ProcessStats?,
      processor: Processor<in VirtualFile>,
  ): Boolean {
    val active = registry.virtualFilesSnapshot()
    val seen = HashSet<Entry>()
    val roots = if (scope == null || scope.isSearchInLibraries()) contentRoots + libraryRoots else contentRoots
    stats?.roots = roots.size
    for (root in roots) {
      ProgressManager.checkCanceled()
      val snapshot = snapshot(root, stats)
      val groups = when {
        javaJvmName != null -> listOfNotNull(snapshot.javaByJvmName[javaJvmName])
        names != null -> names.mapNotNull(snapshot.byName::get)
        else -> snapshot.byName.values
      }
      for (entries in groups) {
        for (entry in entries) {
          ProgressManager.checkCanceled()
          stats?.entriesSeen = stats?.entriesSeen?.plus(1) ?: 0
          if (!seen.add(entry)) {
            stats?.duplicateEntries = stats?.duplicateEntries?.plus(1) ?: 0
            continue
          }
          if (entry.archiveEntry == null && active.containsKey(entry.path)) {
            stats?.activeOverrides = stats?.activeOverrides?.plus(1) ?: 0
            continue
          }
          val file = entry.virtualFile()
          if (file == null) {
            stats?.missingVirtualFiles = stats?.missingVirtualFiles?.plus(1) ?: 0
            continue
          }
          stats?.let {
            when {
              file.isDirectory -> it.directories++
              file.fileType == JavaFileType.INSTANCE -> it.javaSources++
              file.fileType == JavaClassFileType.INSTANCE -> it.javaClasses++
              else -> it.otherFiles++
            }
          }
          if (scope != null && !scope.contains(file)) {
            stats?.scopeRejected = stats?.scopeRejected?.plus(1) ?: 0
            continue
          }
          stats?.processed = stats?.processed?.plus(1) ?: 0
          if (!processor.process(file)) return false
        }
      }
    }
    for ((path, file) in active) {
      ProgressManager.checkCanceled()
      if (names != null && path.fileName.toString() !in names) continue
      if (javaJvmName != null && (!file.name.endsWith(".java", ignoreCase = true) ||
          JavaImplicitClassUtil.getJvmName(file.name) != javaJvmName)) continue
      if (!belongsToContent(path)) continue
      stats?.activeFiles = stats?.activeFiles?.plus(1) ?: 0
      stats?.let {
        when {
          file.isDirectory -> it.directories++
          file.fileType == JavaFileType.INSTANCE -> it.javaSources++
          file.fileType == JavaClassFileType.INSTANCE -> it.javaClasses++
          else -> it.otherFiles++
        }
      }
      if (scope != null && !scope.contains(file)) {
        stats?.activeScopeRejected = stats?.activeScopeRejected?.plus(1) ?: 0
        continue
      }
      stats?.processed = stats?.processed?.plus(1) ?: 0
      if (!processor.process(file)) return false
    }
    return true
  }

  fun contains(file: VirtualFile): Boolean {
    val activePath = registry.pathForVirtualFile(file)
    if (activePath != null) return belongsToContent(activePath)
    val path = file.path.substringBefore("!/")
    val localPath = Path.of(path).toAbsolutePath().normalize()
    return Files.exists(localPath) && (belongsToContent(localPath) || libraryRoots.any { localPath.startsWith(it) })
  }

  private fun belongsToContent(path: Path): Boolean = contentRoots.any { path.startsWith(it) }

  fun ensureUpToDate(scope: GlobalSearchScope?) {
    val roots = if (scope == null || scope.isSearchInLibraries()) contentRoots + libraryRoots else contentRoots
    roots.forEach { root ->
      ProgressManager.checkCanceled()
      snapshot(root)
    }
  }

  private fun snapshot(root: Path, stats: ProcessStats? = null): Snapshot = synchronized(snapshots) {
    check(!closed) { "Analysis file set is closed" }
    snapshots[root]?.let {
      stats?.snapshotHits = stats?.snapshotHits?.plus(1) ?: 0
      return@synchronized it
    }
    stats?.snapshotBuilds = stats?.snapshotBuilds?.plus(1) ?: 0
    val startedAt = System.nanoTime()
    scan(root).also {
      snapshots[root] = it
      stats?.snapshotBuildUs = stats?.snapshotBuildUs?.plus((System.nanoTime() - startedAt) / 1_000L) ?: 0L
    }
  }

  private fun scan(root: Path): Snapshot {
    if (!Files.exists(root)) return Snapshot(emptyMap(), emptyMap())
    val byName = HashMap<String, MutableList<Entry>>()
    if (Files.isDirectory(root)) {
      Files.walk(root).use { paths ->
        val iterator = paths.iterator()
        while (iterator.hasNext()) {
          ProgressManager.checkCanceled()
          val path = iterator.next()
          byName.getOrPut(path.fileName.toString()) { ArrayList(1) }.add(Entry(path))
        }
      }
    } else if (root.fileName.toString().substringAfterLast('.').lowercase() in setOf("jar", "zip")) {
      ZipFile(root.toFile()).use { archive ->
        val entries = archive.entries()
        val paths = HashSet<String>()
        while (entries.hasMoreElements()) {
          ProgressManager.checkCanceled()
          val name = entries.nextElement().name.trimEnd('/')
          var path = name
          while (path.isNotEmpty() && paths.add(path)) {
            byName.getOrPut(path.substringAfterLast('/')) { ArrayList(1) }.add(Entry(root, path))
            path = path.substringBeforeLast('/', "")
          }
        }
      }
    } else {
      byName[root.fileName.toString()] = arrayListOf(Entry(root))
    }
    val javaByJvmName = HashMap<String, MutableList<Entry>>()
    for ((name, entries) in byName) {
      ProgressManager.checkCanceled()
      if (name.endsWith(".java", ignoreCase = true)) {
        javaByJvmName.getOrPut(JavaImplicitClassUtil.getJvmName(name)) { ArrayList(entries.size) }.addAll(entries)
      }
    }
    return Snapshot(byName, javaByJvmName)
  }

  override fun close() {
    synchronized(snapshots) {
      closed = true
      snapshots.clear()
    }
  }

  private fun minimalRoots(paths: List<Path>): List<Path> {
    val result = ArrayList<Path>()
    paths.map { it.toAbsolutePath().normalize() }.distinct().sortedBy { it.nameCount }.forEach { path ->
      if (result.none { path.startsWith(it) }) result.add(path)
    }
    return result
  }
}
