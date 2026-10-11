@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment


import com.tom.rv2ide.javac.config.JavacConfigProvider
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisModuleContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisProjectContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
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
import com.tom.rv2ide.utils.Environment
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.ApplicationServiceRegistration
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.SmartTypePointerManager
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.streams.asSequence

@OptIn(KaImplementationDetail::class, KaPlatformInterface::class, K1Deprecation::class)
internal class LiveAnalysisEnvironment(
    private val classpathProvider: KotlinProjectClasspathProvider,
    private val intellijPluginRoot: String,
) : AutoCloseable {
  private val disposable = Disposer.newDisposable("acs-kotlin-live-analysis")
  private val closed = AtomicBoolean(false)
  private val platform = JvmPlatforms.defaultJvmPlatform

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

  fun start(): Boolean {
    val workspace = classpathProvider.workspace ?: return false
    projectContext = KotlinAnalysisProjectContext.create(workspace, classpathProvider)
    if (projectContext.modules.none { it.sourceRoots.isNotEmpty() }) return false
    Environment.JAVA_HOME?.let { System.setProperty(JavacConfigProvider.PROP_ANDROIDIDE_JAVA_HOME, it.absolutePath) }

    val configuration = CompilerConfiguration().apply {
      put(CLIConfigurationKeys.INTELLIJ_PLUGIN_ROOT, intellijPluginRoot)
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
      val annotationsResolverFactory = LiveAnnotationsResolverFactory()
      val declarationFactory = LiveDeclarationProviderFactory()
      val declarationMerger = LiveDeclarationProviderMerger(project)
      val packageFactory = LivePackageProviderFactory()
      val packageMerger = LivePackageProviderMerger(project)
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
      )
      ApplicationServiceRegistration.registerWithCustomRegistration(application, listOf(registrar)) {
        registrar.registerApplicationServices(application)
      }

      val libraryModules = linkedMapOf<String, KaLibraryModule>()
      val sourceModules = linkedMapOf<String, LiveSourceModule>()

      fun library(context: KotlinAnalysisModuleContext): KaLibraryModule =
          libraryModules.getOrPut(context.modulePath) {
            val builder = org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder(
                projectEnvironment.environment,
                project,
            )
            builder.platform = platform
            builder.run {
              buildKtLibraryModule {
                libraryName = "acs-classpath-${context.modulePath}"
                // Qualify the outer platform to avoid reading the builder's uninitialized lateinit property of the same name.
                this.platform = this@LiveAnalysisEnvironment.platform
                addBinaryRoots((context.binaryRoots + context.bootClasspaths).distinct())
              }
            }
          }

      val building = mutableSetOf<String>()
      fun source(context: KotlinAnalysisModuleContext): LiveSourceModule {
        sourceModules[context.modulePath]?.let { return it }
        check(building.add(context.modulePath)) { "Cyclic Kotlin module dependency: ${context.modulePath}" }
        val dependencies = context.compileModulePaths
            .filter { it != context.modulePath && it !in building }
            .mapNotNull { projectContext.module(it) }
            .map(::source)
        val module = LiveSourceModule(
            project = project,
            name = context.modulePath,
            sourceRoots = context.sourceRoots.toSet(),
            registry = sourceRegistry,
            directRegularDependencies = buildList {
              add(library(context))
              addAll(dependencies)
            },
            targetPlatform = platform,
        )
        building.remove(context.modulePath)
        sourceModules[context.modulePath] = module
        return module
      }

      modules = projectContext.modules.map(::source)
      sourceModulesByPath = sourceModules
      val allModules = modules + libraryModules.values
      val structure = LiveStaticProjectStructureProvider(project, allModules, sourceRegistry)

      StandaloneProjectFactory.registerServicesForProjectEnvironment(
          projectEnvironment,
          structure,
          jdkHome = Environment.JAVA_HOME?.toPath(),
      )
      unregisterStaticServices(project)
      registrar.registerProjectExtensionPoints(project)
      registrar.registerProjectServices(project)
      registrar.registerProjectModelServices(project, disposable)

      liveIndex = LiveSourceFileIndex(project, sourceRegistry, metadataIndex)
      val projectStructure = project.getService(KotlinProjectStructureProvider::class.java) as LiveProjectStructureProvider
      projectStructure.setup(project, allModules, sourceRegistry)
      directInheritorsProvider.setup(project, liveIndex)
      annotationsResolverFactory.setup(project, liveIndex, metadataIndex)
      declarationFactory.setup(project, liveIndex, sourceRegistry, metadataIndex)
      packageFactory.setup(project, metadataIndex)
      moduleDependents.setup(allModules)
      val parser = KtPsiFactory(project, eventSystemEnabled = true)
      liveIndex.parser = parser
      val libraryRoots = StandaloneProjectFactory.getAllBinaryRoots(
          libraryModules.values.toList(),
          projectEnvironment.environment,
      )
      packagePartFactory.setup(org.jetbrains.kotlin.config.LanguageVersionSettingsImpl.DEFAULT, libraryRoots)
      allModules.filterIsInstance<LiveSourceModule>().forEach { module ->
        module.sourceRoots.flatMap(::sourcePaths).forEach(liveIndex::registerDiskFile)
      }

      session = StandaloneAnalysisAPISession(projectEnvironment) {
        sourceModules.values.associateWith { module -> liveIndex.currentPsiFilesFor(module) }
      }
      true
    } catch (t: Throwable) {
      KlsLogs.error("Kotlin Analysis live environment failed to start", t)
      close()
      false
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    if (::liveIndex.isInitialized) liveIndex.close()
    Disposer.dispose(disposable)
  }

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

  private fun resolveJdkRelease(jdkHome: java.io.File): Int =
      Regex("java-(\\d+)").find(jdkHome.name)?.groupValues?.get(1)?.toIntOrNull() ?: 17
}