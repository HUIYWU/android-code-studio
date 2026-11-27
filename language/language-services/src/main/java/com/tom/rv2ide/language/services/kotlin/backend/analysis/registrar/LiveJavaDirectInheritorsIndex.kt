package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaClassFileType
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiAnonymousClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

// TODO(ACS-ANALYSIS-EXPERIMENT): Validate direct-inheritor candidates, binary member classes, and live-to-disk transitions on device.
internal class LiveJavaDirectInheritorsIndex(
    private val project: Project,
    private val files: LiveProjectFiles,
    private val registry: LiveSourceFileRegistry,
) : AutoCloseable {
  class Candidate(
      val file: VirtualFile,
      val sourceOrdinal: Int,
      val memberNames: List<String>,
      val superNames: Set<String>,
      val anonymous: Boolean,
      val className: String?,
      val staticMembers: Set<String> = emptySet(),
  )

  private class State(
      val fileSetRevision: Long,
      var mappingRevision: Long,
      var active: Map<Path, VirtualFile>,
      val byFile: MutableMap<String, List<Candidate>>,
      val bySuperName: MutableMap<String, MutableSet<Candidate>>,
      val byClassName: MutableMap<String, MutableSet<Candidate>>,
      val byStaticMemberName: MutableMap<String, MutableSet<Candidate>>,
  )

  private class IndexStats {
    var indexabilityChecks: Int = 0
    var indexabilityUs: Long = 0L
    var sourceFiles: Int = 0
    var sourceCandidates: Int = 0
    var sourceCollectUs: Long = 0L
    var binaryFiles: Int = 0
    var binaryCandidates: Int = 0
    var binaryCollectUs: Long = 0L
  }

  private data class Member(val outerName: String?, val innerName: String?)
  private val lock = Any()
  private var state: State? = null
  private var closed = false

  fun candidates(name: String): List<Candidate> = synchronized(lock) {
    check(!closed) { "Analysis direct-inheritor index is closed" }
    refresh().bySuperName[name]?.toList().orEmpty()
  }

  fun classesByName(name: String): List<Candidate> = synchronized(lock) {
    check(!closed) { "Analysis direct-inheritor index is closed" }
    refresh().byClassName[name]?.toList().orEmpty()
  }

  fun staticMemberOwners(name: String, scope: GlobalSearchScope?): List<Candidate> = synchronized(lock) {
    check(!closed) { "Analysis direct-inheritor index is closed" }
    val current = refresh()
    val owners = LinkedHashSet<Candidate>()
    val pending = ArrayDeque<String>()
    current.byStaticMemberName[name].orEmpty().forEach { owner ->
      if (scope == null || scope.contains(owner.file)) {
        owners.add(owner)
        owner.className?.let(pending::addLast)
      }
    }
    val visitedNames = HashSet<String>()
    while (pending.isNotEmpty()) {
      ProgressManager.checkCanceled()
      val superName = pending.removeFirst()
      if (!visitedNames.add(superName)) continue
      for (candidate in current.bySuperName[superName].orEmpty()) {
        if (scope != null && !scope.contains(candidate.file)) continue
        if (owners.add(candidate)) candidate.className?.let(pending::addLast)
      }
    }
    owners.toList()
  }

  fun classNames(scope: GlobalSearchScope?): List<String> = synchronized(lock) {
    check(!closed) { "Analysis direct-inheritor index is closed" }
    refresh().byClassName.entries.mapNotNull { (name, candidates) ->
      ProgressManager.checkCanceled()
      if (scope == null || candidates.any { scope.contains(it.file) }) name else null
    }
  }

  fun materialize(candidate: Candidate, cache: MutableMap<VirtualFile, List<PsiClass>>): PsiClass {
    val classes = cache.getOrPut(candidate.file) {
      val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(candidate.file)) {
        "Analysis inheritor candidate PSI is missing: ${candidate.file.path}"
      }
      if (candidate.sourceOrdinal >= 0) sourceClasses(psiFile)
      else (psiFile as PsiClassOwner).classes.toList()
    }
    if (candidate.sourceOrdinal >= 0) return classes[candidate.sourceOrdinal]
    var klass = classes.single { it.name == candidate.memberNames.first() }
    for (name in candidate.memberNames.drop(1)) {
      klass = klass.innerClasses.single { it.name == name }
    }
    return klass
  }

  private fun refresh(): State {
    val fileSetRevision = files.fileSetRevision
    val mappingRevision = registry.fileMappingRevision
    val current = state
    if (current != null && current.fileSetRevision == fileSetRevision && current.mappingRevision == mappingRevision) {
      return current
    }
    val startedAt = System.nanoTime()
    val activeStartedAt = System.nanoTime()
    val active = registry.virtualFilesSnapshot().filterValues { it.fileType == JavaFileType.INSTANCE }
    val activeUs = (System.nanoTime() - activeStartedAt) / 1_000L
    if (current == null || current.fileSetRevision != fileSetRevision) {
      val byFile = HashMap<String, List<Candidate>>()
      val processStats = LiveProjectFiles.ProcessStats()
      val indexStats = IndexStats()
      val processStartedAt = System.nanoTime()
      files.processWithStats(GlobalSearchScope.allScope(project), Processor { file ->
        ProgressManager.checkCanceled()
        val indexableStartedAt = System.nanoTime()
        val indexable = isIndexable(file)
        indexStats.indexabilityChecks++
        indexStats.indexabilityUs += (System.nanoTime() - indexableStartedAt) / 1_000L
        if (indexable) {
          val collectStartedAt = System.nanoTime()
          val values = collect(file)
          if (file.fileType == JavaFileType.INSTANCE) indexStats.sourceFiles++ else indexStats.binaryFiles++
          byFile[fileKey(file)] = values
          if (file.fileType == JavaFileType.INSTANCE) indexStats.sourceCandidates += values.size
          else indexStats.binaryCandidates += values.size
          indexStats.sourceCollectUs += if (file.fileType == JavaFileType.INSTANCE) {
            (System.nanoTime() - collectStartedAt) / 1_000L
          } else 0L
          indexStats.binaryCollectUs += if (file.fileType == JavaClassFileType.INSTANCE) {
            (System.nanoTime() - collectStartedAt) / 1_000L
          } else 0L
        }
        true
      }, processStats)
      val processUs = (System.nanoTime() - processStartedAt) / 1_000L
      val rebuilt = State(fileSetRevision, mappingRevision, active, byFile, HashMap(), HashMap(), HashMap())
      val mapStartedAt = System.nanoTime()
      byFile.values.forEach { values -> values.forEach { add(rebuilt, it) } }
      val mapUs = (System.nanoTime() - mapStartedAt) / 1_000L
      state = rebuilt
      KlsLogs.info(
          "Java direct inheritors index: action=rebuild, project={}, fileSetRevision={}, mappingRevision={}, files={}, candidates={}, durationUs={}, activeFiles={}, activeUs={}, processUs={}, mapUs={}, roots={}, snapshotHits={}, snapshotBuilds={}, snapshotBuildUs={}, entriesSeen={}, duplicateEntries={}, activeOverrides={}, missingVirtualFiles={}, scopeRejected={}, activeScopeRejected={}, processed={}, directories={}, javaSources={}, javaClasses={}, otherFiles={}, indexabilityChecks={}, indexabilityUs={}, sourceFiles={}, sourceCandidates={}, sourceCollectUs={}, binaryFiles={}, binaryCandidates={}, binaryCollectUs={}",
          System.identityHashCode(project), fileSetRevision, mappingRevision, byFile.size, byFile.values.sumOf { it.size },
          (System.nanoTime() - startedAt) / 1_000L, active.size, activeUs, processUs, mapUs,
          processStats.roots, processStats.snapshotHits, processStats.snapshotBuilds,
          processStats.snapshotBuildUs, processStats.entriesSeen, processStats.duplicateEntries,
          processStats.activeOverrides,
          processStats.missingVirtualFiles, processStats.scopeRejected, processStats.activeScopeRejected,
          processStats.processed, processStats.directories, processStats.javaSources,
          processStats.javaClasses, processStats.otherFiles, indexStats.indexabilityChecks,
          indexStats.indexabilityUs, indexStats.sourceFiles, indexStats.sourceCandidates,
          indexStats.sourceCollectUs, indexStats.binaryFiles, indexStats.binaryCandidates,
          indexStats.binaryCollectUs,
      )
      return rebuilt
    }
    val changed = (current.active.keys + active.keys).filter { current.active[it] !== active[it] }
    val replacements = HashMap<String, List<Candidate>>()
    val indexStats = IndexStats()
    val collectStartedAt = System.nanoTime()
    for (path in changed) {
      ProgressManager.checkCanceled()
      val file = active[path] ?: if (Files.isRegularFile(path)) {
        requireNotNull(StandardFileSystems.local().findFileByPath(path.toString()))
      } else null
      val indexableStartedAt = System.nanoTime()
      val indexable = file != null && isIndexable(file)
      indexStats.indexabilityChecks++
      indexStats.indexabilityUs += (System.nanoTime() - indexableStartedAt) / 1_000L
      val values = if (indexable) collect(file!!) else emptyList()
      if (file?.fileType == JavaFileType.INSTANCE) {
        indexStats.sourceFiles++
        indexStats.sourceCandidates += values.size
      } else if (file?.fileType == JavaClassFileType.INSTANCE) {
        indexStats.binaryFiles++
        indexStats.binaryCandidates += values.size
      }
      replacements[path.toString()] = values
    }
    val collectUs = (System.nanoTime() - collectStartedAt) / 1_000L
    for ((key, values) in replacements) {
      current.byFile.remove(key)?.forEach { remove(current, it) }
      if (values.isNotEmpty()) current.byFile[key] = values
      values.forEach { add(current, it) }
    }
    current.active = active
    current.mappingRevision = mappingRevision
    KlsLogs.info(
        "Java direct inheritors index: action=refresh, project={}, fileSetRevision={}, mappingRevision={}, changedFiles={}, durationUs={}, activeFiles={}, activeUs={}, collectUs={}, indexabilityChecks={}, indexabilityUs={}, sourceFiles={}, sourceCandidates={}, binaryFiles={}, binaryCandidates={}",
        System.identityHashCode(project), fileSetRevision, mappingRevision, changed.size, (System.nanoTime() - startedAt) / 1_000L,
        active.size, activeUs, collectUs, indexStats.indexabilityChecks, indexStats.indexabilityUs,
        indexStats.sourceFiles, indexStats.sourceCandidates, indexStats.binaryFiles,
        indexStats.binaryCandidates,
    )
    return current
  }

  private fun fileKey(file: VirtualFile): String = registry.pathForVirtualFile(file)?.toString() ?: file.path

  private fun isIndexable(file: VirtualFile): Boolean {
    if (file.isDirectory) return false
    val fileIndex = ProjectFileIndex.getInstance(project)
    return when (file.fileType) {
      JavaFileType.INSTANCE -> fileIndex.isInSourceContent(file)
      JavaClassFileType.INSTANCE -> fileIndex.isInLibraryClasses(file) &&
          file.name != "module-info.class" && file.name != "package-info.class" &&
          !file.path.contains("/META-INF/versions/")
      else -> false
    }
  }

  private fun collect(file: VirtualFile): List<Candidate> = if (file.fileType == JavaFileType.INSTANCE) {
    val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(file)) {
      "Analysis Java source PSI is missing: ${file.path}"
    }
    sourceClasses(psiFile).mapIndexed { ordinal, klass ->
      val names = linkedSetOf("Object")
      klass.extendsList?.referenceElements?.mapNotNullTo(names) { it.referenceName }
      klass.implementsList?.referenceElements?.mapNotNullTo(names) { it.referenceName }
      if (klass is PsiAnonymousClass) names.add(klass.baseClassType.className)
      if (klass.isEnum) names.add("Enum")
      if (klass.isRecord) names.add("Record")
      if (klass.isAnnotationType) names.add("Annotation")
      val members = LinkedHashSet<String>()
      if (klass.qualifiedName != null) {
        for (method in klass.methods) {
          ProgressManager.checkCanceled()
          if (!method.isConstructor && method.hasModifierProperty(PsiModifier.STATIC) &&
              !method.hasModifierProperty(PsiModifier.PRIVATE)) members.add(method.name)
        }
        for (field in klass.fields) {
          ProgressManager.checkCanceled()
          if (field.hasModifierProperty(PsiModifier.STATIC) && !field.hasModifierProperty(PsiModifier.PRIVATE)) {
            members.add(field.name)
          }
        }
      }
      Candidate(file, ordinal, emptyList(), names, klass is PsiAnonymousClass, klass.name, if (members.isEmpty()) emptySet() else members)
    }
  } else {
    collectBinary(file)
  }

  private fun sourceClasses(file: PsiFile): List<PsiClass> {
    val result = ArrayList<PsiClass>()
    file.accept(object : PsiRecursiveElementWalkingVisitor() {
      override fun visitElement(element: PsiElement) {
        ProgressManager.checkCanceled()
        if (element is PsiClass && element !is PsiTypeParameter) result.add(element)
        super.visitElement(element)
      }
    })
    return result
  }

  private fun collectBinary(file: VirtualFile): List<Candidate> {
    val reader = ClassReader(file.contentsToByteArray())
    if (reader.access and Opcodes.ACC_SYNTHETIC != 0) return emptyList()
    val members = HashMap<String, Member>()
    val staticMembers = LinkedHashSet<String>()
    fun readNesting(classReader: ClassReader): Boolean {
      var enclosingClass = false
      classReader.accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
          enclosingClass = true
        }

        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
          members[name] = Member(outerName, innerName)
        }

        override fun visitMethod(
            access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?,
        ): org.objectweb.asm.MethodVisitor? {
          if (classReader === reader && access and Opcodes.ACC_STATIC != 0 &&
              access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNTHETIC) == 0 && !name.startsWith("<")) {
            staticMembers.add(name)
          }
          return null
        }

        override fun visitField(
            access: Int, name: String, descriptor: String, signature: String?, value: Any?,
        ): org.objectweb.asm.FieldVisitor? {
          if (classReader === reader && access and Opcodes.ACC_STATIC != 0 &&
              access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNTHETIC) == 0) {
            staticMembers.add(name)
          }
          return null
        }
      }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
      return enclosingClass
    }
    if (readNesting(reader)) return emptyList()
    var owner = reader.className
    val memberNames = ArrayList<String>()
    val seen = HashSet<String>()
    while (true) {
      check(seen.add(owner)) { "Cyclic binary nesting: ${file.path}" }
      if (owner != reader.className && owner !in members) {
        val outerFile = requireNotNull(file.parent.findChild(owner.substringAfterLast('/') + ".class")) {
          "Binary inheritor owner is missing: ${file.path}, owner=$owner"
        }
        val outerReader = ClassReader(outerFile.contentsToByteArray())
        check(outerReader.className == owner) { "Binary inheritor owner name mismatch: ${outerFile.path}" }
        if (readNesting(outerReader)) return emptyList()
      }
      val member = members[owner] ?: break
      val innerName = member.innerName ?: return emptyList()
      val outerName = member.outerName ?: return emptyList()
      memberNames.add(innerName)
      owner = outerName
    }
    memberNames.add(owner.substringAfterLast('/'))
    memberNames.reverse()
    val ownerFile = if (owner == reader.className) file else requireNotNull(
        file.parent.findChild(owner.substringAfterLast('/') + ".class")
    ) { "Binary inheritor owner is missing: ${file.path}, owner=$owner" }
    val names = linkedSetOf<String>()
    fun addSuper(name: String) {
      val memberName = members[name]?.innerName
      if (memberName != null) {
        names.add(memberName)
      } else {
        val jvmName = name.substringAfterLast('/')
        names.add(jvmName)
        if ('$' in jvmName) names.add(jvmName.substringAfterLast('$'))
      }
    }
    reader.superName?.let(::addSuper)
    reader.interfaces.forEach(::addSuper)
    return listOf(Candidate(ownerFile, -1, memberNames, names, false, memberNames.last(), if (staticMembers.isEmpty()) emptySet() else staticMembers))
  }

  private fun add(target: State, candidate: Candidate) {
    candidate.superNames.forEach { name -> target.bySuperName.getOrPut(name) { linkedSetOf() }.add(candidate) }
    candidate.className?.let { name -> target.byClassName.getOrPut(name) { linkedSetOf() }.add(candidate) }
    candidate.staticMembers.forEach { name -> target.byStaticMemberName.getOrPut(name) { linkedSetOf() }.add(candidate) }
  }

  private fun remove(target: State, candidate: Candidate) {
    candidate.superNames.forEach { name ->
      target.bySuperName[name]?.let { values ->
        values.remove(candidate)
        if (values.isEmpty()) target.bySuperName.remove(name)
      }
    }
    candidate.className?.let { name ->
      target.byClassName[name]?.let { values ->
        values.remove(candidate)
        if (values.isEmpty()) target.byClassName.remove(name)
      }
    }
    candidate.staticMembers.forEach { name ->
      target.byStaticMemberName[name]?.let { values ->
        values.remove(candidate)
        if (values.isEmpty()) target.byStaticMemberName.remove(name)
      }
    }
  }

  override fun close() {
    synchronized(lock) {
      closed = true
      state = null
    }
  }
}
