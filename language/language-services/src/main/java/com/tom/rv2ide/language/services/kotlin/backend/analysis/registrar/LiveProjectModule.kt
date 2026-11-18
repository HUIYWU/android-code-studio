package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisModuleContext
import com.tom.rv2ide.projects.models.ProjectBinaryRole
import org.jetbrains.kotlin.com.intellij.mock.MockComponentManager
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.module.ModifiableModuleModel
import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleDescription
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleGrouper
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.projectRoots.Sdk
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentEntry
import org.jetbrains.kotlin.com.intellij.openapi.roots.DependencyScope
import org.jetbrains.kotlin.com.intellij.openapi.roots.ExcludeFolder
import org.jetbrains.kotlin.com.intellij.openapi.roots.JdkOrderEntry
import org.jetbrains.kotlin.com.intellij.openapi.roots.LibraryOrderEntry
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModifiableRootModel
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleFileIndex
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleOrderEntry
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootModel
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleSourceOrderEntry
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerator
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectModelExternalSource
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootPolicy
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileFilter
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.util.graph.Graph
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRoot
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.Comparator

internal class LiveProjectModule(
    private val project: MockProject,
    internal val context: KotlinAnalysisModuleContext,
) : MockComponentManager(project.picoContainer, project), Module {
  private lateinit var modulesByPath: Map<String, LiveProjectModule>
  private lateinit var allModules: Collection<LiveProjectModule>
  private val options = HashMap<String, String?>()
  private lateinit var rootManager: LiveModuleRootManager

  fun bindModules(
      modulesByPath: Map<String, LiveProjectModule>,
      allModules: Collection<LiveProjectModule>,
  ) {
    this.modulesByPath = modulesByPath
    this.allModules = allModules
    rootManager = LiveModuleRootManager(this, context, modulesByPath)
    addComponent(ModuleRootManager::class.java, rootManager)
    registerService(ModuleRootManager::class.java, rootManager)
  }

  override fun getModuleFile(): VirtualFile? = null

  override fun getModuleNioFile(): Path =
      context.projectDirectory.resolve(".acs-analysis-modules").resolve("${context.modulePath.hashCode()}.iml")

  override fun getProject(): Project = project

  override fun getName(): String = context.modulePath

  override fun isLoaded(): Boolean = true

  override fun setOption(key: String, value: String?) {
    options[key] = value
  }

  override fun getOptionValue(key: String): String? = options[key]

  override fun getModuleScope(): GlobalSearchScope = scope(false, false, false)

  override fun getModuleScope(includeTests: Boolean): GlobalSearchScope = getModuleScope()

  override fun getModuleWithLibrariesScope(): GlobalSearchScope = scope(false, true, false)

  override fun getModuleWithDependenciesScope(): GlobalSearchScope = scope(true, false, false)

  override fun getModuleContentScope(): GlobalSearchScope = scope(false, false, false, true)

  override fun getModuleContentWithDependenciesScope(): GlobalSearchScope = scope(true, false, false, true)

  override fun getModuleWithDependenciesAndLibrariesScope(includeTests: Boolean): GlobalSearchScope = scope(true, true, false)

  override fun getModuleWithDependentsScope(): GlobalSearchScope = scope(false, false, true)

  override fun getModuleTestsWithDependentsScope(): GlobalSearchScope = getModuleWithDependentsScope()

  override fun getModuleRuntimeScope(includeTests: Boolean): GlobalSearchScope = scope(true, true, false)

  override fun getModuleProductionSourceScope(): GlobalSearchScope = getModuleScope()

  override fun getModuleTestSourceScope(): GlobalSearchScope = GlobalSearchScope.EMPTY_SCOPE

  internal fun directDependencies(): List<LiveProjectModule> = context.compileModulePaths.mapNotNull(modulesByPath::get)

  private fun scope(
      includeDependencies: Boolean,
      includeLibraries: Boolean,
      includeDependents: Boolean,
      includeContent: Boolean = false,
  ): GlobalSearchScope {
    val allowed = LinkedHashSet<LiveProjectModule>()
    allowed += this
    if (includeDependencies) {
      val pending = ArrayDeque<LiveProjectModule>()
      pending += this
      while (pending.isNotEmpty()) {
        pending.removeFirst().directDependencies().forEach { dependency ->
          if (allowed.add(dependency)) pending += dependency
        }
      }
    }
    if (includeDependents) allModules.filter { context.modulePath in it.context.compileModulePaths }.forEach(allowed::add)
    val fileIndex = ProjectFileIndexAccess.get(this@LiveProjectModule.project)
    return object : GlobalSearchScope(this@LiveProjectModule.project) {
      override fun contains(file: VirtualFile): Boolean {
        val owner = fileIndex.moduleForFile(file)
        return (owner in allowed && (includeContent || fileIndex.isInSourceContent(file))) ||
            (includeLibraries && fileIndex.isInLibrary(file))
      }

      override fun isSearchInModuleContent(module: Module): Boolean = module in allowed

      override fun isSearchInLibraries(): Boolean = includeLibraries
    }
  }
}

private class LiveModuleRootManager(
    private val module: LiveProjectModule,
    private val context: KotlinAnalysisModuleContext,
    private val modulesByPath: Map<String, LiveProjectModule>,
) : ModuleRootManager() {
  private val moduleFileIndex = LiveModuleFileIndex(module)

  override fun getModule(): Module = module

  override fun getContentEntries(): Array<ContentEntry> = arrayOf(contentEntry())

  override fun getOrderEntries(): Array<OrderEntry> = buildList {
    add(sourceOrderEntry(module, this@LiveModuleRootManager))
    context.compileModulePaths.mapNotNull(modulesByPath::get).forEach { dependency ->
      add(moduleOrderEntry(module, dependency, dependency.context.moduleOutputRoots + dependency.context.binaryRoots))
    }
    context.binaryRootContexts
        .filter { it.role == ProjectBinaryRole.EXTERNAL_BINARY }
        .groupBy { it.origin }
        .forEach { (origin, binaries) ->
          add(libraryOrderEntry(module, origin.name, binaries.map { it.path }, emptyList()))
        }
    context.libraryAttachments.forEach { attachment ->
      add(libraryOrderEntry(module, attachment.libraryIdentity, attachment.binaryPaths, attachment.sourcePaths))
    }
    if (context.bootClasspaths.isNotEmpty()) add(jdkOrderEntry(module, context.bootClasspaths))
  }.toTypedArray()

  override fun getSdk(): Sdk? = null
  override fun isSdkInherited(): Boolean = true
  override fun getContentRoots(): Array<VirtualFile> = listOfNotNull(virtualFile(context.projectDirectory)).toTypedArray()
  override fun getContentRootUrls(): Array<String> = getContentRoots().map { it.url }.toTypedArray()
  override fun getExcludeRoots(): Array<VirtualFile> = emptyArray()
  override fun getExcludeRootUrls(): Array<String> = emptyArray()
  override fun getSourceRoots(): Array<VirtualFile> = sourceRoots().toTypedArray()
  override fun getSourceRoots(includingTests: Boolean): Array<VirtualFile> = getSourceRoots()
  override fun getSourceRoots(rootType: JpsModuleSourceRootType<*>): List<VirtualFile> =
      if (rootType == JavaSourceRootType.SOURCE) sourceRoots() else emptyList()
  override fun getSourceRoots(rootTypes: Set<out JpsModuleSourceRootType<*>>): List<VirtualFile> =
      if (rootTypes.contains(JavaSourceRootType.SOURCE)) sourceRoots() else emptyList()
  override fun getSourceRootUrls(): Array<String> = sourceRoots().map { it.url }.toTypedArray()
  override fun getSourceRootUrls(includingTests: Boolean): Array<String> = getSourceRootUrls()

  override fun <R> processOrder(policy: RootPolicy<R>, initialValue: R): R {
    var result = initialValue
    getOrderEntries().forEach { result = it.accept(policy, result) }
    return result
  }

  override fun orderEntries(): OrderEnumerator = unsupported()
  override fun getDependencyModuleNames(): Array<String> = context.compileModulePaths.toTypedArray()
  override fun <T> getModuleExtension(klass: Class<T>): T? = null
  override fun getModuleDependencies(): Array<Module> = context.compileModulePaths.mapNotNull(modulesByPath::get).toTypedArray()
  override fun getModuleDependencies(includeTests: Boolean): Array<Module> = getModuleDependencies()
  override fun getFileIndex(): ModuleFileIndex = moduleFileIndex
  override fun getModifiableModel(): ModifiableRootModel = unsupported()
  override fun getDependencies(): Array<Module> = getModuleDependencies()
  override fun getDependencies(includeTests: Boolean): Array<Module> = getModuleDependencies()
  override fun isDependsOn(module: Module): Boolean = module in getModuleDependencies()
  override fun getExternalSource(): ProjectModelExternalSource? = null

  private fun sourceRoots(): List<VirtualFile> =
      (context.javaSourceRoots + context.generatedSourceRoots).distinct().mapNotNull(::virtualFile)

  private fun contentEntry(): ContentEntry {
    val root = virtualFile(context.projectDirectory)
    val folders = sourceRoots().map(::sourceFolder).toTypedArray()
    return proxy(ContentEntry::class.java) { _, method, args ->
      when (method.name) {
        "getFile" -> root
        "getUrl" -> root?.url.orEmpty()
        "getSourceFolders" -> {
          val argument = args?.firstOrNull()
          when (argument) {
            null -> folders
            is JpsModuleSourceRootType<*> -> folders.filter { it.getRootType() == argument }
            is Set<*> -> folders.filter { it.getRootType() in argument }
            else -> folders
          }
        }
        "getSourceFolderFiles" -> folders.mapNotNull { it.getFile() }.toTypedArray()
        "getExcludeFolders" -> emptyArray<ExcludeFolder>()
        "getExcludeFolderUrls" -> emptyArray<String>()
        "getExcludeFolderFiles" -> emptyArray<VirtualFile>()
        "getExcludePatterns" -> emptyList<String>()
        "getRootModel" -> this
        "isSynthetic" -> false
        else -> unsupported()
      }
    }
  }

  private fun sourceFolder(file: VirtualFile): SourceFolder = proxy(SourceFolder::class.java) { _, method, _ ->
    when (method.name) {
      "getFile" -> file
      "getContentEntry" -> contentEntry()
      "getUrl" -> file.url
      "isSynthetic" -> false
      "isTestSource" -> false
      "getPackagePrefix" -> ""
      "setPackagePrefix" -> Unit
      "getRootType" -> JavaSourceRootType.SOURCE
      "getJpsElement" -> jpsSourceRoot(file)
      "changeType" -> unsupported()
      else -> unsupported()
    }
  }

  private fun jpsSourceRoot(file: VirtualFile): JpsModuleSourceRoot = proxy(JpsModuleSourceRoot::class.java) { _, method, _ ->
    when (method.name) {
      "getRootType" -> JavaSourceRootType.SOURCE
      "getFile" -> runCatching { file.toNioPath().toFile() }.getOrNull()
      "getPath" -> runCatching { file.toNioPath() }.getOrNull()
      "getUrl" -> file.url
      "getProperties" -> JavaSourceRootType.SOURCE.createDefaultProperties()
      else -> null
    }
  }

  private fun virtualFile(path: Path): VirtualFile? = analysisRootVirtualFile(path)
}

private class LiveModuleFileIndex(private val module: LiveProjectModule) : ModuleFileIndex {
  override fun getOrderEntryForFile(file: VirtualFile): OrderEntry? = getOrderEntriesForFile(file).firstOrNull()
  override fun getOrderEntriesForFile(file: VirtualFile): List<OrderEntry> =
      ProjectFileIndexAccess.get(module.getProject()).getOrderEntriesForModuleFile(module, file)
  override fun iterateContent(processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator): Boolean =
      ProjectFileIndexAccess.get(module.getProject()).iterateContentUnderDirectory(module.context.projectDirectoryFile(), processor)
  override fun iterateContent(processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator, filter: VirtualFileFilter?): Boolean =
      ProjectFileIndexAccess.get(module.getProject()).iterateContentUnderDirectory(module.context.projectDirectoryFile(), processor, filter)
  override fun iterateContentUnderDirectory(dir: VirtualFile, processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator): Boolean =
      ProjectFileIndexAccess.get(module.getProject()).iterateContentUnderDirectory(dir, processor)
  override fun iterateContentUnderDirectory(dir: VirtualFile, processor: org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator, customFilter: VirtualFileFilter?): Boolean =
      ProjectFileIndexAccess.get(module.getProject()).iterateContentUnderDirectory(dir, processor, customFilter)
  override fun isInContent(fileOrDir: VirtualFile): Boolean = ProjectFileIndexAccess.get(module.getProject()).moduleForFile(fileOrDir) === module
  override fun isInSourceContent(fileOrDir: VirtualFile): Boolean =
      ProjectFileIndexAccess.get(module.getProject()).moduleForFile(fileOrDir) === module && ProjectFileIndexAccess.get(module.getProject()).isInSource(fileOrDir)
  override fun isInTestSourceContent(fileOrDir: VirtualFile): Boolean = false
  override fun isUnderSourceRootOfType(fileOrDir: VirtualFile, rootTypes: Set<out JpsModuleSourceRootType<*>>): Boolean =
      ProjectFileIndexAccess.get(module.getProject()).moduleForFile(fileOrDir) === module && ProjectFileIndexAccess.get(module.getProject()).isUnderSourceRootOfType(fileOrDir, rootTypes)
}

internal class LiveModuleManager(
    private val project: Project,
    private val moduleList: List<LiveProjectModule>,
) : ModuleManager() {
  private val byName = moduleList.associateBy { it.name }

  override fun newModule(filePath: String, moduleTypeId: String): Module = unsupported()
  override fun loadModule(filePath: String): Module = unsupported()
  override fun loadModule(file: Path): Module = unsupported()
  override fun disposeModule(module: Module) = unsupported<Unit>()
  override val modules: Array<Module>
    get() = moduleList.toTypedArray()
  override fun findModuleByName(name: String): Module? = byName[name]
  override val sortedModules: Array<Module>
    get() = moduleList.toTypedArray()
  override fun moduleDependencyComparator(): Comparator<Module> = compareBy { it.name }
  override fun getModuleDependentModules(module: Module): List<Module> = moduleList.filter { module in ModuleRootManager.getInstance(it).getDependencies() }
  override fun isModuleDependent(module: Module, onModule: Module): Boolean = onModule in ModuleRootManager.getInstance(module).getDependencies()
  override fun moduleGraph(): Graph<Module> = moduleGraph(false)
  override fun moduleGraph(includeTests: Boolean): Graph<Module> = moduleGraphOf(moduleList, includeTests)
  override fun getModifiableModel(): ModifiableModuleModel = unsupported()
  override val allModuleDescriptions: Collection<ModuleDescription>
    get() = moduleList.map { description(it) }
  override val unloadedModuleDescriptions: Collection<org.jetbrains.kotlin.com.intellij.openapi.module.UnloadedModuleDescription>
    get() = emptyList()
  override fun getUnloadedModuleDescription(moduleName: String) = null
  override fun getModuleGrouper(model: ModifiableModuleModel?): ModuleGrouper = ModuleGrouper.instanceFor(project)
  override suspend fun setUnloadedModules(unloadedModuleNames: List<String>) = unsupported<Unit>()
  override fun setUnloadedModulesSync(unloadedModuleNames: List<String>) = unsupported<Unit>()

  private fun description(module: LiveProjectModule): ModuleDescription =
      object : ModuleDescription {
        override fun getName(): String = module.name
        override fun getDependencyModuleNames(): List<String> = module.context.compileModulePaths
      }
}

private fun moduleGraphOf(modules: List<LiveProjectModule>, includeTests: Boolean): Graph<Module> = object : Graph<Module> {
  override fun getNodes(): Collection<Module> = modules
  override fun getIn(n: Module): Iterator<Module> = modules.filter { n in ModuleRootManager.getInstance(it).getDependencies() }.iterator()
  override fun getOut(n: Module): Iterator<Module> = ModuleRootManager.getInstance(n).getDependencies().iterator()
}

internal object ProjectFileIndexAccess {
  fun get(project: Project): LiveProjectFileIndex =
      project.getService(org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex::class.java) as LiveProjectFileIndex
}

private fun KotlinAnalysisModuleContext.projectDirectoryFile(): VirtualFile =
    VirtualFileManager.getInstance().findFileByNioPath(projectDirectory.toAbsolutePath().normalize())
        ?: error("Analysis module content root is not available: $projectDirectory")

private fun sourceOrderEntry(module: Module, rootModel: ModuleRootModel): OrderEntry =
    orderEntryProxy(ModuleSourceOrderEntry::class.java, module, "${module.name} sources", emptyList()) { method, _, _ ->
      if (method.name == "getRootModel") rootModel else null
    }

private fun moduleOrderEntry(owner: Module, dependency: Module, roots: List<Path>): OrderEntry =
    orderEntryProxy(ModuleOrderEntry::class.java, owner, dependency.name, roots) { method, _, _ ->
      when (method.name) {
        "getModule" -> dependency
        "getModuleName" -> dependency.name
        "isProductionOnTestDependency" -> false
        "setProductionOnTestDependency" -> Unit
        "isExported" -> true
        "setExported" -> Unit
        "getScope" -> DependencyScope.COMPILE
        "setScope" -> Unit
        else -> null
      }
    }

private fun libraryOrderEntry(owner: Module, name: String, classes: List<Path>, sources: List<Path>): OrderEntry =
    orderEntryProxy(LibraryOrderEntry::class.java, owner, name, classes + sources) { method, _, args ->
      when (method.name) {
        "getLibrary" -> null
        "isModuleLevel" -> true
        "getLibraryLevel" -> "project"
        "getLibraryName" -> name
        "isExported" -> true
        "setExported" -> Unit
        "getScope" -> DependencyScope.COMPILE
        "setScope" -> Unit
        "getRootFiles", "getFiles" -> rootFiles(args?.firstOrNull() as? OrderRootType, classes, sources)
        "getRootUrls" -> rootFiles(args?.firstOrNull() as? OrderRootType, classes, sources).map { it.url }.toTypedArray()
        else -> null
      }
    }

private fun jdkOrderEntry(owner: Module, roots: List<Path>): OrderEntry =
    orderEntryProxy(JdkOrderEntry::class.java, owner, "JDK", roots) { method, _, _ ->
      when (method.name) {
        "getJdk" -> null
        "getJdkName" -> "AndroidIDE-JDK"
        else -> null
      }
    }

private fun rootFiles(type: OrderRootType?, classes: List<Path>, sources: List<Path>): Array<VirtualFile> {
  val paths = if (type == OrderRootType.SOURCES) sources else classes
  return paths.mapNotNull(::analysisRootVirtualFile).toTypedArray()
}

private fun orderEntryProxy(
    type: Class<out OrderEntry>,
    owner: Module,
    name: String,
    roots: List<Path>,
    extra: (Method, Any?, Array<out Any?>?) -> Any?,
): OrderEntry {
  lateinit var entry: OrderEntry
  entry = Proxy.newProxyInstance(type.classLoader, arrayOf(type), InvocationHandler { proxy, method, args ->
    when (method.name) {
      "getOwnerModule" -> owner
      "getPresentableName" -> name
      "isValid" -> true
      "isSynthetic" -> false
      "getFiles", "getRootFiles", "getRootUrls" ->
          extra(method, entry, args) ?: when (method.name) {
            "getRootUrls" -> rootFiles(args?.firstOrNull() as? OrderRootType, roots, roots).map { it.url }.toTypedArray()
            else -> rootFiles(args?.firstOrNull() as? OrderRootType, roots, roots)
          }
      "accept" -> {
        @Suppress("UNCHECKED_CAST")
        val policy = args?.getOrNull(0) as RootPolicy<Any?>
        val initial = args?.getOrNull(1)
        when {
          type == ModuleSourceOrderEntry::class.java -> policy.visitModuleSourceOrderEntry(entry as ModuleSourceOrderEntry, initial)
          type == ModuleOrderEntry::class.java -> policy.visitModuleOrderEntry(entry as ModuleOrderEntry, initial)
          type == LibraryOrderEntry::class.java -> policy.visitLibraryOrderEntry(entry as LibraryOrderEntry, initial)
          type == JdkOrderEntry::class.java -> policy.visitJdkOrderEntry(entry as JdkOrderEntry, initial)
          else -> policy.visitOrderEntry(entry, initial)
        }
      }
      "compareTo" -> name.compareTo(args?.firstOrNull().toString())
      "hashCode" -> System.identityHashCode(entry)
      "equals" -> entry === args?.firstOrNull()
      "toString" -> name
      else -> extra(method, proxy, args)
    }
  }) as OrderEntry
  return entry
}

private fun <T> proxy(type: Class<T>, handler: (Any?, Method, Array<out Any?>?) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type), InvocationHandler { proxy, method, args -> handler(proxy, method, args) }) as T

private fun <T> unsupported(): T = throw UnsupportedOperationException("Analysis project model is read-only")