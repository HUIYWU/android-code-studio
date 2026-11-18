package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.search.FileTypeIndex
import org.jetbrains.kotlin.com.intellij.psi.search.FilenameIndex
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex.IndexWritingFile
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex.ValueProcessor
import org.jetbrains.kotlin.com.intellij.util.indexing.ID
import org.jetbrains.kotlin.com.intellij.util.indexing.IdFilter
import java.util.concurrent.ConcurrentHashMap

// TODO(ACS-ANALYSIS-EXPERIMENT): Replace this non-content index bridge when standalone provides a compatible indexing service.
@Suppress("UnstableApiUsage", "DEPRECATION", "UNCHECKED_CAST")
internal class LiveFileBasedIndex : FileBasedIndex() {
  private val projects = ConcurrentHashMap<Project, LiveProjectFiles>()

  fun bind(project: Project, files: LiveProjectFiles) {
    check(projects.putIfAbsent(project, files) == null) { "Analysis project file set already registered" }
  }

  fun unbind(project: Project) {
    projects.remove(project)
  }

  private fun fileSets(project: Project?): Collection<LiveProjectFiles> =
      if (project == null) projects.values.toList()
      else listOf(checkNotNull(projects[project]) { "Analysis project file set is not registered" })

  private fun requireSupported(id: ID<*, *>) {
    if (id != FilenameIndex.NAME && id != FileTypeIndex.NAME) {
      throw UnsupportedOperationException("Analysis content index is not implemented: $id")
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

  private fun process(
      id: ID<*, *>,
      scope: GlobalSearchScope,
      keys: Collection<*>?,
      processor: Processor<in VirtualFile>,
  ): Boolean {
    requireSupported(id)
    val names = if (id == FilenameIndex.NAME && keys != null) keys.map { it as String }.toSet() else null
    val selected = fileSets(scope.project)
    val seen = if (selected.size > 1) HashSet<VirtualFile>() else null
    for (files in selected) {
      if (!files.process(scope, names, Processor { file ->
            val actualKey = key(id, file)
            if (actualKey == null || (keys != null && actualKey !in keys) || (seen != null && !seen.add(file))) true
            else processor.process(file)
          })) return false
    }
    return true
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
    val found = !process(indexId, filter, listOf(dataKey), Processor { false })
    return if (found) listOf(null as V) else emptyList()
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
    requireNoIdFilter(idFilter)
    return process(indexId, filter, listOf(dataKey), Processor { file ->
      if (inFile == null || inFile == file) processor.process(file, null as V) else true
    })
  }

  override fun <K, V> getIndexModificationStamp(indexId: ID<K, V>, project: Project): Long {
    requireSupported(indexId)
    return fileSets(project).sumOf { it.modificationStamp }
  }

  override fun <K, V> processFilesContainingAllKeys(
      indexId: ID<K, V>, dataKeys: Collection<K>, filter: GlobalSearchScope,
      valueChecker: Condition<in V>?, processor: Processor<in VirtualFile>,
  ): Boolean {
    requireSupported(indexId)
    if (dataKeys.isEmpty() || dataKeys.distinct().size > 1 || valueChecker?.value(null as V) == false) return true
    return process(indexId, filter, dataKeys, processor)
  }

  override fun <K, V> processFilesContainingAnyKey(
      indexId: ID<K, V>, dataKeys: Collection<K>, filter: GlobalSearchScope, idFilter: IdFilter?,
      valueChecker: Condition<in V>?, processor: Processor<in VirtualFile>,
  ): Boolean {
    requireSupported(indexId)
    requireNoIdFilter(idFilter)
    if (dataKeys.isEmpty() || valueChecker?.value(null as V) == false) return true
    return process(indexId, filter, dataKeys, processor)
  }

  override fun <K> getAllKeys(indexId: ID<K, *>, project: Project): Collection<K> {
    val result = LinkedHashSet<K>()
    processAllKeys(indexId, Processor { result.add(it); true }, project)
    return result
  }

  override fun <K> processAllKeys(indexId: ID<K, *>, processor: Processor<in K>, project: Project?): Boolean {
    requireSupported(indexId)
    val seen = HashSet<Any>()
    for (files in fileSets(project)) {
      if (!files.process(null, null, Processor { file ->
            val value = key(indexId, file)
            if (value == null || !seen.add(value)) true else processor.process(value as K)
          })) return false
    }
    return true
  }

  override fun <K> processAllKeys(
      indexId: ID<K, *>, processor: Processor<in K>, scope: GlobalSearchScope, idFilter: IdFilter?,
  ): Boolean {
    requireNoIdFilter(idFilter)
    val seen = HashSet<K>()
    return process(indexId, scope, null, Processor { file ->
      val value = key(indexId, file) as K
      if (seen.add(value)) processor.process(value) else true
    })
  }

  override fun <K> ensureUpToDate(indexId: ID<K, *>, project: Project?, filter: GlobalSearchScope?) {
    requireSupported(indexId)
    fileSets(project ?: filter?.project).forEach { it.ensureUpToDate(filter) }
  }

  override fun requestRebuild(indexId: ID<*, *>, throwable: Throwable) {
    requireSupported(indexId)
    projects.values.forEach { it.invalidate() }
  }

  override fun <K> scheduleRebuild(indexId: ID<K, *>, e: Throwable) = requestRebuild(indexId, e)

  override fun requestReindex(file: VirtualFile) {
    projects.values.forEach { it.invalidate() }
  }

  override fun invalidateCaches() {
    projects.values.forEach { it.invalidate() }
  }

  override fun <K, V> getFilesWithKey(
      indexId: ID<K, V>, dataKeys: Set<K>, processor: Processor<in VirtualFile>, filter: GlobalSearchScope,
  ): Boolean = processFilesContainingAllKeys(indexId, dataKeys, filter, null, processor)

  override fun <K, V> getFileData(id: ID<K, V>, virtualFile: VirtualFile, project: Project): Map<K, V> {
    requireSupported(id)
    if (fileSets(project).none { it.contains(virtualFile) }) return emptyMap()
    val value = key(id, virtualFile) ?: return emptyMap()
    return mapOf((value as K) to (null as V))
  }

  override fun <V> getSingleEntryIndexData(id: ID<Int, V>, virtualFile: VirtualFile, project: Project): V? =
      throw UnsupportedOperationException("Analysis single-entry content index is not implemented: $id")
}
