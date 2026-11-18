package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiDirectory
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFinderImpl
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope

internal class LiveJavaElementFinder(
    project: Project,
    private val manager: LiveJavaFileManager,
) : PsiElementFinder() {
  private val delegate = PsiElementFinderImpl(project)

  override fun findClass(qualifiedName: String, scope: GlobalSearchScope): PsiClass? {
    val candidate = manager.findClass(qualifiedName, scope)
    return candidate?.takeUnless { manager.isShadowed(it.containingFile) }
  }

  override fun findClasses(qualifiedName: String, scope: GlobalSearchScope): Array<PsiClass> =
      manager.findClasses(qualifiedName, scope).filterNot { manager.isShadowed(it.containingFile) }.toTypedArray()

  override fun findPackage(qualifiedName: String): PsiPackage? = manager.findPackage(qualifiedName)

  override fun processPackageDirectories(
      psiPackage: PsiPackage,
      scope: GlobalSearchScope,
      consumer: Processor<in PsiDirectory>,
      includeLibrarySources: Boolean,
  ): Boolean = delegate.processPackageDirectories(psiPackage, scope, consumer, includeLibrarySources)

  override fun getClasses(psiPackage: PsiPackage, scope: GlobalSearchScope): Array<PsiClass> {
    val active = manager.packageFiles(psiPackage.qualifiedName, scope).flatMap { it.classes.toList() }
    val disk = delegate.getClasses(psiPackage, scope).filterNot { manager.isShadowed(it.containingFile) }
    return (active + disk).toTypedArray()
  }

  override fun getClasses(className: String?, psiPackage: PsiPackage, scope: GlobalSearchScope): Array<PsiClass> =
      getClasses(psiPackage, scope).filter { className == null || it.name == className }.toTypedArray()

  override fun getClassNames(psiPackage: PsiPackage, scope: GlobalSearchScope): Set<String> =
      getClasses(psiPackage, scope).mapNotNullTo(HashSet()) { it.name }

  override fun getPackageFiles(psiPackage: PsiPackage, scope: GlobalSearchScope): Array<PsiFile> {
    val active = manager.packageFiles(psiPackage.qualifiedName, scope)
    val disk = delegate.getPackageFiles(psiPackage, scope).filterNot(manager::isShadowed)
    return (active + disk).toTypedArray()
  }

  override fun getSubPackages(psiPackage: PsiPackage, scope: GlobalSearchScope): Array<PsiPackage> {
    val active = manager.subPackageNames(psiPackage.qualifiedName, scope).mapNotNull(manager::findPackage)
    return (active + delegate.getSubPackages(psiPackage, scope))
        .distinctBy { it.qualifiedName }.toTypedArray()
  }
}
