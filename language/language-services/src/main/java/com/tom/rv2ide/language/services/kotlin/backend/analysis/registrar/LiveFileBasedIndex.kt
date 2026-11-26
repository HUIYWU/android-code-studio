package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalVirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdIndex
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import org.jetbrains.kotlin.com.intellij.psi.search.FileTypeIndex
import org.jetbrains.kotlin.com.intellij.psi.search.FilenameIndex
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.com.intellij.util.indexing.DumbModeAccessType
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex.AllKeysQuery
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex.IndexWritingFile
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex.ProjectSpecificInputFilter
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex.ValueProcessor
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContentImpl
import org.jetbrains.kotlin.com.intellij.util.indexing.ID
import org.jetbrains.kotlin.com.intellij.util.indexing.IdFilter
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexedFileImpl
import java.util.concurrent.ConcurrentHashMap

@Suppress("UnstableApiUsage", "DEPRECATION", "UNCHECKED_CAST")
internal class LiveFileBasedIndex : FileBasedIndex() {
  private val projects = ConcurrentHashMap<Project, LiveProjectFiles>()
  private data class ActiveKey(val path: Path)
  private data class DiskStamp(val modified: FileTime, val size: Long, val fileKey: Any?)
  private data class IdSnapshot(
      val indexStamp: Long,
      val fileStamp: Long,
      val contentStamp: Long,
      val fileType: FileType,
      val accepted: Boolean,
      val textSource: Int,
      val diskStamp: DiskStamp?,
      val activeFile: WeakReference<VirtualFile>?,
      val masks: Map<IdIndexEntry, Int>,
  )
  private class IdSnapshotCache {
    private val entries = LinkedHashMap<Any, IdSnapshot>(256, 0.75f, true)
    private var maskEntries = 0L

    @Synchronized
    fun get(key: Any): IdSnapshot? = entries[key]

    @Synchronized
    fun put(key: Any, value: IdSnapshot): Int {
      entries.remove(key)?.let { maskEntries -= it.masks.size }
      if (value.masks.size > 524_288) return 0
      entries[key] = value
      maskEntries += value.masks.size
      var evicted = 0
      val iterator = entries.entries.iterator()
      while (entries.size > 65_536 || maskEntries > 524_288L) {
        maskEntries -= iterator.next().value.masks.size
        iterator.remove()
        evicted++
      }
      return evicted
    }

    @Synchronized
    fun remove(key: Any) {
      entries.remove(key)?.let { maskEntries -= it.masks.size }
    }

    @Synchronized
    fun clear() {
      entries.clear()
      maskEntries = 0L
    }
  }
  private val idSnapshots = ConcurrentHashMap<Project, IdSnapshotCache>()

  private class IdQueryKey(
      val project: Project,
      val scope: GlobalSearchScope,
      val entry: IdIndexEntry,
      val fileSetRevision: Long,
      val mappingRevision: Long,
  ) {
    override fun equals(other: Any?): Boolean = other is IdQueryKey &&
        project === other.project && scope === other.scope && entry == other.entry &&
        fileSetRevision == other.fileSetRevision && mappingRevision == other.mappingRevision

    override fun hashCode(): Int {
      var result = System.identityHashCode(project)
      result = 31 * result + System.identityHashCode(scope)
      result = 31 * result + entry.hashCode()
      result = 31 * result + fileSetRevision.hashCode()
      return 31 * result + mappingRevision.hashCode()
    }
  }

  private class IdQueryCache {
    private val entries = LinkedHashMap<IdQueryKey, Map<VirtualFile, Int>>(16, 0.75f, true)
    private var candidateCount = 0

    fun get(key: IdQueryKey): Map<VirtualFile, Int>? = entries[key]

    fun put(key: IdQueryKey, candidates: Map<VirtualFile, Int>) {
      entries.remove(key)?.let { candidateCount -= it.size }
      entries[key] = candidates
      candidateCount += candidates.size
      val iterator = entries.entries.iterator()
      while (entries.size > 64 || candidateCount > 8192) {
        candidateCount -= iterator.next().value.size
        iterator.remove()
      }
    }
  }

  private class IdQueryCollector(keys: Collection<IdIndexEntry>) {
    var candidates: Map<IdIndexEntry, LinkedHashMap<VirtualFile, Int>>? =
        keys.associateWith { LinkedHashMap<VirtualFile, Int>() }
      private set
    private var candidateCount = 0

    fun add(file: VirtualFile, masks: Map<IdIndexEntry, Int>) {
      for ((entry, files) in candidates ?: return) {
        if (!masks.containsKey(entry)) continue
        if (files.put(file, masks.getValue(entry)) == null) candidateCount++
        if (candidateCount > 8192) {
          candidates = null
          return
        }
      }
    }
  }

  private val idQueryCache = ThreadLocal<IdQueryCache>()

  fun <T> withIdQueryCache(action: () -> T): T {
    val previous = idQueryCache.get()
    idQueryCache.set(IdQueryCache())
    try {
      return action()
    } finally {
      if (previous == null) idQueryCache.remove() else idQueryCache.set(previous)
    }
  }

  private fun snapshotKey(files: LiveProjectFiles, file: VirtualFile): Any {
    files.activePath(file)?.let { return ActiveKey(it) }
    return if (file is CoreLocalVirtualFile) Path.of(file.path).toAbsolutePath().normalize() else file
  }
  private val idExtension by lazy { IdIndex() }
  private val idInputFilter by lazy { idExtension.inputFilter as ProjectSpecificInputFilter }
  private val idIndexer by lazy { idExtension.indexer }
  private val dumbModeAccess = ThreadLocal<DumbModeAccessType>()

  private class IdQueryStats {
    var candidates = 0
    var matchedFiles = 0
    var candidateNs = 0L
    var consumerNs = 0L
    var snapshots = 0
    var queryCacheHits = 0
    var queryCacheStores = 0
    var queryCacheCandidates = 0
    var cacheHits = 0
    var cacheMissing = 0
    var indexStale = 0
    var indexOnlyStale = 0
    var binaryIndexOnlyStale = 0
    var textIndexOnlyStale = 0
    var fileStale = 0
    var contentStale = 0
    var inputStale = 0
    var cacheEvictions = 0
    var firstOldIndexStamp = -1L
    var firstNewIndexStamp = -1L
    var rebuilt = 0
    var binaryBuilds = 0
    var textBuilds = 0
    var rejectedBuilds = 0
    var vfsInputs = 0
    var providerInputs = 0
    var documentInputs = 0
    var textChars = 0L
    var generatedKeys = 0L
    var snapshotNs = 0L
    var prepareNs = 0L
    var contentCreateNs = 0L
    var mapNs = 0L
  }

  private fun traceIdQuery(
      enabled: Boolean,
      operation: String,
      project: Project?,
      scope: GlobalSearchScope?,
      describe: () -> String,
      action: (IdQueryStats?) -> Boolean,
  ): Boolean {
    if (!enabled || !IdeLogConfig.shouldLogInfo()) return action(null)
    val stats = IdQueryStats()
    val query = System.identityHashCode(stats)
    KlsLogs.info(
        "Java IdIndex perf begin: query={}, operation={}, project={}, scope={}, libraries={}, keys={}",
        query, operation, project?.let { System.identityHashCode(it) }, scope?.javaClass?.name,
        scope?.isSearchInLibraries(), describe(),
    )
    val startedAt = System.nanoTime()
    var outcome = "failed"
    try {
      return action(stats).also { outcome = if (it) "completed" else "stopped" }
    } catch (cancelled: CancellationException) {
      outcome = "cancelled"
      throw cancelled
    } finally {
      val totalNs = System.nanoTime() - startedAt
      KlsLogs.info(
          "Java IdIndex perf end: query={}, operation={}, outcome={}, totalUs={}, candidates={}, matchedFiles={}, candidateUs={}, consumerUs={}, outsideCandidateUs={}, snapshots={}, queryCacheHits={}, queryCacheStores={}, queryCacheCandidates={}, cacheHits={}, cacheMissing={}, indexStale={}, indexOnlyStale={}, binaryIndexOnlyStale={}, textIndexOnlyStale={}, fileStale={}, contentStale={}, inputStale={}, cacheEvictions={}, firstOldIndexStamp={}, firstNewIndexStamp={}, rebuilt={}, binaryBuilds={}, textBuilds={}, rejectedBuilds={}, vfsInputs={}, providerInputs={}, documentInputs={}, textChars={}, generatedKeys={}, snapshotUs={}, prepareUs={}, contentCreateUs={}, mapUs={}",
          query, operation, outcome, totalNs / 1_000L, stats.candidates, stats.matchedFiles,
          stats.candidateNs / 1_000L, stats.consumerNs / 1_000L, (totalNs - stats.candidateNs) / 1_000L,
          stats.snapshots, stats.queryCacheHits, stats.queryCacheStores, stats.queryCacheCandidates,
          stats.cacheHits, stats.cacheMissing, stats.indexStale, stats.indexOnlyStale,
          stats.binaryIndexOnlyStale, stats.textIndexOnlyStale,
          stats.fileStale, stats.contentStale, stats.inputStale, stats.cacheEvictions, stats.firstOldIndexStamp, stats.firstNewIndexStamp,
          stats.rebuilt, stats.binaryBuilds, stats.textBuilds, stats.rejectedBuilds,
          stats.vfsInputs, stats.providerInputs, stats.documentInputs, stats.textChars, stats.generatedKeys,
          stats.snapshotNs / 1_000L, stats.prepareNs / 1_000L, stats.contentCreateNs / 1_000L, stats.mapNs / 1_000L,
      )
    }
  }

  private inline fun processMatched(stats: IdQueryStats?, action: () -> Boolean): Boolean {
    if (stats == null) return action()
    stats.matchedFiles++
    val startedAt = System.nanoTime()
    try {
      return action()
    } finally {
      stats.consumerNs += System.nanoTime() - startedAt
    }
  }

  override fun getCurrentDumbModeAccessType(project: Project?): DumbModeAccessType? = dumbModeAccess.get()

  override fun <T, E : Throwable> ignoreDumbMode(
      dumbModeAccessType: DumbModeAccessType, computable: ThrowableComputable<T, E>,
  ): T {
    val previous = dumbModeAccess.get()
    dumbModeAccess.set(dumbModeAccessType)
    try {
      return computable.compute()
    } finally {
      if (previous == null) dumbModeAccess.remove() else dumbModeAccess.set(previous)
    }
  }

  override fun <T> inheritCurrentDumbAccessType(processor: Processor<in T>): Processor<in T> {
    val access = dumbModeAccess.get() ?: return processor
    return Processor { value ->
      ignoreDumbMode(access, ThrowableComputable<Boolean, RuntimeException> { processor.process(value) })
    }
  }

  fun bind(project: Project, files: LiveProjectFiles) {
    check(projects.putIfAbsent(project, files) == null) { "Analysis project file set already registered" }
    idSnapshots[project] = IdSnapshotCache()
  }

  fun unbind(project: Project) {
    projects.remove(project)
    idSnapshots.remove(project)
  }

  private fun projectFiles(project: Project?): List<Pair<Project, LiveProjectFiles>> =
      if (project == null) projects.entries.map { it.key to it.value }
      else listOf(project to checkNotNull(projects[project]) { "Analysis project file set is not registered" })

  private fun fileSets(project: Project?): Collection<LiveProjectFiles> = projectFiles(project).map { it.second }

  private fun idSnapshot(project: Project, file: VirtualFile, stats: IdQueryStats? = null): IdSnapshot {
    val startedAt = if (stats != null) System.nanoTime() else 0L
    if (stats != null) stats.snapshots++
    try {
      val files = checkNotNull(projects[project]) { "Analysis project file set is not registered" }
      val cache = checkNotNull(idSnapshots[project]) { "Analysis IdIndex cache is not registered" }
      ProgressManager.checkCanceled()
      val indexStamp = files.fileSetRevision
      val fileStamp = file.modificationStamp
      val input = IndexedFileImpl(file, project)
      val fileType = input.fileType
      // Active overlays use PSI/document text; disk files are indexed from disk.
      val activePath = files.activePath(file)
      val accepted = file.isValid && idInputFilter.acceptInput(input)
      val provider = if (activePath != null && accepted && !fileType.isBinary) PsiManager.getInstance(project).findCachedViewProvider(file) else null
      val document = if (activePath != null && accepted && !fileType.isBinary) FileDocumentManager.getInstance().getCachedDocument(file) else null
      val textSource = when {
        provider != null -> 1
        document != null -> 2
        else -> 0
      }
      val contentStamp = provider?.modificationStamp ?: document?.modificationStamp ?: fileStamp
      val key = snapshotKey(files, file)
      // Invalidate by real file attributes; independent of the VirtualFile implementation, `!/` members excluded.
      val diskStamp = if (accepted && textSource == 0 && "!/" !in file.path) {
        try {
          val attributes = Files.readAttributes(Path.of(file.path), BasicFileAttributes::class.java)
          DiskStamp(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey())
        } catch (_: java.nio.file.NoSuchFileException) {
          null
        }
      } else null
      val previous = cache.get(key)
      val sameInput = previous != null && previous.fileType == fileType && previous.accepted == accepted &&
          previous.textSource == textSource && previous.diskStamp == diskStamp &&
          (key !is ActiveKey || previous.activeFile?.get() === file)
      if (stats != null) stats.prepareNs += System.nanoTime() - startedAt
      if (previous != null && sameInput && previous.indexStamp == indexStamp &&
          previous.fileStamp == fileStamp && previous.contentStamp == contentStamp) {
        if (stats != null) stats.cacheHits++
        return previous
      }
      if (stats != null) {
        if (previous == null) {
          stats.cacheMissing++
        } else {
          if (previous.indexStamp != indexStamp) {
            stats.indexStale++
            if (sameInput && previous.fileStamp == fileStamp && previous.contentStamp == contentStamp) {
              stats.indexOnlyStale++
              if (accepted) {
                if (input.fileType.isBinary) stats.binaryIndexOnlyStale++ else stats.textIndexOnlyStale++
              }
            }
            if (stats.firstOldIndexStamp == -1L) {
              stats.firstOldIndexStamp = previous.indexStamp
              stats.firstNewIndexStamp = indexStamp
            }
          }
          if (previous.fileStamp != fileStamp) stats.fileStale++
          if (previous.contentStamp != contentStamp) stats.contentStale++
          if (!sameInput) stats.inputStale++
        }
      }
      val masks = if (accepted) {
        val contentStartedAt = if (stats != null) System.nanoTime() else 0L
        val text = provider?.contents ?: document?.immutableCharSequence
        if (stats != null) {
          if (text == null) stats.vfsInputs++
          else {
            stats.textChars += text.length.toLong()
            if (provider != null) stats.providerInputs++ else stats.documentInputs++
          }
        }
        val content = if (text == null) FileContentImpl.createByFile(file, project)
        else FileContentImpl.createByText(file, text, project)
        if (stats != null) stats.contentCreateNs += System.nanoTime() - contentStartedAt
        val mapStartedAt = if (stats != null) System.nanoTime() else 0L
        try {
          idIndexer.map(content)
        } finally {
          if (stats != null) stats.mapNs += System.nanoTime() - mapStartedAt
        }
      } else emptyMap()
      val snapshot = IdSnapshot(
          indexStamp, fileStamp, contentStamp, fileType, accepted, textSource, diskStamp,
          if (key is ActiveKey) WeakReference(file) else null, masks,
      )
      val evicted = cache.put(key, snapshot)
      if (stats != null) {
        stats.cacheEvictions += evicted
        stats.rebuilt++
        stats.generatedKeys += masks.size.toLong()
        if (!accepted) stats.rejectedBuilds++
        else if (input.fileType.isBinary) stats.binaryBuilds++ else stats.textBuilds++
      }
      return snapshot
    } finally {
      if (stats != null) stats.snapshotNs += System.nanoTime() - startedAt
    }
  }

  private inline fun processCandidate(stats: IdQueryStats?, action: () -> Boolean): Boolean {
    if (stats == null) return action()
    stats.candidates++
    val startedAt = System.nanoTime()
    try {
      return action()
    } finally {
      stats.candidateNs += System.nanoTime() - startedAt
    }
  }

  private fun processProjectFiles(
      project: Project?,
      scope: GlobalSearchScope?,
      names: Set<String>? = null,
      inFile: VirtualFile? = null,
      stats: IdQueryStats? = null,
      processor: (Project, VirtualFile) -> Boolean,
  ): Boolean {
    for ((owner, files) in projectFiles(project)) {
      val processStats = if (stats != null && inFile == null) LiveProjectFiles.ProcessStats(timeFiles = true) else null
      val fileSetRevision = if (stats != null) files.fileSetRevision else 0L
      val mappingRevision = if (stats != null) files.fileMappingRevision else 0L
      val candidateNsBefore = stats?.candidateNs ?: 0L
      val startedAt = if (stats != null) System.nanoTime() else 0L
      var outcome = "failed"
      try {
        val completed = if (inFile != null) {
          ProgressManager.checkCanceled()
          !files.contains(inFile, scope) || processCandidate(stats) { processor(owner, inFile) }
        } else {
          val fileProcessor = Processor<VirtualFile> { file ->
            processCandidate(stats) { !file.isValid || processor(owner, file) }
          }
          if (processStats == null) files.process(scope, names, fileProcessor)
          else files.processWithStats(scope, fileProcessor, processStats, names)
        }
        outcome = if (completed) "completed" else "stopped"
        if (!completed) return false
      } catch (cancelled: CancellationException) {
        outcome = "cancelled"
        throw cancelled
      } finally {
        if (stats != null) {
          val totalNs = System.nanoTime() - startedAt
          val candidateNs = stats.candidateNs - candidateNsBefore
          KlsLogs.info(
              "Java IdIndex files perf: query={}, project={}, singleFile={}, outcome={}, totalUs={}, candidateUs={}, enumerationUs={}, fileSetRevision={}, mappingRevision={}, endFileSetRevision={}, endMappingRevision={}, roots={}, rootSnapshotHits={}, rootSnapshotBuilds={}, rootSnapshotBuildUs={}, entriesSeen={}, duplicateEntries={}, activeOverrides={}, missingVirtualFiles={}, scopeRejected={}, activeScopeRejected={}, processed={}, javaSources={}, javaClasses={}, otherFiles={}, directories={}, virtualFileUs={}, scopeUs={}, archiveFileHits={}, virtualFileLookups={}",
              System.identityHashCode(stats), System.identityHashCode(owner), inFile != null, outcome,
              totalNs / 1_000L, candidateNs / 1_000L, (totalNs - candidateNs) / 1_000L,
              fileSetRevision, mappingRevision, files.fileSetRevision, files.fileMappingRevision,
              processStats?.roots, processStats?.snapshotHits, processStats?.snapshotBuilds, processStats?.snapshotBuildUs,
              processStats?.entriesSeen, processStats?.duplicateEntries, processStats?.activeOverrides,
              processStats?.missingVirtualFiles, processStats?.scopeRejected, processStats?.activeScopeRejected,
              processStats?.processed, processStats?.javaSources, processStats?.javaClasses, processStats?.otherFiles,
              processStats?.directories, processStats?.virtualFileNs?.div(1_000L), processStats?.scopeNs?.div(1_000L),
              processStats?.archiveFileHits, processStats?.virtualFileLookups,
          )
        }
      }
    }
    return true
  }

  private fun processIdQueryFiles(
      scope: GlobalSearchScope,
      keys: Set<IdIndexEntry>,
      requireAllKeys: Boolean,
      stats: IdQueryStats?,
      processor: (Project, VirtualFile, Map<IdIndexEntry, Int>) -> Boolean,
  ): Boolean {
    val cache = idQueryCache.get()?.takeIf { keys.size <= 64 }
        ?: return processProjectFiles(scope.project, scope, stats = stats) { project, file ->
          processor(project, file, idSnapshot(project, file, stats).masks)
        }
    for ((project, files) in projectFiles(scope.project)) {
      ProgressManager.checkCanceled()
      val fileSetRevision = files.fileSetRevision
      val mappingRevision = files.fileMappingRevision
      val queryKeys = keys.associateWith {
        IdQueryKey(project, scope, it, fileSetRevision, mappingRevision)
      }
      val cached = LinkedHashMap<IdIndexEntry, Map<VirtualFile, Int>>()
      for ((entry, key) in queryKeys) cache.get(key)?.let { cached[entry] = it }
      val seed = if (requireAllKeys) {
        cached.values.minByOrNull { it.size }?.keys
      } else if (cached.size == keys.size) {
        LinkedHashSet<VirtualFile>().apply { cached.values.forEach { addAll(it.keys) } }
      } else null
      if (seed != null) {
        if (stats != null) {
          stats.queryCacheHits += cached.size
          stats.queryCacheCandidates += seed.size
        }
        for (file in seed) {
          ProgressManager.checkCanceled()
          if (!processCandidate(stats) {
                if (!file.isValid || !scope.contains(file)) true
                else {
                  val masks = if (cached.size != keys.size) idSnapshot(project, file, stats).masks
                  else HashMap<IdIndexEntry, Int>().apply {
                    cached.forEach { (entry, candidates) -> candidates[file]?.let { put(entry, it) } }
                  }
                  processor(project, file, masks)
                }
              }) return false
        }
      } else {
        val collector = IdQueryCollector(keys)
        val completed = processProjectFiles(project, scope, stats = stats) { owner, file ->
          val masks = idSnapshot(owner, file, stats).masks
          collector.add(file, masks)
          processor(owner, file, masks)
        }
        if (!completed) return false
        ProgressManager.checkCanceled()
        if (files.fileSetRevision == fileSetRevision && files.fileMappingRevision == mappingRevision) {
          collector.candidates?.forEach { (entry, candidates) ->
            cache.put(queryKeys.getValue(entry), candidates)
            if (stats != null) stats.queryCacheStores++
          }
        }
      }
    }
    ProgressManager.checkCanceled()
    return true
  }

  private fun matchesId(
      masks: Map<IdIndexEntry, Int>, keys: Set<*>, valueChecker: Condition<*>?, requireAllKeys: Boolean,
  ): Boolean {
    for (entry in keys) {
      ProgressManager.checkCanceled()
      val indexEntry = entry as IdIndexEntry
      val matched = masks.containsKey(indexEntry) && acceptsValue(valueChecker, masks[indexEntry])
      if (requireAllKeys && !matched) return false
      if (!requireAllKeys && matched) return true
    }
    return requireAllKeys
  }

  private fun acceptsValue(valueChecker: Condition<*>?, value: Any?): Boolean =
      (valueChecker as Condition<Any?>?)?.value(value) != false

  private fun requireSupported(id: ID<*, *>) {
    if (id != FilenameIndex.NAME && id != FileTypeIndex.NAME && id != IdIndex.NAME) {
      throw UnsupportedOperationException("Analysis content index is not implemented: $id")
    }
    if (id == IdIndex.NAME && !IdIndexEntry.useStrongerHash()) {
      throw UnsupportedOperationException("Analysis IdIndex requires the official trigram index when strong hashes are disabled")
    }
  }

  private fun requireNoIdFilter(filter: IdFilter?) {
    if (filter != null) throw UnsupportedOperationException("Core VFS does not provide persistent file IDs for IdFilter")
  }

  private fun key(id: ID<*, *>, file: VirtualFile): Any? = when (id) {
    FilenameIndex.NAME -> file.name
    FileTypeIndex.NAME -> if (file.isDirectory) null else file.fileType
    else -> throw UnsupportedOperationException("Analysis content index is not implemented: $id")
  }

  private fun matches(
      id: ID<*, *>,
      project: Project,
      file: VirtualFile,
      keys: Set<*>,
      valueChecker: Condition<*>?,
      requireAllKeys: Boolean,
      stats: IdQueryStats? = null,
  ): Boolean {
    if (id == IdIndex.NAME) {
      return matchesId(idSnapshot(project, file, stats).masks, keys, valueChecker, requireAllKeys)
    }
    val actualKey = key(id, file) ?: return false
    return (!requireAllKeys || keys.size == 1) && actualKey in keys && acceptsValue(valueChecker, null)
  }

  private fun process(
      id: ID<*, *>,
      scope: GlobalSearchScope,
      keys: Collection<*>,
      processor: Processor<in VirtualFile>,
      valueChecker: Condition<*>? = null,
      requireAllKeys: Boolean = true,
  ): Boolean {
    requireSupported(id)
    val distinctKeys = keys.toSet()
    if (distinctKeys.isEmpty()) return true
    val names = if (id == FilenameIndex.NAME) distinctKeys.map { it as String }.toSet() else null
    val emitted = if (scope.project == null) HashSet<VirtualFile>() else null
    return traceIdQuery(
        id == IdIndex.NAME, if (requireAllKeys) "allKeys" else "anyKey", scope.project, scope,
        { "index=$id keys=${distinctKeys.take(8)} keyCount=${distinctKeys.size} checker=${valueChecker?.javaClass?.name}" },
    ) { stats ->
      if (id == IdIndex.NAME) {
        processIdQueryFiles(scope, distinctKeys as Set<IdIndexEntry>, requireAllKeys, stats) { _, file, masks ->
          !matchesId(masks, distinctKeys, valueChecker, requireAllKeys) ||
              (emitted != null && !emitted.add(file)) || processMatched(stats) { processor.process(file) }
        }
      } else {
        processProjectFiles(scope.project, scope, names, stats = stats) { project, file ->
          !matches(id, project, file, distinctKeys, valueChecker, requireAllKeys, stats) ||
              (emitted != null && !emitted.add(file)) || processMatched(stats) { processor.process(file) }
        }
      }
    }
  }

  override fun iterateIndexableFiles(processor: ContentIterator, project: Project, indicator: ProgressIndicator?) {
    for (files in fileSets(project)) {
      if (!files.process(null, null, Processor { file ->
            indicator?.checkCanceled()
            processor.processFile(file)
          })) return
    }
  }

  override fun getFileBeingCurrentlyIndexed(): VirtualFile? = null

  override fun getFileWritingCurrentlyIndexes(): IndexWritingFile? = null

  override fun findFileById(project: Project?, id: Int): VirtualFile? =
      throw UnsupportedOperationException("Core VFS does not provide persistent file IDs")

  override fun <K, V> getValues(indexId: ID<K, V>, dataKey: K & Any, filter: GlobalSearchScope): List<V> {
    requireSupported(indexId)
    if (indexId != IdIndex.NAME) {
      val found = !process(indexId, filter, listOf(dataKey), Processor { false })
      return if (found) listOf(null as V) else emptyList()
    }
    val result = LinkedHashSet<V>()
    processValues(indexId, dataKey, null, ValueProcessor { _, value -> result.add(value); true }, filter)
    return result.toList()
  }

  override fun <K, V> getContainingFiles(indexId: ID<K, V>, dataKey: K & Any, filter: GlobalSearchScope): Collection<VirtualFile> {
    val result = ArrayList<VirtualFile>()
    process(indexId, filter, listOf(dataKey), Processor { result.add(it); true })
    return result
  }

  override fun <K, V> getContainingFilesIterator(indexId: ID<K, V>, dataKey: K & Any, filter: GlobalSearchScope): Iterator<VirtualFile> =
      getContainingFiles(indexId, dataKey, filter).iterator()

  override fun <K, V> processValues(
      indexId: ID<K, V>, dataKey: K & Any, inFile: VirtualFile?, processor: ValueProcessor<in V>, filter: GlobalSearchScope,
  ): Boolean = processValues(indexId, dataKey, inFile, processor, filter, null)

  override fun <K, V> processValues(
      indexId: ID<K, V>, dataKey: K & Any, inFile: VirtualFile?, processor: ValueProcessor<in V>,
      filter: GlobalSearchScope, idFilter: IdFilter?,
  ): Boolean {
    requireSupported(indexId)
    requireNoIdFilter(idFilter)
    val names = if (indexId == FilenameIndex.NAME) setOf(dataKey as String) else null
    val emitted = if (filter.project == null) HashSet<Pair<VirtualFile, Any?>>() else null
    return traceIdQuery(
        indexId == IdIndex.NAME, "values", filter.project, filter,
        { "index=$indexId key=$dataKey singleFile=${inFile != null}" },
    ) { stats ->
      if (indexId == IdIndex.NAME && inFile == null) {
        val entry = dataKey as IdIndexEntry
        processIdQueryFiles(filter, setOf(entry), true, stats) { _, file, masks ->
          if (!masks.containsKey(entry)) true
          else {
            val value = masks.getValue(entry)
            (emitted != null && !emitted.add(file to value)) || processMatched(stats) { processor.process(file, value as V) }
          }
        }
      } else {
        processProjectFiles(filter.project, filter, names, inFile, stats) { project, file ->
          if (indexId == IdIndex.NAME) {
            val masks = idSnapshot(project, file, stats).masks
            val entry = dataKey as IdIndexEntry
            if (!masks.containsKey(entry)) return@processProjectFiles true
            val value = masks[entry]
            (emitted != null && !emitted.add(file to value)) || processMatched(stats) { processor.process(file, value as V) }
          } else {
            key(indexId, file) != dataKey || (emitted != null && !emitted.add(file to null)) || processor.process(file, null as V)
          }
        }
      }
    }
  }

  override fun <K, V> getIndexModificationStamp(indexId: ID<K, V>, project: Project): Long {
    requireSupported(indexId)
    return fileSets(project).sumOf { it.modificationStamp }
  }

  override fun <K, V> processFilesContainingAllKeys(
      indexId: ID<K, V>, dataKeys: Collection<K>, filter: GlobalSearchScope,
      valueChecker: Condition<in V>?, processor: Processor<in VirtualFile>,
  ): Boolean {
    return process(indexId, filter, dataKeys, processor, valueChecker)
  }

  override fun processFilesContainingAllKeys(
      queries: Collection<AllKeysQuery<*, *>>, filter: GlobalSearchScope, processor: Processor<in VirtualFile>,
  ): Boolean {
    val prepared = queries.map { query ->
      requireSupported(query.indexId)
      Triple(query.indexId, query.dataKeys.toSet(), query.valueChecker)
    }
    if (prepared.isEmpty() || prepared.any { it.second.isEmpty() }) return true
    val names = prepared.firstOrNull { it.first == FilenameIndex.NAME }?.second?.map { it as String }?.toSet()
    val emitted = if (filter.project == null) HashSet<VirtualFile>() else null
    return traceIdQuery(
        prepared.any { it.first == IdIndex.NAME }, "queries", filter.project, filter,
        { prepared.take(8).joinToString { (id, keys, checker) ->
          "index=$id keys=${keys.take(8)} keyCount=${keys.size} checker=${checker?.javaClass?.name}"
        } },
    ) { stats ->
      if (prepared.all { it.first == IdIndex.NAME }) {
        val keys = prepared.flatMapTo(LinkedHashSet()) { it.second as Set<IdIndexEntry> }
        processIdQueryFiles(filter, keys, true, stats) { _, file, masks ->
          val matched = prepared.all { (_, entries, checker) -> matchesId(masks, entries, checker, true) }
          !matched || (emitted != null && !emitted.add(file)) || processMatched(stats) { processor.process(file) }
        }
      } else {
        processProjectFiles(filter.project, filter, names, stats = stats) { project, file ->
          val matched = prepared.all { (id, keys, checker) -> matches(id, project, file, keys, checker, true, stats) }
          !matched || (emitted != null && !emitted.add(file)) || processMatched(stats) { processor.process(file) }
        }
      }
    }
  }

  override fun <K, V> processFilesContainingAnyKey(
      indexId: ID<K, V>, dataKeys: Collection<K>, filter: GlobalSearchScope, idFilter: IdFilter?,
      valueChecker: Condition<in V>?, processor: Processor<in VirtualFile>,
  ): Boolean {
    requireSupported(indexId)
    requireNoIdFilter(idFilter)
    if (dataKeys.isEmpty()) return true
    return process(indexId, filter, dataKeys, processor, valueChecker, requireAllKeys = false)
  }

  override fun <K> getAllKeys(indexId: ID<K, *>, project: Project): Collection<K> {
    val result = LinkedHashSet<K>()
    processAllKeys(indexId, Processor { result.add(it); true }, project)
    return result
  }

  override fun <K> processAllKeys(indexId: ID<K, *>, processor: Processor<in K>, project: Project?): Boolean =
      processKeys(indexId, processor, project, null)

  private fun <K> processKeys(
      indexId: ID<K, *>, processor: Processor<in K>, project: Project?, scope: GlobalSearchScope?,
  ): Boolean {
    requireSupported(indexId)
    val seen = HashSet<K>()
    return traceIdQuery(indexId == IdIndex.NAME, "keys", project, scope, { "index=$indexId" }) { stats ->
      processProjectFiles(project, scope, stats = stats) { owner, file ->
        val keys = if (indexId == IdIndex.NAME) idSnapshot(owner, file, stats).masks.keys
        else listOfNotNull(key(indexId, file))
        keys.all { value ->
          ProgressManager.checkCanceled()
          val dataKey = value as K
          return@all !seen.add(dataKey) || processor.process(dataKey)
        }
      }
    }
  }

  override fun <K> processAllKeys(
      indexId: ID<K, *>, processor: Processor<in K>, scope: GlobalSearchScope, idFilter: IdFilter?,
  ): Boolean {
    requireNoIdFilter(idFilter)
    return processKeys(indexId, processor, scope.project, scope)
  }

  override fun <K> ensureUpToDate(indexId: ID<K, *>, project: Project?, filter: GlobalSearchScope?) {
    requireSupported(indexId)
    fileSets(project ?: filter?.project).forEach { it.ensureUpToDate(filter) }
  }

  override fun requestRebuild(indexId: ID<*, *>, throwable: Throwable) {
    requireSupported(indexId)
    invalidateCaches()
  }

  override fun <K> scheduleRebuild(indexId: ID<K, *>, e: Throwable) = requestRebuild(indexId, e)

  override fun requestReindex(file: VirtualFile) {
    for ((project, files) in projects) {
      files.invalidate()
      idSnapshots[project]?.remove(snapshotKey(files, file))
    }
  }

  override fun invalidateCaches() {
    projects.values.forEach { it.invalidate() }
    idSnapshots.values.forEach { it.clear() }
  }

  override fun <K, V> getFilesWithKey(
      indexId: ID<K, V>, dataKeys: Set<K>, processor: Processor<in VirtualFile>, filter: GlobalSearchScope,
  ): Boolean = processFilesContainingAllKeys(indexId, dataKeys, filter, null, processor)

  override fun <K, V> getFileData(id: ID<K, V>, virtualFile: VirtualFile, project: Project): Map<K, V> {
    requireSupported(id)
    if (!virtualFile.isValid || fileSets(project).none { it.contains(virtualFile) }) return emptyMap()
    if (id == IdIndex.NAME) {
      return idSnapshot(project, virtualFile).masks as Map<K, V>
    }
    val value = key(id, virtualFile) ?: return emptyMap()
    return mapOf((value as K) to (null as V))
  }

  override fun <V> getSingleEntryIndexData(id: ID<Int, V>, virtualFile: VirtualFile, project: Project): V? =
      throw UnsupportedOperationException("Analysis single-entry content index is not implemented: $id")
}
