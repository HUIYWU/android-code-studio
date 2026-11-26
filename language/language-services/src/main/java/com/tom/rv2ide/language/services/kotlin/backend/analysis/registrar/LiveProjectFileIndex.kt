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
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import java.nio.file.Path

internal class LiveProjectFileIndex(
    private val project: MockProject,
    projectContext: KotlinAnalysisProjectContext,
    private val sourceRegistry: LiveSourceFileRegistry,
) : ProjectFileIndex {
  private class Root<T>(val path: Path, val value: T) {
    val normalizedPath: Path = path.toAbsolutePath().normalize()
    val pathText: String = normalizedPath.toString().replace('\\', '/')
    val archivePrefix: String = "$pathText!/"
    val priority: Int = path.toString().length
  }

  private class Roots<T>(val entries: List<Root<T>>) {
    val paths = entries.withIndex().groupBy { it.value.normalizedPath.fileSystem }.mapValues { (_, values) ->
      LiveRootPathIndex(values.map { (order, root) ->
        LiveRootPathIndex.Entry(root.normalizedPath.toString(), root, root.priority, order)
      })
    }
    val vfsPaths = LiveRootPathIndex(entries.mapIndexed { order, root ->
      LiveRootPathIndex.Entry(root.pathText, root, root.priority, order)
    }, archiveBoundary = true)
  }

  private class FileLocation(
      val file: VirtualFile,
      private val mappedPath: Path?,
  ) {
    private var archiveResolved = false
    private var archivePath: String? = null
    private var resolved = false
    var path: Path? = null
      private set
    var vfsPath: String = ""
      private set

    fun archiveRootPath(): String? {
      if (!archiveResolved) {
        if (mappedPath == null && file.fileSystem is CoreJarFileSystem) {
          var root = file
          var parent = root.parent
          while (parent != null) {
            root = parent
            parent = root.parent
          }
          archivePath = root.path.replace('\\', '/')
        }
        archiveResolved = true
      }
      return archivePath
    }

    fun resolve() {
      if (resolved) return
      val rawPath = file.path
      path = mappedPath ?: file.fileSystem.getNioPath(file)?.normalize()
          ?: runCatching { Path.of(rawPath).normalize() }.getOrNull()
      vfsPath = rawPath.replace('\\', '/')
      resolved = true
    }
  }

  private val contexts = projectContext.modules
  private val modulesByPath = LinkedHashMap<String, LiveProjectModule>()
  private val modules: List<LiveProjectModule>
  internal val moduleManager: ModuleManager
  internal val projectRootManager: ProjectRootManager
  private val contentRoots: Roots<KotlinAnalysisModuleContext>
  private val sourceRoots: Roots<ProjectRootDescriptor>
  private val outputRoots: Roots<KotlinAnalysisModuleContext>
  private val libraryClassRoots: Roots<KotlinAnalysisModuleContext>
  private val librarySourceRoots: Roots<KotlinAnalysisModuleContext>

  init {
    contexts.forEach { context ->
      modulesByPath[context.modulePath] = LiveProjectModule(project, context)
    }
    modules = modulesByPath.values.toList()
    modules.forEach { it.bindModules(modulesByPath, modules) }
    contentRoots = Roots(contexts.map { Root(it.projectDirectory, it) })
    sourceRoots = Roots(contexts.flatMap { context -> context.sourceRootDescriptors.map { Root(it.path, it) } })
    outputRoots = Roots(contexts.flatMap { context -> context.moduleOutputRoots.map { Root(it, context) } })
    libraryClassRoots = Roots(contexts.flatMap { context ->
      val binaries = context.binaryRootContexts
          .filter { it.role != ProjectBinaryRole.MODULE_OUTPUT }
          .map { Root(it.path, context) }
      val attachments = context.libraryAttachments.flatMap { attachment -> attachment.binaryPaths.map { Root(it, context) } }
      binaries + attachments
    })
    librarySourceRoots = Roots(contexts.flatMap { context ->
      context.libraryAttachments.flatMap { attachment -> attachment.sourcePaths.map { Root(it, context) } }
    })
    moduleManager = LiveModuleManager(project, modules)
    projectRootManager = LiveProjectRootManager(this)
  }

  fun moduleForFile(file: VirtualFile): LiveProjectModule? = moduleForLocation(locationFor(file))

  private fun moduleForLocation(location: FileLocation): LiveProjectModule? =
      bestRoot(location, sourceRoots)?.value?.ownerModulePath?.let(::moduleForPath)
          ?: bestRoot(location, outputRoots)?.value?.let { moduleForPath(it.modulePath) }
          ?: bestRoot(location, contentRoots)?.value?.let { moduleForPath(it.modulePath) }

  private fun ownerForLocation(location: FileLocation): LiveProjectModule? =
      moduleForLocation(location)
          ?: bestRoot(location, libraryClassRoots)?.value?.let { moduleForPath(it.modulePath) }
          ?: bestRoot(location, librarySourceRoots)?.value?.let { moduleForPath(it.modulePath) }

  fun moduleForPath(path: String): LiveProjectModule? = modulesByPath[path]

  fun contentRootFiles(): List<VirtualFile> = contentRoots.entries.mapNotNull { analysisRootVirtualFile(it.path) }

  fun sourceRootFiles(): List<VirtualFile> = sourceRoots.entries.mapNotNull { analysisRootVirtualFile(it.path) }.distinct()

  fun getOrderEntriesForModuleFile(module: LiveProjectModule, file: VirtualFile): List<OrderEntry> =
      orderEntriesForModuleFile(module, file, locationFor(file))

  private fun orderEntriesForModuleFile(
      module: LiveProjectModule,
      file: VirtualFile,
      location: FileLocation,
  ): List<OrderEntry> {
    val moduleIndex = ModuleRootManager.getInstance(module).getOrderEntries()
    val matches = moduleIndex.filter { entry ->
      entry.getFiles(OrderRootType.CLASSES).any { rootContains(it, file, location) } ||
          entry.getFiles(OrderRootType.SOURCES).any { rootContains(it, file, location) }
    }
    if (moduleForLocation(location) === module) {
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
    if (!hasRoot(locationFor(dir), contentRoots)) return true
    return walk(dir, processor, customFilter)
  }

  override fun isInContent(fileOrDir: VirtualFile): Boolean = hasRoot(locationFor(fileOrDir), contentRoots)

  override fun isInSourceContent(fileOrDir: VirtualFile): Boolean = hasRoot(locationFor(fileOrDir), sourceRoots)

  override fun isInTestSourceContent(fileOrDir: VirtualFile): Boolean = false

  override fun isUnderSourceRootOfType(
      fileOrDir: VirtualFile,
      rootTypes: Set<out JpsModuleSourceRootType<*>>,
  ): Boolean = rootTypes.contains(JavaSourceRootType.SOURCE) && isInSourceContent(fileOrDir)

  override fun isInProject(file: VirtualFile): Boolean = isInProjectOrExcluded(file) && !isExcluded(file)

  override fun isInProjectOrExcluded(file: VirtualFile): Boolean {
    val location = locationFor(file)
    return hasRoot(location, contentRoots) || hasRoot(location, libraryClassRoots) ||
        hasRoot(location, librarySourceRoots)
  }

  override fun getModuleForFile(file: VirtualFile): Module? = moduleForFile(file)

  override fun getModuleForFile(file: VirtualFile, honorExclusion: Boolean): Module? = moduleForFile(file)

  override fun getModulesForFile(file: VirtualFile, honorExclusion: Boolean): Set<Module> =
      moduleForFile(file)?.let(::setOf) ?: emptySet()

  override fun getOrderEntriesForFile(file: VirtualFile): List<OrderEntry> {
    val location = locationFor(file)
    val owner = ownerForLocation(location) ?: return emptyList()
    return orderEntriesForModuleFile(owner, file, location)
  }

  override fun getClassRootForFile(file: VirtualFile): VirtualFile? {
    val location = locationFor(file)
    val root = bestRoot(location, outputRoots) ?: bestRoot(location, libraryClassRoots) ?: return null
    return rootVirtualFile(file, root)
  }

  override fun getSourceRootForFile(file: VirtualFile): VirtualFile? {
    val location = locationFor(file)
    val root = bestRoot(location, sourceRoots) ?: bestRoot(location, librarySourceRoots) ?: return null
    return rootVirtualFile(file, root)
  }

  override fun getContentRootForFile(file: VirtualFile): VirtualFile? =
      bestRoot(locationFor(file), contentRoots)?.let { rootVirtualFile(file, it) }

  override fun getContentRootForFile(file: VirtualFile, honorExclusion: Boolean): VirtualFile? = getContentRootForFile(file)

  override fun getPackageNameByDirectory(dir: VirtualFile): String? {
    val location = locationFor(dir)
    val root = bestRoot(location, sourceRoots) ?: return null
    val path = location.path ?: return null
    val relative = runCatching { root.path.relativize(path) }.getOrNull() ?: return null
    return relative.toString().replace('\\', '/').trim('/').replace('/', '.')
  }

  override fun isLibraryClassFile(file: VirtualFile): Boolean = !file.isDirectory && isInLibraryClasses(file)

  override fun isInSource(fileOrDir: VirtualFile): Boolean = isInSourceContent(fileOrDir)

  override fun isInLibraryClasses(fileOrDir: VirtualFile): Boolean = hasRoot(locationFor(fileOrDir), libraryClassRoots)

  override fun isInLibrary(fileOrDir: VirtualFile): Boolean {
    val location = locationFor(fileOrDir)
    return hasRoot(location, libraryClassRoots) || hasRoot(location, librarySourceRoots)
  }

  override fun isInLibrarySource(fileOrDir: VirtualFile): Boolean = hasRoot(locationFor(fileOrDir), librarySourceRoots)

  override fun isExcluded(file: VirtualFile): Boolean = false

  override fun findContainingLibraries(fileOrDir: VirtualFile): Collection<org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.LibraryEntity> = emptyList()

  override fun isUnderIgnored(file: VirtualFile): Boolean = false

  override fun getContainingSourceRootType(file: VirtualFile): JpsModuleSourceRootType<*>? =
      if (isInSourceContent(file)) JavaSourceRootType.SOURCE else null

  override fun isInGeneratedSources(file: VirtualFile): Boolean =
      bestRoot(locationFor(file), sourceRoots)?.value?.role == ProjectRootRole.GENERATED_SOURCE

  override fun getUnloadedModuleNameForFile(fileOrDir: VirtualFile): String? = null

  private fun <T> bestRoot(location: FileLocation, roots: Roots<T>): Root<T>? {
    location.resolve()
    val pathMatch = location.path?.let { roots.paths[it.fileSystem]?.find(it.toString()) }
    val vfsMatch = roots.vfsPaths.find(location.vfsPath)
    return if (vfsMatch != null && vfsMatch.preferredTo(pathMatch)) vfsMatch.value else pathMatch?.value
  }

  private fun hasRoot(location: FileLocation, roots: Roots<*>): Boolean {
    location.archiveRootPath()?.let { if (roots.vfsPaths.contains(it)) return true }
    location.resolve()
    return location.path?.let { roots.paths[it.fileSystem]?.contains(it.toString()) } == true ||
        roots.vfsPaths.contains(location.vfsPath)
  }

  private fun locationFor(file: VirtualFile): FileLocation =
      FileLocation(file, sourceRegistry.pathForVirtualFile(file)?.normalize())

  private fun rootContains(root: VirtualFile, file: VirtualFile, location: FileLocation): Boolean {
    if (file === root) return true
    location.resolve()
    val rootPath = root.path.replace('\\', '/')
    return location.vfsPath == rootPath || location.vfsPath.startsWith(rootPath.trimEnd('/') + "/")
  }

  private fun rootVirtualFile(file: VirtualFile, root: Root<*>): VirtualFile? {
    var current: VirtualFile? = file
    while (current != null) {
      val currentPath = current.path
      if (currentPath == root.pathText || currentPath == root.archivePrefix) {
        return if (current.isDirectory) current else analysisRootVirtualFile(root.path)
      }
      current = current.parent
    }
    return analysisRootVirtualFile(root.path)
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