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
  data class Candidate(
      val file: VirtualFile,
      val sourceOrdinal: Int,
      val memberNames: List<String>,
      val superNames: Set<String>,
      val anonymous: Boolean,
  )

  private class State(
      val fileSetRevision: Long,
      var mappingRevision: Long,
      var active: Map<Path, VirtualFile>,
      val byFile: MutableMap<String, List<Candidate>>,
      val bySuperName: MutableMap<String, MutableSet<Candidate>>,
  )

  private data class Member(val outerName: String?, val innerName: String?)
  private val lock = Any()
  private var state: State? = null
  private var closed = false

  fun candidates(name: String): List<Candidate> = synchronized(lock) {
    check(!closed) { "Analysis direct-inheritor index is closed" }
    refresh().bySuperName[name]?.toList().orEmpty()
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
    val active = registry.virtualFilesSnapshot().filterValues { it.fileType == JavaFileType.INSTANCE }
    if (current == null || current.fileSetRevision != fileSetRevision) {
      val byFile = HashMap<String, List<Candidate>>()
      files.process(GlobalSearchScope.allScope(project), null, Processor { file ->
        ProgressManager.checkCanceled()
        if (isIndexable(file)) byFile[fileKey(file)] = collect(file)
        true
      })
      val rebuilt = State(fileSetRevision, mappingRevision, active, byFile, HashMap())
      byFile.values.forEach { values -> values.forEach { add(rebuilt, it) } }
      state = rebuilt
      KlsLogs.info(
          "Java direct inheritors index: action=rebuild, project={}, files={}, candidates={}, durationUs={}",
          System.identityHashCode(project), byFile.size, byFile.values.sumOf { it.size },
          (System.nanoTime() - startedAt) / 1_000L,
      )
      return rebuilt
    }
    val changed = (current.active.keys + active.keys).filter { current.active[it] !== active[it] }
    val replacements = HashMap<String, List<Candidate>>()
    for (path in changed) {
      ProgressManager.checkCanceled()
      val file = active[path] ?: if (Files.isRegularFile(path)) {
        requireNotNull(StandardFileSystems.local().findFileByPath(path.toString()))
      } else null
      replacements[path.toString()] = if (file != null && isIndexable(file)) collect(file) else emptyList()
    }
    for ((key, values) in replacements) {
      current.byFile.remove(key)?.forEach { remove(current, it) }
      if (values.isNotEmpty()) current.byFile[key] = values
      values.forEach { add(current, it) }
    }
    current.active = active
    current.mappingRevision = mappingRevision
    KlsLogs.info(
        "Java direct inheritors index: action=refresh, project={}, changedFiles={}, durationUs={}",
        System.identityHashCode(project), changed.size, (System.nanoTime() - startedAt) / 1_000L,
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
      Candidate(file, ordinal, emptyList(), names, klass is PsiAnonymousClass)
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
    fun readNesting(classReader: ClassReader): Boolean {
      var enclosingClass = false
      classReader.accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
          enclosingClass = true
        }

        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
          members[name] = Member(outerName, innerName)
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
    return listOf(Candidate(ownerFile, -1, memberNames, names, false))
  }

  private fun add(target: State, candidate: Candidate) {
    candidate.superNames.forEach { name -> target.bySuperName.getOrPut(name) { linkedSetOf() }.add(candidate) }
  }

  private fun remove(target: State, candidate: Candidate) {
    candidate.superNames.forEach { name ->
      target.bySuperName[name]?.let { values ->
        values.remove(candidate)
        if (values.isEmpty()) target.bySuperName.remove(name)
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
