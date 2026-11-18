package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisModuleContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisProjectContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.projects.models.ProjectBinaryRole
import com.tom.rv2ide.projects.models.ProjectRootDescriptor
import com.tom.rv2ide.projects.models.ProjectRootRole
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager
import org.jetbrains.kotlin.com.intellij.openapi.projectRoots.Sdk
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerator
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileFilter
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import java.nio.file.Path

internal class LiveProjectFileIndex(
    private val project: MockProject,
    projectContext: KotlinAnalysisProjectContext,
    private val sourceRegistry: LiveSourceFileRegistry,
) : ProjectFileIndex {
  private data class Root<T>(val path: Path, val value: T)

  private val contexts = projectContext.modules
  private val modulesByPath = LinkedHashMap<String, LiveProjectModule>()
  private val modules: List<LiveProjectModule>
  internal val moduleManager: ModuleManager
  internal val projectRootManager: ProjectRootManager
  private val contentRoots: List<Root<KotlinAnalysisModuleContext>>
  private val sourceRoots: List<Root<ProjectRootDescriptor>>
  private val outputRoots: List<Root<KotlinAnalysisModuleContext>>
  private val libraryClassRoots: List<Root<KotlinAnalysisModuleContext>>
  private val librarySourceRoots: List<Root<KotlinAnalysisModuleContext>>

  init {
    contexts.forEach { context ->
      modulesByPath[context.modulePath] = LiveProjectModule(project, context)
    }
    modules = modulesByPath.values.toList()
    modules.forEach { it.bindModules(modulesByPath, modules) }
    contentRoots = contexts.map { Root(it.projectDirectory, it) }
    sourceRoots = contexts.flatMap { context -> context.sourceRootDescriptors.map { Root(it.path, it) } }
    outputRoots = contexts.flatMap { context -> context.moduleOutputRoots.map { Root(it, context) } }
    libraryClassRoots = contexts.flatMap { context ->
      val binaries = context.binaryRootContexts
          .filter { it.role != ProjectBinaryRole.MODULE_OUTPUT }
          .map { Root(it.path, context) }
      val attachments = context.libraryAttachments.flatMap { attachment -> attachment.binaryPaths.map { Root(it, context) } }
      binaries + attachments
    }
    librarySourceRoots = contexts.flatMap { context ->
      context.libraryAttachments.flatMap { attachment -> attachment.sourcePaths.map { Root(it, context) } }
    }
    moduleManager = LiveModuleManager(project, modules)
    projectRootManager = LiveProjectRootManager(this)
  }

  fun moduleForFile(file: VirtualFile): LiveProjectModule? =
      bestRoot(file, sourceRoots)?.value?.ownerModulePath?.let(::moduleForPath)
          ?: bestRoot(file, outputRoots)?.value?.let { moduleForPath(it.modulePath) }
          ?: bestRoot(file, contentRoots)?.value?.let { moduleForPath(it.modulePath) }

  private fun ownerForAnyFile(file: VirtualFile): LiveProjectModule? =
      moduleForFile(file)
          ?: bestRoot(file, libraryClassRoots)?.value?.let { moduleForPath(it.modulePath) }
          ?: bestRoot(file, librarySourceRoots)?.value?.let { moduleForPath(it.modulePath) }

  fun moduleForPath(path: String): LiveProjectModule? = modulesByPath[path]

  fun contentRootFiles(): List<VirtualFile> = contentRoots.mapNotNull { analysisRootVirtualFile(it.path) }

  fun sourceRootFiles(): List<VirtualFile> = sourceRoots.mapNotNull { analysisRootVirtualFile(it.path) }.distinct()

  fun getOrderEntriesForModuleFile(module: LiveProjectModule, file: VirtualFile): List<OrderEntry> {
    val moduleIndex = ModuleRootManager.getInstance(module).getOrderEntries()
    val matches = moduleIndex.filter { entry ->
      entry.getFiles(OrderRootType.CLASSES).any { rootContains(it, file) } ||
          entry.getFiles(OrderRootType.SOURCES).any { rootContains(it, file) }
    }
    if (moduleForFile(file) === module) {
      val sourceEntry = moduleIndex.firstOrNull()
      if (sourceEntry != null && sourceEntry !in matches) return listOf(sourceEntry) + matches
    }
    return matches
  }

  override fun iterateContent(processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator): Boolean =
      iterateContent(processor, null)

  override fun iterateContent(
      processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator,
      filter: VirtualFileFilter?,
  ): Boolean = walkRoots(contentRootFiles(), processor, filter)

  override fun iterateContentUnderDirectory(
      dir: VirtualFile,
      processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator,
  ): Boolean = iterateContentUnderDirectory(dir, processor, null)

  override fun iterateContentUnderDirectory(
      dir: VirtualFile,
      processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator,
      customFilter: VirtualFileFilter?,
  ): Boolean {
    if (contentRootFor(dir) == null) return true
    return walk(dir, processor, customFilter)
  }

  override fun isInContent(fileOrDir: VirtualFile): Boolean = contentRootFor(fileOrDir) != null

  override fun isInSourceContent(fileOrDir: VirtualFile): Boolean = bestRoot(fileOrDir, sourceRoots) != null

  override fun isInTestSourceContent(fileOrDir: VirtualFile): Boolean = false

  override fun isUnderSourceRootOfType(
      fileOrDir: VirtualFile,
      rootTypes: Set<out JpsModuleSourceRootType<*>>,
  ): Boolean = rootTypes.contains(JavaSourceRootType.SOURCE) && isInSourceContent(fileOrDir)

  override fun isInProject(file: VirtualFile): Boolean =
      (isInContent(file) || isInLibrary(file)) && !isExcluded(file)

  override fun isInProjectOrExcluded(file: VirtualFile): Boolean = isInContent(file) || isInLibrary(file)

  override fun getModuleForFile(file: VirtualFile): Module? = moduleForFile(file)

  override fun getModuleForFile(file: VirtualFile, honorExclusion: Boolean): Module? = moduleForFile(file)

  override fun getModulesForFile(file: VirtualFile, honorExclusion: Boolean): Set<Module> =
      moduleForFile(file)?.let(::setOf) ?: emptySet()

  override fun getOrderEntriesForFile(file: VirtualFile): List<OrderEntry> =
      ownerForAnyFile(file)?.let { getOrderEntriesForModuleFile(it, file) }.orEmpty()

  override fun getClassRootForFile(file: VirtualFile): VirtualFile? =
      bestRoot(file, outputRoots)?.let { rootVirtualFile(file, it.path) }
          ?: bestRoot(file, libraryClassRoots)?.let { rootVirtualFile(file, it.path) }

  override fun getSourceRootForFile(file: VirtualFile): VirtualFile? {
    val root = bestRoot(file, sourceRoots) ?: bestRoot(file, librarySourceRoots) ?: return null
    return rootVirtualFile(file, root.path)
  }

  override fun getContentRootForFile(file: VirtualFile): VirtualFile? =
      contentRootFor(file)?.let { rootVirtualFile(file, it.path) }

  override fun getContentRootForFile(file: VirtualFile, honorExclusion: Boolean): VirtualFile? = getContentRootForFile(file)

  override fun getPackageNameByDirectory(dir: VirtualFile): String? {
    val root = bestRoot(dir, sourceRoots) ?: return null
    val path = pathFor(dir) ?: return null
    val relative = runCatching { root.path.relativize(path) }.getOrNull() ?: return null
    return relative.toString().replace('\\', '/').trim('/').replace('/', '.')
  }

  override fun isLibraryClassFile(file: VirtualFile): Boolean = !file.isDirectory && isInLibraryClasses(file)

  override fun isInSource(fileOrDir: VirtualFile): Boolean = isInSourceContent(fileOrDir)

  override fun isInLibraryClasses(fileOrDir: VirtualFile): Boolean = bestRoot(fileOrDir, libraryClassRoots) != null

  override fun isInLibrary(fileOrDir: VirtualFile): Boolean = isInLibraryClasses(fileOrDir) || isInLibrarySource(fileOrDir)

  override fun isInLibrarySource(fileOrDir: VirtualFile): Boolean = bestRoot(fileOrDir, librarySourceRoots) != null

  override fun isExcluded(file: VirtualFile): Boolean = false

  override fun findContainingLibraries(fileOrDir: VirtualFile): Collection<org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.LibraryEntity> = emptyList()

  override fun isUnderIgnored(file: VirtualFile): Boolean = false

  override fun getContainingSourceRootType(file: VirtualFile): JpsModuleSourceRootType<*>? =
      if (isInSourceContent(file)) JavaSourceRootType.SOURCE else null

  override fun isInGeneratedSources(file: VirtualFile): Boolean =
      bestRoot(file, sourceRoots)?.value?.role == ProjectRootRole.GENERATED_SOURCE

  override fun getUnloadedModuleNameForFile(fileOrDir: VirtualFile): String? = null

  private fun contentRootFor(file: VirtualFile): Root<KotlinAnalysisModuleContext>? = bestRoot(file, contentRoots)

  private fun <T> bestRoot(file: VirtualFile, roots: List<Root<T>>): Root<T>? =
      roots.asSequence().filter { matches(file, it.path) }.maxByOrNull { it.path.toString().length }

  private fun pathFor(file: VirtualFile): Path? =
      sourceRegistry.pathForVirtualFile(file)?.normalize()
          ?: runCatching { file.toNioPath().normalize() }.getOrNull()
          ?: runCatching { Path.of(file.path).normalize() }.getOrNull()

  private fun matches(file: VirtualFile, root: Path): Boolean {
    val candidate = pathFor(file)
    val normalizedRoot = root.toAbsolutePath().normalize()
    if (candidate != null && (candidate == normalizedRoot || candidate.startsWith(normalizedRoot))) return true
    val filePath = file.path.replace('\\', '/')
    val rootPath = normalizedRoot.toString().replace('\\', '/')
    return filePath == rootPath || filePath.startsWith("$rootPath/") || filePath.startsWith("$rootPath!/")
  }

  private fun rootContains(root: VirtualFile, file: VirtualFile): Boolean =
      file === root || file.path == root.path || file.path.startsWith(root.path.trimEnd('/') + "/")

  private fun rootVirtualFile(file: VirtualFile, path: Path): VirtualFile? {
    val normalizedRoot = path.toAbsolutePath().normalize().toString().replace('\\', '/')
    var current: VirtualFile? = file
    while (current != null) {
      if (current.path == normalizedRoot || current.path == "$normalizedRoot!/") {
        return if (current.isDirectory) current else analysisRootVirtualFile(path)
      }
      current = current.parent
    }
    return analysisRootVirtualFile(path)
  }

  private fun walkRoots(
      roots: List<VirtualFile>,
      processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator,
      filter: VirtualFileFilter?,
  ): Boolean {
    roots.distinct().forEach { root -> if (!walk(root, processor, filter)) return false }
    return true
  }

  private fun walk(
      file: VirtualFile,
      processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator,
      filter: VirtualFileFilter?,
  ): Boolean {
    if (file.isDirectory && filter != null && !filter.accept(file)) return true
    if (!processor.processFile(file)) return false
    if (!file.isDirectory) return true
    file.children.forEach { if (!walk(it, processor, filter)) return false }
    return true
  }
}

internal class LiveProjectRootManager(private val fileIndex: LiveProjectFileIndex) : ProjectRootManager() {
  override fun getFileIndex(): ProjectFileIndex = fileIndex
  override fun orderEntries(): OrderEnumerator = unsupported()
  override fun orderEntries(modules: Collection<out Module>): OrderEnumerator = unsupported()
  override fun getContentRootsFromAllModules(): Array<VirtualFile> = fileIndex.contentRootFiles().toTypedArray()
  override fun getContentRootUrls(): List<String> = fileIndex.contentRootFiles().map { it.url }
  override fun getContentRoots(): Array<VirtualFile> = getContentRootsFromAllModules()
  override fun getContentSourceRoots(): Array<VirtualFile> = fileIndex.sourceRootFiles().toTypedArray()
  override fun getModuleSourceRoots(rootTypes: Set<out JpsModuleSourceRootType<*>>): List<VirtualFile> =
      if (rootTypes.contains(JavaSourceRootType.SOURCE)) fileIndex.sourceRootFiles() else emptyList()
  override fun getProjectSdk(): Sdk? = null
  override fun getProjectSdkName(): String? = null
  override fun getProjectSdkTypeName(): String? = null
  override fun setProjectSdk(sdk: Sdk?) = unsupported<Unit>()
  override fun setProjectSdkName(name: String, type: String) = unsupported<Unit>()
}

private fun <T> unsupported(): T = throw UnsupportedOperationException("Analysis project model is read-only")