package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
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

  private fun requireMethods(indexKey: StubIndexKey<*, *>) {
    if (indexKey !== JavaStubIndexKeys.METHODS) {
      throw UnsupportedOperationException("Analysis stub index is not implemented: $indexKey")
    }
  }

  override fun <Key, Psi : PsiElement> processElements(
      indexKey: StubIndexKey<Key, Psi>,
      key: Key,
      project: Project,
      scope: GlobalSearchScope?,
      idFilter: IdFilter?,
      requiredClass: Class<Psi>,
      processor: Processor<in Psi>,
  ): Boolean {
    requireMethods(indexKey)
    if (idFilter != null) {
      throw UnsupportedOperationException("Analysis stub index does not support IdFilter")
    }
    val methodName = key as? String ?: return true
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
          if (element is PsiMethod && element.name == methodName && requiredClass.isInstance(element)) {
            @Suppress("UNCHECKED_CAST")
            completed = processor.process(element as Psi)
            if (!completed) return
          }
          super.visitElement(element)
        }
      })
      completed
    })
  }

  override fun <Key> getAllKeys(indexKey: StubIndexKey<Key, *>, project: Project): Collection<Key> {
    requireMethods(indexKey)
    val result = LinkedHashSet<Key>()
    files(project).process(null, null, Processor { virtualFile ->
      ProgressManager.checkCanceled()
      if (virtualFile.isDirectory || virtualFile.fileType != JavaFileType.INSTANCE) return@Processor true
      val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: return@Processor true
      psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
          if (element is PsiMethod) {
            @Suppress("UNCHECKED_CAST")
            result += element.name as Key
          }
          super.visitElement(element)
        }
      })
      true
    })
    return result
  }

  override fun <Key> getContainingFilesIterator(
      indexKey: StubIndexKey<Key, *>,
      dataKey: Key,
      project: Project,
      scope: GlobalSearchScope,
  ): Iterator<VirtualFile> {
    requireMethods(indexKey)
    val methodName = dataKey as? String ?: return emptyList<VirtualFile>().iterator()
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
    requireMethods(indexKey)
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
