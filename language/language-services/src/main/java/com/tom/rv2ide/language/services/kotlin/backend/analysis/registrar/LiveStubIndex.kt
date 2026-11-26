package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.com.intellij.util.indexing.IdFilter
import org.jetbrains.kotlin.com.intellij.psi.tree.IFileElementType
import java.util.concurrent.ConcurrentHashMap

internal class LiveStubIndex : StubIndex() {
  private val projects = ConcurrentHashMap<Project, LiveProjectFiles>()

  fun bind(project: Project, files: LiveProjectFiles) {
    check(projects.putIfAbsent(project, files) == null) { "Analysis project stub index already registered" }
  }

  fun unbind(project: Project) {
    projects.remove(project)
  }

  private fun files(project: Project): LiveProjectFiles =
      checkNotNull(projects[project]) { "Analysis project stub index is not registered" }

  private class QueryStats {
    val files = LiveProjectFiles.ProcessStats(timeFiles = true)
    var methodScan = false
    var fileSetRevision = 0L
    var mappingRevision = 0L
    var endFileSetRevision = 0L
    var endMappingRevision = 0L
    var processCompleted: Boolean? = null
    var processNs = 0L
    var candidateNs = 0L
    var psiLookupNs = 0L
    var walkNs = 0L
    var consumerNs = 0L
    var candidates = 0
    var javaFiles = 0
    var psiFiles = 0
    var missingPsi = 0
    var visitedElements = 0L
    var methods = 0
    var nameMatches = 0
    var processorCalls = 0
    var processorStops = 0
  }

  private inline fun <T> traceQuery(
      operation: String, indexKey: StubIndexKey<*, *>, key: Any?, project: Project,
      scope: GlobalSearchScope?, requiredClass: Class<*>?, processor: Any?, action: (QueryStats?) -> T,
  ): T {
    if (!IdeLogConfig.shouldLogInfo()) return action(null)
    val stats = QueryStats()
    val query = System.identityHashCode(stats)
    val startedAt = System.nanoTime()
    var outcome = "failed"
    try {
      return action(stats).also { outcome = "returned" }
    } catch (cancelled: CancellationException) {
      outcome = "cancelled"
      throw cancelled
    } finally {
      val totalNs = System.nanoTime() - startedAt
      KlsLogs.info(
          "Java StubIndex perf: query={}, operation={}, index={}, key={}, project={}, scope={}, libraries={}, requiredClass={}, processor={}, totalUs={}, outcome={}, processCompleted={}",
          query, operation, indexKey, key, System.identityHashCode(project), scope?.javaClass?.name,
          scope?.isSearchInLibraries(), requiredClass?.name, processor?.javaClass?.name,
          totalNs / 1_000L, outcome, stats.processCompleted,
      )
      if (stats.methodScan) logMethodStats(query, project, stats)
    }
  }

  private fun logMethodStats(query: Int, project: Project, stats: QueryStats) {
    KlsLogs.info(
        "Java StubIndex methods perf: query={}, project={}, processUs={}, candidateUs={}, enumerationUs={}, psiLookupUs={}, walkUs={}, consumerUs={}, walkSelfUs={}, otherCandidateUs={}, candidates={}, javaFiles={}, psiFiles={}, missingPsi={}, visitedElements={}, methods={}, nameMatches={}, processorCalls={}, processorStops={}",
        query, System.identityHashCode(project), stats.processNs / 1_000L, stats.candidateNs / 1_000L,
        (stats.processNs - stats.candidateNs) / 1_000L, stats.psiLookupNs / 1_000L, stats.walkNs / 1_000L,
        stats.consumerNs / 1_000L, (stats.walkNs - stats.consumerNs) / 1_000L,
        (stats.candidateNs - stats.psiLookupNs - stats.walkNs) / 1_000L,
        stats.candidates, stats.javaFiles, stats.psiFiles, stats.missingPsi, stats.visitedElements,
        stats.methods, stats.nameMatches, stats.processorCalls, stats.processorStops,
    )
    val files = stats.files
    KlsLogs.info(
        "Java StubIndex files perf: query={}, project={}, fileSetRevision={}, mappingRevision={}, endFileSetRevision={}, endMappingRevision={}, roots={}, rootSnapshotHits={}, rootSnapshotBuilds={}, rootSnapshotBuildUs={}, entriesSeen={}, duplicateEntries={}, activeOverrides={}, missingVirtualFiles={}, scopeRejected={}, activeScopeRejected={}, processed={}, javaSources={}, javaClasses={}, otherFiles={}, directories={}, activeFiles={}, virtualFileUs={}, scopeUs={}, archiveFileHits={}, virtualFileLookups={}",
        query, System.identityHashCode(project), stats.fileSetRevision, stats.mappingRevision,
        stats.endFileSetRevision, stats.endMappingRevision, files.roots, files.snapshotHits,
        files.snapshotBuilds, files.snapshotBuildUs, files.entriesSeen, files.duplicateEntries,
        files.activeOverrides, files.missingVirtualFiles, files.scopeRejected, files.activeScopeRejected,
        files.processed, files.javaSources, files.javaClasses, files.otherFiles, files.directories, files.activeFiles,
        files.virtualFileNs / 1_000L, files.scopeNs / 1_000L, files.archiveFileHits, files.virtualFileLookups,
    )
  }

  private fun requireSupported(indexKey: StubIndexKey<*, *>) {
    if (indexKey !== JavaStubIndexKeys.METHODS && indexKey !== JavaStubIndexKeys.CLASS_SHORT_NAMES) {
      throw UnsupportedOperationException("Analysis stub index is not implemented: $indexKey")
    }
  }

  private fun classes(project: Project): LiveJavaDirectInheritorsIndex =
      checkNotNull(project.getService(LiveJavaDirectInheritorsIndex::class.java))

  override fun <Key, Psi : PsiElement> processElements(
      indexKey: StubIndexKey<Key, Psi>,
      key: Key,
      project: Project,
      scope: GlobalSearchScope?,
      idFilter: IdFilter?,
      requiredClass: Class<Psi>,
      processor: Processor<in Psi>,
  ): Boolean = traceQuery("elements", indexKey, key, project, scope, requiredClass, processor) { stats ->
    processElementsInScope(indexKey, key, project, scope, idFilter, requiredClass, processor, stats)
  }

  private fun <Key, Psi : PsiElement> processElementsInScope(
      indexKey: StubIndexKey<Key, Psi>, key: Key, project: Project, scope: GlobalSearchScope?,
      idFilter: IdFilter?, requiredClass: Class<Psi>, processor: Processor<in Psi>, stats: QueryStats?,
  ): Boolean {
    requireSupported(indexKey)
    if (idFilter != null) {
      throw UnsupportedOperationException("Analysis stub index does not support IdFilter")
    }
    val methodName = key as? String ?: return true
    if (indexKey === JavaStubIndexKeys.CLASS_SHORT_NAMES) {
      val index = classes(project)
      val cache = HashMap<VirtualFile, List<PsiClass>>()
      for (candidate in index.classesByName(methodName)) {
        ProgressManager.checkCanceled()
        if (scope != null && !scope.contains(candidate.file)) continue
        val element = index.materialize(candidate, cache)
        if (requiredClass.isInstance(element) && !processor.process(requiredClass.cast(element))) return false
      }
      return true
    }
    val psiManager = PsiManager.getInstance(project)
    val projectFiles = files(project)
    if (stats != null) {
      stats.methodScan = true
      stats.fileSetRevision = projectFiles.fileSetRevision
      stats.mappingRevision = projectFiles.fileMappingRevision
    }
    val fileProcessor = Processor<VirtualFile> { virtualFile ->
      val candidateStartedAt = if (stats != null) System.nanoTime() else 0L
      if (stats != null) stats.candidates++
      try {
        ProgressManager.checkCanceled()
        if (virtualFile.isDirectory || virtualFile.fileType != JavaFileType.INSTANCE) return@Processor true
        if (stats != null) stats.javaFiles++
        val psiStartedAt = if (stats != null) System.nanoTime() else 0L
        val psiFile = try {
          psiManager.findFile(virtualFile) as? PsiJavaFile
        } finally {
          if (stats != null) stats.psiLookupNs += System.nanoTime() - psiStartedAt
        }
        if (psiFile == null) {
          if (stats != null) stats.missingPsi++
          return@Processor true
        }
        if (stats != null) stats.psiFiles++
        var completed = true
        val walkStartedAt = if (stats != null) System.nanoTime() else 0L
        try {
          psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
              if (!completed) return
              ProgressManager.checkCanceled()
              if (stats != null) stats.visitedElements++
              if (element is PsiMethod) {
                if (stats != null) stats.methods++
                if (element.name == methodName) {
                  if (stats != null) stats.nameMatches++
                  if (requiredClass.isInstance(element)) {
                    val consumerStartedAt = if (stats != null) System.nanoTime() else 0L
                    if (stats != null) stats.processorCalls++
                    try {
                      @Suppress("UNCHECKED_CAST")
                      completed = processor.process(element as Psi)
                    } finally {
                      if (stats != null) stats.consumerNs += System.nanoTime() - consumerStartedAt
                    }
                    if (!completed) {
                      if (stats != null) stats.processorStops++
                      return
                    }
                  }
                }
              }
              super.visitElement(element)
            }
          })
        } finally {
          if (stats != null) stats.walkNs += System.nanoTime() - walkStartedAt
        }
        completed
      } finally {
        if (stats != null) stats.candidateNs += System.nanoTime() - candidateStartedAt
      }
    }
    val processStartedAt = if (stats != null) System.nanoTime() else 0L
    try {
      val completed = if (stats == null) projectFiles.process(scope, null, fileProcessor)
      else projectFiles.processWithStats(scope, fileProcessor, stats.files)
      if (stats != null) stats.processCompleted = completed
      return completed
    } finally {
      if (stats != null) {
        stats.processNs = System.nanoTime() - processStartedAt
        stats.endFileSetRevision = projectFiles.fileSetRevision
        stats.endMappingRevision = projectFiles.fileMappingRevision
      }
    }
  }

  override fun <Key> getAllKeys(indexKey: StubIndexKey<Key, *>, project: Project): Collection<Key> {
    val result = LinkedHashSet<Key>()
    processAllKeys(indexKey, Processor { result.add(it); true }, GlobalSearchScope.allScope(project), null)
    return result
  }

  override fun <Key> processAllKeys(
      indexKey: StubIndexKey<Key, *>,
      processor: Processor<in Key>,
      scope: GlobalSearchScope,
      idFilter: IdFilter?,
  ): Boolean {
    requireSupported(indexKey)
    if (idFilter != null) {
      throw UnsupportedOperationException("Analysis stub index does not support IdFilter")
    }
    val project = requireNotNull(scope.project)
    if (indexKey === JavaStubIndexKeys.CLASS_SHORT_NAMES) {
      for (name in classes(project).classNames(scope)) {
        ProgressManager.checkCanceled()
        @Suppress("UNCHECKED_CAST")
        if (!processor.process(name as Key)) return false
      }
      return true
    }
    val seen = HashSet<String>()
    val psiManager = PsiManager.getInstance(project)
    return files(project).process(scope, null, Processor { virtualFile ->
      ProgressManager.checkCanceled()
      if (virtualFile.isDirectory || virtualFile.fileType != JavaFileType.INSTANCE) return@Processor true
      val psiFile = psiManager.findFile(virtualFile) as? PsiJavaFile ?: return@Processor true
      var completed = true
      psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
          if (!completed) return
          ProgressManager.checkCanceled()
          if (element is PsiMethod && seen.add(element.name)) {
            @Suppress("UNCHECKED_CAST")
            completed = processor.process(element.name as Key)
            if (!completed) return
          }
          super.visitElement(element)
        }
      })
      completed
    })
  }

  override fun <Key> getContainingFilesIterator(
      indexKey: StubIndexKey<Key, *>,
      dataKey: Key,
      project: Project,
      scope: GlobalSearchScope,
  ): Iterator<VirtualFile> = traceQuery("containingFiles", indexKey, dataKey, project, scope, null, null) {
    containingFiles(indexKey, dataKey, project, scope)
  }

  private fun <Key> containingFiles(
      indexKey: StubIndexKey<Key, *>, dataKey: Key, project: Project, scope: GlobalSearchScope,
  ): Iterator<VirtualFile> {
    requireSupported(indexKey)
    val methodName = dataKey as? String ?: return emptyList<VirtualFile>().iterator()
    if (indexKey === JavaStubIndexKeys.CLASS_SHORT_NAMES) {
      return classes(project).classesByName(methodName).asSequence()
          .map { it.file }.filter { scope.contains(it) }.distinct().toList().iterator()
    }
    val result = ArrayList<VirtualFile>()
    val psiManager = PsiManager.getInstance(project)
    files(project).process(scope, null, Processor { virtualFile ->
      ProgressManager.checkCanceled()
      if (virtualFile.isDirectory || virtualFile.fileType != JavaFileType.INSTANCE) return@Processor true
      val psiFile = psiManager.findFile(virtualFile) as? PsiJavaFile ?: return@Processor true
      var found = false
      psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
          if (found) return
          ProgressManager.checkCanceled()
          if (element is PsiMethod && element.name == methodName) {
            found = true
            return
          }
          super.visitElement(element)
        }
      })
      if (found) result += virtualFile
      true
    })
    return result.iterator()
  }

  override fun <Key> getMaxContainingFileCount(
      indexKey: StubIndexKey<Key, *>,
      dataKey: Key,
      project: Project,
      scope: GlobalSearchScope,
  ): Int {
    requireSupported(indexKey)
    return Int.MAX_VALUE
  }

  override fun forceRebuild(e: Throwable) {
    projects.values.forEach { it.invalidate() }
  }

  override fun getPerFileElementTypeModificationTracker(fileElementType: IFileElementType): ModificationTracker =
      ModificationTracker { projects.values.sumOf { it.modificationStamp } }

  override fun getStubIndexModificationTracker(project: Project): ModificationTracker =
      ModificationTracker { files(project).modificationStamp }
}
