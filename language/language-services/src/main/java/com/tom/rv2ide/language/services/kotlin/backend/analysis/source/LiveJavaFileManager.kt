package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisWrite
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.liveBackingPath
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.publishModificationEvent
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiModificationTrackerImpl
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage
import org.jetbrains.kotlin.com.intellij.psi.impl.file.PsiPackageImpl
import org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.load.java.JavaClassFinder
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.impl.JavaClassImpl
import org.jetbrains.kotlin.load.java.structure.impl.source.JavaElementSourceFactory
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.jvm.KotlinCliJavaFileManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate
private val liveJavaFileInvalidKey = Key.create<Boolean>("acs.kotlin.live.java.file.invalid")

internal fun invalidateJavaPsiCaches(project: Project) {
  (PsiModificationTracker.getInstance(project) as PsiModificationTrackerImpl)
      .incLanguageModificationCount(JavaLanguage.INSTANCE)
  PsiManager.getInstance(project).dropResolveCaches()
  project.messageBus.syncPublisher(PsiModificationTracker.TOPIC).modificationCountChanged()
}

@OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)
internal class LiveJavaFileManager(
    private val project: Project,
    private val registry: LiveSourceFileRegistry,
    private val delegate: KotlinCliJavaFileManager,
) : KotlinCliJavaFileManager by delegate, AutoCloseable {
  private data class Entry(
      val version: Int,
      val revision: Long,
      val file: PsiJavaFile,
      val diskClassNames: Set<String>,
  )

  private val files = LinkedHashMap<Path, Entry>()
  private val classes = HashMap<String, MutableList<PsiClass>>()

  fun refresh(path: Path) {
    val normalized = path.normalize()
    if (!normalized.toString().endsWith(".java")) return
    val snapshot = FileManager.getActiveDocumentSnapshot(normalized)
    val previous = read { files[normalized] }
    if (snapshot == null && previous == null) return
    if (snapshot != null && previous?.let { it.version == snapshot.version && it.revision == snapshot.revision } == true) return
    ApplicationManager.getApplication().runWriteAction {
      project.analysisWrite {
        val previous = files[normalized]
        if (snapshot == null && previous == null) return@analysisWrite
        if (snapshot != null && previous != null && previous.version == snapshot.version && previous.revision == snapshot.revision) {
          return@analysisWrite
        }
        val current = FileManager.getActiveDocumentSnapshot(normalized)
        if (current?.version != snapshot?.version || current?.revision != snapshot?.revision) return@analysisWrite
        val module = registry.moduleForPath(normalized) ?: return@analysisWrite
        val next = snapshot?.let {
          PsiFileFactory.getInstance(project)
              .createFileFromText(
                  normalized.fileName.toString(), JavaFileType.INSTANCE, it.content, it.revision, true,
              ) as PsiJavaFile
        }
        next?.liveBackingPath = normalized
        if (previous != null) {
          previous.file.putUserData(liveJavaFileInvalidKey, true)
          index(previous.file, add = false)
          files.remove(normalized)
          registry.unregister(normalized)
        }
        if (next != null && snapshot != null) {
          registry.register(normalized, next.viewProvider.virtualFile)
          val diskNames = previous?.diskClassNames ?: diskClassNames(normalized)
          files[normalized] = Entry(snapshot.version, snapshot.revision, next, diskNames)
          index(next, add = true)
        }
        val psiManager = PsiManager.getInstance(project)
        // TODO(ACS-ANALYSIS-EXPERIMENT): Validate disk PSI coherence after saving and closing an active Java document.
        if (next == null && previous != null && Files.isRegularFile(normalized)) {
          val diskVirtualFile = requireNotNull(VirtualFileManager.getInstance().findFileByNioPath(normalized))
          val diskFile = requireNotNull(psiManager.findFile(diskVirtualFile))
          psiManager.reloadFromDisk(diskFile)
          KlsLogs.info("Java live PSI disk reloaded: file={}, psi={}", normalized, System.identityHashCode(diskFile))
        }
        invalidateJavaPsiCaches(project)
        project.publishModificationEvent(KotlinModuleOutOfBlockModificationEvent(module))
      }
    }
  }

  private fun diskClassNames(path: Path): Set<String> {
    val virtualFile = VirtualFileManager.getInstance()
        .findFileByNioPath(path) ?: return emptySet()
    val file = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: return emptySet()
    return file.classes.mapNotNullTo(HashSet()) { it.qualifiedName }
  }

  private fun index(file: PsiJavaFile, add: Boolean) {
    fun visit(klass: PsiClass) {
      klass.qualifiedName?.let { name ->
        if (add) {
          classes.getOrPut(name) { ArrayList(1) }.add(klass)
        } else {
          classes[name]?.let { values ->
            values.remove(klass)
            if (values.isEmpty()) classes.remove(name)
          }
        }
      }
      klass.innerClasses.forEach(::visit)
    }
    file.classes.forEach(::visit)
  }

  private fun <T> read(action: () -> T): T =
      ApplicationManager.getApplication().runReadAction(Computable { project.analysisRead(action) })

  /** Returns the active PSI for [path]. Callers must already hold the analysis read lock. */
  fun currentFile(path: Path): PsiJavaFile? = files[path.normalize()]?.file

  fun currentFile(path: Path, version: Int, revision: Long): PsiJavaFile? =
      files[path.normalize()]
          ?.takeIf { it.version == version && it.revision == revision }
          ?.file

  fun isShadowed(file: PsiFile): Boolean = read {
    if (file.getUserData(liveJavaFileInvalidKey) == true) return@read true
    val virtualFile = file.viewProvider.virtualFile
    if (registry.pathForVirtualFile(virtualFile)?.let { files[it]?.file === file } == true) {
      false
    } else {
      runCatching { files.containsKey(Path.of(virtualFile.path).normalize()) }.getOrDefault(false)
    }
  }

  fun packageFiles(packageName: String, scope: GlobalSearchScope): List<PsiJavaFile> = read {
    files.values.map { it.file }
        .filter { it.packageName == packageName && scope.contains(it.viewProvider.virtualFile) }
  }

  fun subPackageNames(packageName: String, scope: GlobalSearchScope): Set<String> = read {
    val prefix = if (packageName.isEmpty()) "" else "$packageName."
    files.values.asSequence().map { it.file }
        .filter { scope.contains(it.viewProvider.virtualFile) }
        .map { it.packageName }
        .filter { it.startsWith(prefix) && it.length > prefix.length }
        .map { prefix + it.removePrefix(prefix).substringBefore('.') }
        .toSet()
  }

  override fun findClass(qualifiedName: String, scope: GlobalSearchScope): PsiClass? =
      findClasses(qualifiedName, scope).firstOrNull()

  override fun findClasses(qualifiedName: String, scope: GlobalSearchScope): Array<PsiClass> = read {
    val active = classes[qualifiedName].orEmpty()
        .filter { !isShadowed(it.containingFile) && scope.contains(it.containingFile.viewProvider.virtualFile) }
    val disk = delegate.findClasses(qualifiedName, scope).filterNot { isShadowed(it.containingFile) }
    (active + disk).toTypedArray()
  }

  override fun hasClass(qualifiedName: String, scope: GlobalSearchScope, filter: Predicate<PsiClass>): Boolean =
      findClasses(qualifiedName, scope).any { filter.test(it) }

  override fun findClass(request: JavaClassFinder.Request, searchScope: GlobalSearchScope): JavaClass? = read {
    val name = request.classId.asSingleFqName().asString()
    val active = classes[name].orEmpty()
        .firstOrNull { !isShadowed(it.containingFile) && searchScope.contains(it.containingFile.viewProvider.virtualFile) }
    if (active != null) {
      return@read JavaClassImpl(JavaElementSourceFactory.getInstance(project).createPsiSource(active))
    }
    val filteredScope = object : GlobalSearchScope(project) {
      override fun contains(file: VirtualFile): Boolean =
          searchScope.contains(file) &&
              !runCatching { files.containsKey(Path.of(file.path).normalize()) }.getOrDefault(false)

      override fun isSearchInModuleContent(module: Module): Boolean =
          searchScope.isSearchInModuleContent(module)

      override fun isSearchInLibraries(): Boolean = searchScope.isSearchInLibraries()
    }
    delegate.findClass(request, filteredScope)
  }

  override fun findPackage(packageName: String): PsiPackage? = read {
    val active = files.values.any {
      val name = it.file.packageName
      name == packageName || packageName.isEmpty() || name.startsWith("$packageName.")
    }
    if (active) PsiPackageImpl(PsiManager.getInstance(project), packageName)
    else delegate.findPackage(packageName)
  }

  override fun knownClassNamesInPackage(packageFqName: FqName): Set<String> = read {
    val allScope = GlobalSearchScope.allScope(project)
    val shadowedNames = files.values.flatMapTo(HashSet()) { it.diskClassNames }
    val names = delegate.knownClassNamesInPackage(packageFqName).orEmpty().filterTo(HashSet()) { name ->
      val qualifiedName = if (packageFqName.isRoot) name else "${packageFqName.asString()}.$name"
      if (qualifiedName !in shadowedNames) {
        true
      } else {
        delegate.findClasses(qualifiedName, allScope).any { !isShadowed(it.containingFile) }
      }
    }
    files.values.forEach { entry ->
      if (entry.file.packageName == packageFqName.asString()) {
        entry.file.classes.mapNotNullTo(names) { it.name }
      }
    }
    names
  }

  fun removeUnder(root: Path) {
    val normalized = root.normalize()
    ApplicationManager.getApplication().runWriteAction {
      project.analysisWrite {
        val paths = files.keys.filter { it == normalized || it.startsWith(normalized) }
        val modules = paths.mapNotNull(registry::moduleForPath).distinct()
        paths.forEach { path ->
          files.remove(path)?.let {
            it.file.putUserData(liveJavaFileInvalidKey, true)
            index(it.file, add = false)
          }
          registry.unregister(path)
        }
        if (paths.isNotEmpty()) {
          invalidateJavaPsiCaches(project)
          modules.forEach { project.publishModificationEvent(KotlinModuleOutOfBlockModificationEvent(it)) }
        }
      }
    }
  }

  override fun close() {
    project.analysisWrite {
      files.values.forEach { it.file.putUserData(liveJavaFileInvalidKey, true) }
      files.keys.forEach(registry::unregister)
      project.messageBus.syncPublisher(PsiModificationTracker.TOPIC).modificationCountChanged()
      files.clear()
      classes.clear()
    }
  }
}
