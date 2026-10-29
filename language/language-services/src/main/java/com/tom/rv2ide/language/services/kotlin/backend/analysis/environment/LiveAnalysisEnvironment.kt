@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import android.util.Log
import com.tom.rv2ide.javac.config.JavacConfigProvider
import com.tom.rv2ide.language.services.kotlin.backend.analysis.analysisBestEffort
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisModuleContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisProjectContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceRootDescriptor
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar.LiveAnalysisServiceRegistrar
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.AnalysisPackagePartProviderFactory
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveAnnotationsResolverFactory
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveDeclarationProviderFactory
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveDeclarationProviderMerger
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveDirectInheritorsProvider
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveModuleDependentsProvider
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LivePackageProviderFactory
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LivePackageProviderMerger
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveProjectStructureProvider
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslModelState
import com.tom.rv2ide.utils.Environment
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingModule
import org.jetbrains.kotlin.analysis.api.platform.declarations.createDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleAccessibilityChecker
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleAnnotationsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.ApplicationServiceRegistration
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCliJavaFileManagerImpl
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleFinder
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleResolver
import org.jetbrains.kotlin.cli.jvm.modules.JavaModuleGraph
import org.jetbrains.kotlin.analysis.api.standalone.base.java.KotlinStandaloneJavaModuleAccessibilityChecker
import org.jetbrains.kotlin.analysis.api.standalone.base.java.KotlinStandaloneJavaModuleAnnotationsProvider
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.util.io.URLUtil.JAR_SEPARATOR
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.SmartTypePointerManager
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.streams.asSequence
private const val ANDROID_DIAGNOSTIC_TAG = "ACS-KotlinAnalysis"

@OptIn(KaExperimentalApi::class, KaImplementationDetail::class, KaPlatformInterface::class, K1Deprecation::class)
internal class LiveAnalysisEnvironment(
    private val classpathProvider: KotlinProjectClasspathProvider,
    private val intellijPluginRoot: String,
) : AutoCloseable {
  private val disposable = Disposer.newDisposable("acs-kotlin-live-analysis")
  private val closed = AtomicBoolean(false)
  private val platform = JvmPlatforms.defaultJvmPlatform
  private val temporaryLibrarySourceRoots = mutableListOf<Path>()
  private val librarySourcesByBinary = java.util.IdentityHashMap<KaLibraryModule, KaLibrarySourceModule>()

  @Volatile
  var startFailure: Throwable? = null
    private set

  @Volatile
  var startFailureReason: String? = null
    private set

  lateinit var project: MockProject
    private set
  lateinit var session: StandaloneAnalysisAPISession
    private set
  lateinit var projectContext: KotlinAnalysisProjectContext
    private set
  lateinit var modules: List<KaModule>
    private set
  lateinit var sourceModulesByPath: Map<String, KaSourceModule>
    private set
  lateinit var sourceRegistry: LiveSourceFileRegistry
    private set
  lateinit var metadataIndex: KotlinSourceMetadataIndex
    private set
  lateinit var liveIndex: LiveSourceFileIndex
    private set
  lateinit var runtimeGraph: LiveAnalysisModuleGraph
    private set

  fun configureRefreshScheduler(schedule: (Path, () -> Unit) -> Unit) {
    if (::liveIndex.isInitialized) liveIndex.configureRefreshScheduler(schedule)
  }

  fun start(): Boolean {
    val workspace = classpathProvider.workspace ?: run {
      startFailureReason = "Kotlin Analysis workspace is unavailable"
      return false
    }
    projectContext = KotlinAnalysisProjectContext.create(workspace)
    if (projectContext.modules.none { it.sourceRoots.isNotEmpty() }) {
      startFailureReason = "Kotlin Analysis workspace has no source roots"
      return false
    }
    Environment.JAVA_HOME?.let { System.setProperty(JavacConfigProvider.PROP_ANDROIDIDE_JAVA_HOME, it.absolutePath) }

    val languageVersionSettings = createLanguageVersionSettings()
    val configuration = CompilerConfiguration().apply {
      put(CLIConfigurationKeys.INTELLIJ_PLUGIN_ROOT, intellijPluginRoot)
      this.languageVersionSettings = languageVersionSettings
      Environment.JAVA_HOME?.let {
        put(JVMConfigurationKeys.JDK_HOME, it)
        put(JVMConfigurationKeys.JDK_RELEASE, resolveJdkRelease(it))
      }
    }

    return try {
      val projectEnvironment = StandaloneProjectFactory.createProjectEnvironment(
          disposable,
          KotlinCoreApplicationEnvironmentMode.Production,
          configuration,
      )
      // Keep the shared Production application alive after this workspace is closed.
      KotlinApplicationEnvironmentPin.ensure(configuration)
      project = projectEnvironment.project
      project.installAnalysisProjectLock()
      val application = projectEnvironment.environment.application

      sourceRegistry = LiveSourceFileRegistry()
      metadataIndex = KotlinSourceMetadataIndex()
      val scriptModels = workspace.getGradleKotlinDslModel()
      val scriptDefinitionProvider = LiveGradleScriptDefinitionProvider(scriptModels)
      val scriptConfigurationsProvider = LiveGradleScriptConfigurationsProvider(project, scriptDefinitionProvider)
       val annotationsResolverFactory = LiveAnnotationsResolverFactory()
       val declarationFactory = LiveDeclarationProviderFactory()
       val declarationMerger = LiveDeclarationProviderMerger(declarationFactory)
       val packageFactory = LivePackageProviderFactory()
       val packageMerger = LivePackageProviderMerger(packageFactory)
      val moduleDependents = LiveModuleDependentsProvider()
      val packagePartFactory = AnalysisPackagePartProviderFactory()
      val provisionalStructure = LiveProjectStructureProvider()
      val directInheritorsProvider = LiveDirectInheritorsProvider()
      val registrar = LiveAnalysisServiceRegistrar(
          annotationsResolverFactory,
          declarationFactory,
          declarationMerger,
          packageFactory,
          packageMerger,
          moduleDependents,
          packagePartFactory,
          provisionalStructure,
          directInheritorsProvider,
          scriptDefinitionProvider,
          scriptConfigurationsProvider,
      )
      ApplicationServiceRegistration.registerWithCustomRegistration(application, listOf(registrar)) {
        registrar.registerApplicationServices(application)
      }

      runtimeGraph = LiveAnalysisModuleGraph.build(projectContext.modules)
      logMissingRoots(runtimeGraph)
val libraryModules = linkedMapOf<String, KaLibraryModule>()
       val librarySourceModules = linkedMapOf<String, KaLibrarySourceModule>()
       val librarySourceFiles = mutableListOf<org.jetbrains.kotlin.psi.KtFile>()
       val sourceModules = linkedMapOf<String, LiveSourceModule>()

      fun library(group: LiveAnalysisLibraryGroup): KaLibraryModule =
          libraryModules.getOrPut(group.identity) {
            val sourceVirtualFiles = librarySourceVirtualFiles(group.sourcePaths, group.identity)
            val sourceFiles = sourceVirtualFiles.mapNotNull { virtualFile ->
              runCatching {
                org.jetbrains.kotlin.com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
                    as? org.jetbrains.kotlin.psi.KtFile
              }.getOrNull()
            }
            librarySourceFiles += sourceFiles
            val pair = createLiveLibraryModulePair(
                coreApplicationEnvironment = projectEnvironment.environment,
                project = project,
                platform = platform,
                libraryName = group.identity,
                binaryRoots = group.rootKeys.map { it.path },
                sourceFiles = sourceVirtualFiles,
            )
            pair.source?.let {
              librarySourceModules[group.identity] = it
              librarySourcesByBinary[pair.binary] = it
            }
            pair.binary
          }

      val building = mutableSetOf<String>()
      fun source(context: KotlinAnalysisModuleContext): LiveSourceModule {
        sourceModules[context.modulePath]?.let { return it }
        check(building.add(context.modulePath)) { "Cyclic Kotlin module dependency: ${context.modulePath}" }
        val dependencies = runtimeGraph.dependenciesByModule[context.modulePath].orEmpty()
            .map { dependencyPath ->
              source(projectContext.module(dependencyPath) ?: error("Missing Kotlin Analysis module: $dependencyPath"))
            }
        val libraryDependencies = runtimeGraph.libraryGroupsByModule[context.modulePath].orEmpty()
            .map(::library)
        val module = LiveSourceModule(
            project = project,
            name = context.modulePath,
            sourceRootDescriptors = context.sourceRootDescriptors.map { root ->
              KotlinSourceRootDescriptor(
                  path = root.path.normalize(),
                  ownerModulePath = root.ownerModulePath,
                  sourceKind = when (root.role) {
                    com.tom.rv2ide.projects.models.ProjectRootRole.ORDINARY_SOURCE,
                    com.tom.rv2ide.projects.models.ProjectRootRole.JAVA_SOURCE -> KotlinSourceKind.ORDINARY
                    com.tom.rv2ide.projects.models.ProjectRootRole.GENERATED_SOURCE -> KotlinSourceKind.GENERATED
                    else -> KotlinSourceKind.UNKNOWN
                  },
                  origin = root.origin.name,
                  variantName = root.variantName,
              )
            },
            sourceRoots = context.sourceRoots.toSet(),
            registry = sourceRegistry,
            directRegularDependencies = (dependencies + libraryDependencies)
                .distinct()
                .sortedBy { moduleIdentity(it) },
            targetPlatform = platform,
            languageVersionSettings = languageVersionSettings,
        )
        building.remove(context.modulePath)
        sourceModules[context.modulePath] = module
        return module
      }

      modules = projectContext.modules.sortedBy { it.modulePath }.map(::source)
      sourceModulesByPath = sourceModules
      val scriptDependencies = linkedMapOf<Path, List<KaModule>>()
      val scriptLibraries = mutableListOf<KaLibraryModule>()
      val scriptSourceLibraries = mutableListOf<KaLibrarySourceModule>()
      if (scriptModels.schemaVersion == 1 && scriptModels.state in setOf(GradleKotlinDslModelState.VALID, GradleKotlinDslModelState.PARTIAL)) {
        scriptModels.builds
            .filter { it.state in setOf(GradleKotlinDslModelState.VALID, GradleKotlinDslModelState.PARTIAL) }
            .flatMap { build -> build.scripts.map { build.buildRootPath to it } }
            .groupBy { (_, script) -> Path.of(script.scriptPath).normalize() }
            .toSortedMap(compareBy(Path::toString))
            .forEach { (path, candidates) ->
              val candidate = candidates.singleOrNull()
              if (candidate == null) {
                KlsLogs.warn("Kotlin Analysis ambiguous script model: path={}, builds={}", path, candidates.map { it.first })
                return@forEach
              }
              val (buildRoot, script) = candidate
              if (script.state !in setOf(GradleKotlinDslModelState.VALID, GradleKotlinDslModelState.PARTIAL)) return@forEach
              val roots = script.classPath.map { Path.of(it).normalize() }.distinct()
              val missingRoots = roots.filterNot { java.nio.file.Files.exists(it) }
              if (missingRoots.isNotEmpty()) {
                KlsLogs.warn("Kotlin Analysis missing script binary roots: path={}, roots={}", path, missingRoots)
              }
              val existingRoots = roots.filter { java.nio.file.Files.exists(it) }
              val libraryName = "acs-script-dependencies:$buildRoot:$path"
              val sourceFiles = scriptSourceVirtualFiles(script.sourcePath)
              val pair = createLiveScriptLibraryModulePair(
                  coreApplicationEnvironment = projectEnvironment.environment,
                  project = project,
                  platform = platform,
                  libraryName = libraryName,
                  binaryRoots = existingRoots,
                  sourceFiles = sourceFiles,
              )
               val dependency = pair.binary
               pair.source?.let {
                 scriptSourceLibraries.add(it)
                 librarySourcesByBinary[dependency] = it
               }
               scriptLibraries.add(dependency)
               scriptDependencies[path] = listOf(dependency)

            }
      }
val allModules = modules +
           libraryModules.values.sortedBy { moduleIdentity(it) } +
           librarySourceModules.values.sortedBy { moduleIdentity(it) } +
           scriptLibraries + scriptSourceLibraries
       val scriptRegistry = LiveScriptModuleRegistry(
          projectEnvironment.environment,
          project,
          sourceRegistry,
          platform,
          languageVersionSettings,
          scriptDependencies,
      )
      val structure = LiveStaticProjectStructureProvider(project, allModules, sourceRegistry)

      StandaloneProjectFactory.registerServicesForProjectEnvironment(
          environment = projectEnvironment,
          projectStructureProvider = structure,
          languageVersionSettings = languageVersionSettings,
          jdkHome = Environment.JAVA_HOME?.toPath(),
      )
      registerJavaModuleServices(project)
      unregisterStaticServices(project)
      registrar.registerProjectExtensionPoints(project)
      registrar.registerProjectServices(project)
      registrar.registerProjectModelServices(project, disposable)

      liveIndex = LiveSourceFileIndex(project, sourceRegistry, metadataIndex)
      val projectStructure = project.getService(KotlinProjectStructureProvider::class.java) as LiveProjectStructureProvider
      projectStructure.setup(project, allModules, sourceRegistry)
      directInheritorsProvider.setup(project, liveIndex, metadataIndex)
      annotationsResolverFactory.setup(project, liveIndex, metadataIndex)
declarationFactory.setup(
          project = project,
          liveIndex = liveIndex,
          registry = sourceRegistry,
          metadataIndex = metadataIndex,
          coreApplicationEnvironment = projectEnvironment.environment,
librarySourceFiles = librarySourceFiles,
       )
       packageFactory.setup(project, metadataIndex, librarySourceFiles)
      moduleDependents.setup(allModules, runtimeGraph.dependentsByIdentity)
      val parser = KtPsiFactory(project, eventSystemEnabled = true)
      liveIndex.parser = parser
      liveIndex.onScriptFileCommitted = { file ->
        scriptRegistry.refresh(file)
        moduleDependents.refreshScriptModules(sourceRegistry.scriptModules())
      }
      liveIndex.onScriptModulesChanged = {
        moduleDependents.refreshScriptModules(sourceRegistry.scriptModules())
      }
      val libraryRoots = StandaloneProjectFactory.getAllBinaryRoots(
          (libraryModules.values + scriptLibraries).toList(),
          projectEnvironment.environment,
      )
      packagePartFactory.setup(languageVersionSettings, libraryRoots)
      allModules.filterIsInstance<LiveSourceModule>().forEach { module ->
        module.sourceRoots.flatMap(::sourcePaths).forEach(liveIndex::registerDiskFile)
      }
      scriptModels.builds.flatMap { it.scripts }
          .map { Path.of(it.scriptPath).normalize() }
          .distinct()
          .forEach(liveIndex::registerDiskFile)

       session = StandaloneAnalysisAPISession(projectEnvironment) {
          sourceModules.values.associateWith { module -> liveIndex.currentPsiFilesFor(module) }
        }
       Log.i(
           ANDROID_DIAGNOSTIC_TAG,
           "runtime graph=${System.identityHashCode(this)} project=${System.identityHashCode(project)} " +
               "libraryModules=${libraryModules.size} librarySourceModules=${librarySourceModules.size} " +
               "librarySourceFiles=${librarySourceFiles.size} tempSourceRoots=${temporaryLibrarySourceRoots.size} " +
               "declarationFactory=${System.identityHashCode(declarationFactory)} " +
               "packageFactory=${System.identityHashCode(packageFactory)} session=${System.identityHashCode(session)}",
       )
       diagnosticInternalState("runtime_start")
         true
} catch (t: Throwable) {
        startFailure = t
        startFailureReason = "Kotlin Analysis live environment failed to start"
        KlsLogs.error("Kotlin Analysis live environment failed to start", t)
        close()
        false
     }
   }

override fun close() {
      if (!closed.compareAndSet(false, true)) {
        return
      }
     if (::liveIndex.isInitialized) liveIndex.close()
     temporaryLibrarySourceRoots.toList().forEach(::deleteTemporarySourceRoot)
     temporaryLibrarySourceRoots.clear()
Disposer.dispose(disposable)
   }

  internal fun diagnosticInternalState(label: String): String {
    // TODO(ACS-ANALYSIS-EXPERIMENT): Remove reflective runtime-state diagnostics after the Scudo failure is localized.
    fun readProperty(target: Any, name: String): Any? = runCatching {
      val getter = target.javaClass.methods.firstOrNull { method ->
        method.name == "get" + name.replaceFirstChar { it.uppercase() } && method.parameterCount == 0
      }
      getter?.invoke(target) ?: run {
        var type: Class<*>? = target.javaClass
        while (type != null) {
          val field = type.declaredFields.firstOrNull { it.name == name }
          if (field != null) {
            field.isAccessible = true
            return@run field.get(target)
          }
          type = type.superclass
        }
        null
      }
    }.getOrNull()
    fun companionInstance(className: String): Any? = runCatching {
      val targetClass = Class.forName(className)
      val companion = targetClass.getField("Companion").get(null)
      companion.javaClass.methods
          .first { method -> method.name == "getInstance" && method.parameterCount == 1 }
          .invoke(companion, project)
    }.getOrNull()

    fun cacheSize(cache: Any?): String = runCatching {
      cache?.javaClass?.methods
          ?.first { method -> method.name == "getSize" && method.parameterCount == 0 }
          ?.invoke(cache)
          ?.toString()
          ?: "unavailable"
    }.getOrDefault("unavailable")

    fun service(className: String): Any? = runCatching {
      @Suppress("UNCHECKED_CAST")
      project.getService(Class.forName(className) as Class<Any>)
    }.getOrNull()

    val sessionCache = companionInstance("org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache")
    val storage = sessionCache?.let { readProperty(it, "storage") }
    val sourceCache = storage?.let { readProperty(it, "sourceCache") }
    val binaryCache = storage?.let { readProperty(it, "binaryCache") }
    val fallbackCache = storage?.let { readProperty(it, "libraryFallbackDependenciesCache") }
    val danglingCache = storage?.let { readProperty(it, "danglingFileSessionCache") }
    val unstableDanglingCache = storage?.let { readProperty(it, "unstableDanglingFileSessionCache") }

    val firSessionProvider = service("org.jetbrains.kotlin.analysis.api.fir.KaFirSessionProvider")
    val firProviderCacheSize = runCatching {
      if (firSessionProvider == null) {
        "unavailable"
      } else {
        val accessor = firSessionProvider.javaClass.methods
            .firstOrNull { method -> method.name == "access\$getCache\$p" && method.parameterCount == 1 }
            ?: return@runCatching "unavailable"
        cacheSize(accessor.invoke(null, firSessionProvider))
      }
    }.getOrDefault("unavailable")

    val psiModificationCount = runCatching {
      val trackerClass = Class.forName("org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker")
      val tracker = trackerClass.getMethod("getInstance", Class.forName("org.jetbrains.kotlin.com.intellij.openapi.project.Project"))
          .invoke(null, project)
      tracker.javaClass.getMethod("getModificationCount").invoke(tracker).toString()
    }.getOrDefault("unavailable")

    val firInBlockModificationCount = runCatching {
      val tracker = companionInstance("org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationTracker")
          ?: return@runCatching "unavailable"
      tracker.javaClass.getMethod("getModificationCount").invoke(tracker).toString()
    }.getOrDefault("unavailable")


    val state = "label=$label project=${System.identityHashCode(project)} " +
        "source=${cacheSize(sourceCache)} binary=${cacheSize(binaryCache)} " +
        "fallback=${cacheSize(fallbackCache)} dangling=${cacheSize(danglingCache)} " +
        "unstableDangling=${cacheSize(unstableDanglingCache)} " +
        "kaFirProvider=$firProviderCacheSize psiModification=$psiModificationCount " +
        "firInBlockModification=$firInBlockModificationCount"
    Log.i(ANDROID_DIAGNOSTIC_TAG, "analysis_internal_state $state")
    return state
  }

  internal fun attachedSourceDeclaration(session: KaSession, symbol: KaDeclarationSymbol): KtDeclaration? {
    val binaryModule = analysisBestEffort(null, com.tom.rv2ide.progress.ICancelChecker.NOOP) {
      with(session) { symbol.containingModule as? KaLibraryModule }
   } ?: return null
    val sourceModule = librarySourcesByBinary[binaryModule] ?: return null
    val provider = sourceModule.project.createDeclarationProvider(sourceModule.contentScope, sourceModule)

    return when (symbol) {
      is KaConstructorSymbol -> symbol.containingClassId?.let { classId ->
        (provider.getAllClassesByClassId(classId) + provider.getAllTypeAliasesByClassId(classId)).singleOrNull()
      }
      is KaClassLikeSymbol -> symbol.classId?.let { classId ->
        (provider.getAllClassesByClassId(classId) + provider.getAllTypeAliasesByClassId(classId)).singleOrNull()
      }
      is KaCallableSymbol -> {
        val callableId = symbol.callableId ?: return null
        val classId = callableId.classId
        if (classId == null) {
          val functions = provider.getTopLevelFunctions(callableId)
          val properties = provider.getTopLevelProperties(callableId)
          (functions + properties).singleOrNull()
        } else {
          val classes = provider.getAllClassesByClassId(classId)
          val declarations = classes.flatMap { klass ->
            klass.declarations
                .filterIsInstance<KtCallableDeclaration>()
                .filter { it.nameAsName == callableId.callableName }
          }
          declarations.singleOrNull()
        }
      }
      else -> null
    }
  }

  internal fun modulePathFor(path: Path): String? {
    val normalized = path.normalize()
    return projectContext.modules
        .asSequence()
        .flatMap { module ->
          module.sourceRoots.asSequence()
              .filter { root -> normalized == root || normalized.startsWith(root) }
              .map { root -> module.modulePath to root }
        }
        .maxByOrNull { (_, root) -> root.toString().length }
        ?.first
  }

  private fun moduleIdentity(module: KaModule): String = when (module) {
    is KaSourceModule -> module.name
    is KaLibraryModule -> module.libraryName
    is KaLibrarySourceModule -> module.libraryName
    else -> module.stableModuleName ?: module.javaClass.name
  }

  private fun logMissingRoots(graph: LiveAnalysisModuleGraph) {
    graph.missingRootsByModule.forEach { (modulePath, missingRoots) ->
      KlsLogs.warn(
          "Kotlin Analysis missing binary roots: module={}, roots={}, provenance={}",
          modulePath,
          missingRoots.map { it.path },
          missingRoots.map { it.path to graph.libraryProvenance[it] },
      )
    }
  }

  private fun registerJavaModuleServices(project: MockProject) {
    val jdkHome = Environment.JAVA_HOME ?: return
    val javaFileManager = project.getService(JavaFileManager::class.java) as? KotlinCliJavaFileManagerImpl
    if (javaFileManager == null) {
      KlsLogs.warn("Kotlin Analysis Java module services skipped: Java file manager unavailable")
      return
    }
    runCatching {
      val moduleFinder = CliJavaModuleFinder(
          jdkHome,
          MessageCollector.NONE,
          javaFileManager,
          project,
          resolveJdkRelease(jdkHome),
      )
      val moduleGraph = JavaModuleGraph(moduleFinder)
      val moduleResolver = CliJavaModuleResolver(
          moduleGraph,
          emptyList(),
          moduleFinder.systemModules.toList(),
          project,
      )
      project.picoContainer.unregisterComponent(KotlinJavaModuleAccessibilityChecker::class.java.name)
      project.picoContainer.unregisterComponent(KotlinJavaModuleAnnotationsProvider::class.java.name)
      project.registerService(
          KotlinJavaModuleAccessibilityChecker::class.java,
          KotlinStandaloneJavaModuleAccessibilityChecker(moduleResolver),
      )
      project.registerService(
          KotlinJavaModuleAnnotationsProvider::class.java,
          KotlinStandaloneJavaModuleAnnotationsProvider(moduleResolver),
      )
    }.onFailure { error ->
      KlsLogs.warn("Kotlin Analysis Java module services installation skipped", error)
    }
  }

  private fun createLanguageVersionSettings(): LanguageVersionSettings =
      LanguageVersionSettingsImpl(LanguageVersion.LATEST_STABLE, ApiVersion.LATEST)

  private fun unregisterStaticServices(project: MockProject) {
    project.picoContainer.unregisterComponent(KotlinProjectStructureProvider::class.java.name)
    project.picoContainer.unregisterComponent(KotlinModuleDependentsProvider::class.java.name)
    project.picoContainer.unregisterComponent(SmartTypePointerManager::class.java.name)
  }

  private fun sourcePaths(root: Path): List<Path> = runCatching {
    java.nio.file.Files.walk(root).use { stream ->
      stream.iterator().asSequence()
          .filter { java.nio.file.Files.isRegularFile(it) }
          .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".kts") }
          .toList()
    }
  }.getOrDefault(emptyList())
  private fun scriptSourceVirtualFiles(paths: Collection<String>): List<VirtualFile> = buildList {
    paths.map { Path.of(it).toAbsolutePath().normalize() }.forEach { path ->
      if (java.nio.file.Files.isDirectory(path)) {
        addAll(sourcePaths(path).mapNotNull { VirtualFileManager.getInstance().findFileByNioPath(it) })
      } else if (java.nio.file.Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
        val jarFileSystem = StandardFileSystems.jar() as? CoreJarFileSystem ?: return@forEach
        val root = jarFileSystem.refreshAndFindFileByPath(path.toString() + JAR_SEPARATOR) ?: return@forEach
        val files = mutableListOf<VirtualFile>()
        org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively(
            root,
            { true },
        ) { file ->
          if (!file.isDirectory && (file.name.endsWith(".kt") || file.name.endsWith(".kts"))) files += file
          true
        }
        addAll(files)
      } else if (java.nio.file.Files.isRegularFile(path)) {
        VirtualFileManager.getInstance().findFileByNioPath(path)?.let(::add)
      }
    }
  }.distinct()

  private fun librarySourceVirtualFiles(paths: Collection<Path>, group: String): List<VirtualFile> = buildList {
    paths.map { it.toAbsolutePath().normalize() }.forEach { archive ->
      val exists = java.nio.file.Files.isRegularFile(archive)
      if (!exists) return@forEach
      if (archive.toString().endsWith(".jar", ignoreCase = true)) {
        val root = extractKotlinSources(archive) ?: return@forEach
        val sourcePaths = sourcePaths(root)
        addAll(sourcePaths.mapNotNull { VirtualFileManager.getInstance().findFileByNioPath(it) })
      } else if (archive.toString().endsWith(".kt", ignoreCase = true) || archive.toString().endsWith(".kts", ignoreCase = true)) {
        VirtualFileManager.getInstance().findFileByNioPath(archive)?.let(::add)
      }
    }
  }.distinct()
private fun extractKotlinSources(archive: Path): Path? {
    val outputRoot = java.nio.file.Files.createTempDirectory("acs-kotlin-library-sources-")
    var extractedFiles = 0
    var extractedBytes = 0L
    try {
      ZipFile(archive.toFile()).use { zip ->
        val root = outputRoot.toAbsolutePath().normalize()
        zip.entries().asSequence()
            .filter { !it.isDirectory && (it.name.endsWith(".kt") || it.name.endsWith(".kts")) }
            .forEach { entry ->
              val output = root.resolve(entry.name).normalize()
              if (!output.startsWith(root)) return@forEach
              java.nio.file.Files.createDirectories(output.parent)
              zip.getInputStream(entry).use { input ->
                java.nio.file.Files.newOutputStream(output).use { outputStream ->
                  extractedBytes += input.copyTo(outputStream)
                }
              }
              extractedFiles++
            }
      }
      val sourcePaths = sourcePaths(outputRoot)
      if (sourcePaths.isEmpty()) {
        deleteTemporarySourceRoot(outputRoot)
        return null
      }
      temporaryLibrarySourceRoots.add(outputRoot)
      return outputRoot
    } catch (t: Throwable) {
      KlsLogs.warn("Kotlin Analysis library source archive extraction failed", t)
      deleteTemporarySourceRoot(outputRoot)
      return null
    }
  }

  private fun deleteTemporarySourceRoot(root: Path) {
    runCatching {
      if (java.nio.file.Files.exists(root)) {
        java.nio.file.Files.walk(root).sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
      }
    }
  }

  private fun resolveJdkRelease(jdkHome: java.io.File): Int =
      Regex("java-(\\d+)").find(jdkHome.name)?.groupValues?.get(1)?.toIntOrNull() ?: 17
}