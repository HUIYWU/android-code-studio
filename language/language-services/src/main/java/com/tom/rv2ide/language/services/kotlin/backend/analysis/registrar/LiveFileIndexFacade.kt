package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager
import org.jetbrains.kotlin.com.intellij.openapi.module.UnloadedModuleDescription
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootModificationTracker
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jps.model.module.JpsModuleSourceRootType

internal class LiveFileIndexFacade(
    project: Project,
    private val index: LiveProjectFileIndex,
) : FileIndexFacade(project) {
  override fun isInContent(file: VirtualFile): Boolean = index.isInContent(file)

  override fun isInSource(file: VirtualFile): Boolean = index.isInSource(file)

  override fun isInSourceContent(file: VirtualFile): Boolean = index.isInSourceContent(file)

  override fun isInLibrary(file: VirtualFile): Boolean = index.isInLibrary(file)

  override fun isInLibraryClasses(file: VirtualFile): Boolean = index.isInLibraryClasses(file)

  override fun isInLibrarySource(file: VirtualFile): Boolean = index.isInLibrarySource(file)

  override fun isExcludedFile(file: VirtualFile): Boolean = index.isExcluded(file)

  override fun isUnderIgnored(file: VirtualFile): Boolean = index.isUnderIgnored(file)

  @Suppress("UNCHECKED_CAST")
  override fun isUnderSourceRootOfType(file: VirtualFile, rootTypes: Set<*>): Boolean =
      index.isUnderSourceRootOfType(file, rootTypes as Set<JpsModuleSourceRootType<*>>)

  override fun getModuleForFile(file: VirtualFile): Module? = index.getModuleForFile(file)

  override fun isValidAncestor(baseDir: VirtualFile, child: VirtualFile): Boolean {
    var directory: VirtualFile? = if (child.isDirectory) child else child.parent
    while (directory != null) {
      if (directory == baseDir) return true
      if (!index.isInProject(directory)) return false
      directory = directory.parent
    }
    return false
  }

  override fun getRootModificationTracker(): ModificationTracker =
      myProject.getService(ProjectRootModificationTracker::class.java)

  override fun getUnloadedModuleDescriptions(): Collection<UnloadedModuleDescription> =
      ModuleManager.getInstance(myProject).unloadedModuleDescriptions
}
