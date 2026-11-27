package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisProjectContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.AnalysisModificationTrackerFactory
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager
import org.jetbrains.kotlin.com.intellij.openapi.project.IncompleteDependenciesService
import org.jetbrains.kotlin.com.intellij.lang.findUsages.LanguageFindUsages
import org.jetbrains.kotlin.com.intellij.lang.cacheBuilder.CacheBuilderEP
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.util.ClassExtensionPoint
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdIndexer
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.idCache.JavaIdIndexer
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceContributor
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.PsiReferenceContributorEP
import org.jetbrains.kotlin.com.intellij.psi.search.UseScopeEnlarger
import org.jetbrains.kotlin.com.intellij.psi.search.ScopeOptimizer
import org.jetbrains.kotlin.com.intellij.lang.findUsages.FindUsagesProvider
import org.jetbrains.kotlin.com.intellij.lang.java.JavaFindUsagesProvider
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.psi.ElementManipulator
import org.jetbrains.kotlin.com.intellij.psi.ElementManipulators
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.impl.JavaReflectionReferenceContributor
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.impl.manipulators.StringLiteralManipulator
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.CacheManager
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.IndexCacheManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.search.PsiSearchHelperImpl
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileEditorManager
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootManager
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectRootModificationTracker
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionManager
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionSuppressor
import org.jetbrains.kotlin.com.intellij.codeInspection.LanguageInspectionSuppressors
import org.jetbrains.kotlin.com.intellij.codeInspection.ex.InspectionElementsMerger
import org.jetbrains.kotlin.com.intellij.codeInspection.lang.InspectionExtensionsFactory
import org.jetbrains.kotlin.com.intellij.codeInspection.deadCode.DeprecatedEntryPoint
import org.jetbrains.kotlin.com.intellij.codeInspection.ex.EntryPointsManagerBase
import org.jetbrains.kotlin.com.intellij.codeInspection.reference.EntryPoint
import org.jetbrains.kotlin.com.intellij.codeInspection.reference.UnusedDeclarationFixProvider
import org.jetbrains.kotlin.com.intellij.codeInsight.daemon.ImplicitUsageProvider
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.impl.AtomicReferenceImplicitUsageProvider
import org.jetbrains.kotlin.com.intellij.profile.codeInspection.InspectionProjectProfileManager
import org.jetbrains.kotlin.com.intellij.codeInsight.highlighting.JavaReadWriteAccessDetector
import org.jetbrains.kotlin.com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import org.jetbrains.kotlin.com.intellij.openapi.extensions.DefaultPluginDescriptor
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginId
import org.jetbrains.kotlin.com.intellij.lang.LanguageExtensionPoint
import org.jetbrains.kotlin.com.intellij.psi.PsiNameHelper
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiNameHelperImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiJavaModuleModificationTracker
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.kotlin.com.intellij.platform.backend.workspace.WorkspaceModel
import org.jetbrains.kotlin.com.intellij.platform.indexing.BinaryFileSourceProvider

import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.AnalysisPackagePartProviderFactory
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.AnalysisPermissionOptions
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.AnalysisPlatformSettings
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveAnnotationsResolverFactory
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveDeclarationProviderFactory
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveDeclarationProviderMerger
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveDirectInheritorsProvider
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveModuleDependentsProvider
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LivePackageProviderFactory
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LivePackageProviderMerger
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.LiveProjectStructureProvider
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.services.LLFirElementByPsiElementChooser
import org.jetbrains.kotlin.analysis.api.standalone.base.services.LLStandaloneFirElementByPsiElementChooser
import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.NoOpAsyncExecutionService
import org.jetbrains.kotlin.com.intellij.openapi.application.AsyncExecutionService
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinReadActionConfinementLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider

import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.AnalysisApiSimpleServiceRegistrar
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.PluginStructureProvider
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.com.intellij.codeInsight.CustomExceptionHandler
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.mock.MockApplication
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeEvent
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeListener
import com.tom.rv2ide.language.services.kotlin.backend.analysis.source.LiveJavaElementFinder
import com.tom.rv2ide.language.services.kotlin.backend.analysis.source.LiveJavaFileManager
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager
import org.jetbrains.kotlin.com.intellij.psi.search.PsiSearchHelper
import org.jetbrains.kotlin.com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex
import org.jetbrains.kotlin.com.intellij.psi.SmartTypePointerManager
import org.jetbrains.kotlin.com.intellij.psi.augment.PsiAugmentProvider
import org.jetbrains.kotlin.com.intellij.psi.impl.RecordAugmentProvider
import org.jetbrains.kotlin.com.intellij.psi.impl.search.MethodSuperSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.MethodDeepestSuperSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.AllClassesSearchExecutor
import org.jetbrains.kotlin.com.intellij.psi.impl.search.JavaClassInheritorsSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.JavaOverridingMethodsSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.MethodUsagesSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.SearcherQueryExecutor
import org.jetbrains.kotlin.com.intellij.psi.impl.search.CachesBasedRefSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.NonPhysicalReferenceSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.VariableInIncompleteCodeSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.SimpleAccessorReferenceSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.SPIReferencesSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.ConstructorReferencesSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.PsiAnnotationMethodReferencesSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.JavaRecordComponentSearcher
import org.jetbrains.kotlin.com.intellij.psi.impl.search.CustomPropertyScopeProvider
import org.jetbrains.kotlin.com.intellij.psi.search.searches.AllClassesSearch
import org.jetbrains.kotlin.com.intellij.psi.search.searches.ClassInheritorsSearch
import org.jetbrains.kotlin.com.intellij.psi.search.searches.OverridingMethodsSearch
import org.jetbrains.kotlin.com.intellij.psi.search.searches.DeepestSuperMethodsSearch
import org.jetbrains.kotlin.com.intellij.psi.search.searches.MethodReferencesSearch
import org.jetbrains.kotlin.com.intellij.psi.impl.source.JShellPsiAugmentProvider
import org.jetbrains.kotlin.com.intellij.psi.search.searches.DirectClassInheritorsSearch
import org.jetbrains.kotlin.com.intellij.psi.search.searches.ImplicitClassSearch
import org.jetbrains.kotlin.com.intellij.psi.search.searches.SuperMethodsSearch
import org.jetbrains.kotlin.com.intellij.util.QueryExecutor
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartTypePointerManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl
import org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport
import org.jetbrains.kotlin.com.intellij.pom.PomModel
import org.jetbrains.kotlin.com.intellij.pom.core.impl.PomModelImpl
import org.jetbrains.kotlin.com.intellij.pom.tree.TreeAspect
import org.jetbrains.kotlin.load.java.structure.impl.source.JavaElementSourceFactory
import org.jetbrains.kotlin.scripting.definitions.ScriptConfigurationsProvider
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import org.jetbrains.kotlin.load.java.structure.impl.source.JavaFixedElementSourceFactory
import org.jetbrains.uast.UastLanguagePlugin
import org.jetbrains.uast.java.JavaUastLanguagePlugin

@OptIn(KaImplementationDetail::class)
internal class LiveAnalysisServiceRegistrar(
    private val projectContext: KotlinAnalysisProjectContext,
    private val sourceRegistry: LiveSourceFileRegistry,
    private val annotationsResolverFactory: LiveAnnotationsResolverFactory,
    private val declarationProviderFactory: LiveDeclarationProviderFactory,
    private val declarationProviderMerger: LiveDeclarationProviderMerger,
    private val packageProviderFactory: LivePackageProviderFactory,
    private val packageProviderMerger: LivePackageProviderMerger,
    private val moduleDependentsProvider: LiveModuleDependentsProvider,
    private val packagePartProviderFactory: AnalysisPackagePartProviderFactory,
    private val projectStructureProvider: LiveProjectStructureProvider,
    private val directInheritorsProvider: LiveDirectInheritorsProvider,
    private val scriptDefinitionProvider: ScriptDefinitionProvider,
    private val scriptConfigurationsProvider: ScriptConfigurationsProvider,
) : AnalysisApiSimpleServiceRegistrar() {
  companion object {
    const val PLUGIN_PATH = "/META-INF/acs-kotlin/acs-kotlin.xml"
  }

  override fun registerApplicationServices(application: MockApplication) {
    PluginStructureProvider.registerApplicationServices(application, PLUGIN_PATH)
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        UastLanguagePlugin.EP,
        UastLanguagePlugin::class.java,
    )
    application.extensionArea.getExtensionPoint(UastLanguagePlugin.EP)
        .registerExtension(JavaUastLanguagePlugin(), application)
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        "org.jetbrains.kotlin.com.intellij.cacheBuilder",
        CacheBuilderEP::class.java,
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        "org.jetbrains.kotlin.com.intellij.idIndexer",
        FileTypeExtensionPoint::class.java,
    )
    val idIndexerPoint = application.extensionArea
        .getExtensionPoint<FileTypeExtensionPoint<IdIndexer>>("org.jetbrains.kotlin.com.intellij.idIndexer")
    val javaPlugin = DefaultPluginDescriptor(
        PluginId.getId("com.intellij.java"), JavaIdIndexer::class.java.classLoader,
    )
    if (idIndexerPoint.extensionList.none { it.filetype == "JAVA" }) {
      val indexer = FileTypeExtensionPoint<IdIndexer>("JAVA", JavaIdIndexer()).apply {
        setPluginDescriptor(javaPlugin)
      }
      idIndexerPoint.registerExtension(indexer, javaPlugin, application)
    }
    // Replace the official CLASS bean with the fixed indexer; JAVA keeps JavaIdIndexer.
    val existingClassIndexers = idIndexerPoint.extensionList.filter { it.filetype == "CLASS" }
    if (existingClassIndexers.none { it.instance is LiveJavaClassIdIndexer }) {
      existingClassIndexers.forEach { idIndexerPoint.unregisterExtension(it) }
      val classIndexer = FileTypeExtensionPoint<IdIndexer>("CLASS", LiveJavaClassIdIndexer()).apply {
        setPluginDescriptor(javaPlugin)
      }
      idIndexerPoint.registerExtension(classIndexer, javaPlugin, application)
    }
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        BinaryFileSourceProvider.EP,
        BinaryFileSourceProvider::class.java,
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        ElementManipulators.EP_NAME,
        ClassExtensionPoint::class.java,
    )
    val literalClass = PsiLiteralExpression::class.java.name
    val elementManipulatorPoint = application.extensionArea
        .getExtensionPoint<ClassExtensionPoint<ElementManipulator<*>>>(ElementManipulators.EP_NAME)
    if (elementManipulatorPoint.extensionList.none { it.psiElementClass == literalClass }) {
      val literalManipulator = ClassExtensionPoint<ElementManipulator<*>>().apply {
        psiElementClass = literalClass
        implementationClass = StringLiteralManipulator::class.java.name
        setPluginDescriptor(javaPlugin)
      }
      elementManipulatorPoint.registerExtension(literalManipulator, javaPlugin, application)
    }
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        PsiReferenceContributor.EP_NAME,
        PsiReferenceContributorEP::class.java,
    )
    val javaLanguageId = JavaLanguage.INSTANCE.id
    val reflectionContributorClass = JavaReflectionReferenceContributor::class.java.name
    val referenceContributorPoint = application.extensionArea.getExtensionPoint(PsiReferenceContributor.EP_NAME)
    if (referenceContributorPoint.extensionList.none {
          it is PsiReferenceContributorEP && it.language == javaLanguageId &&
              it.implementationClass == reflectionContributorClass
        }) {
      val reflectionContributor = PsiReferenceContributorEP().apply {
        language = javaLanguageId
        implementationClass = reflectionContributorClass
        setPluginDescriptor(javaPlugin)
      }
      referenceContributorPoint.registerExtension(reflectionContributor, javaPlugin, application)
    }
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        UseScopeEnlarger.EP_NAME,
        UseScopeEnlarger::class.java,
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        "org.jetbrains.kotlin.com.intellij.useScopeOptimizer",
        ScopeOptimizer::class.java,
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        PsiSearchHelper.CODE_USAGE_SCOPE_OPTIMIZER_EP_NAME,
        ScopeOptimizer::class.java,
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        "org.jetbrains.kotlin.com.intellij.searcher",
        ClassExtensionPoint::class.java,
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        "org.jetbrains.kotlin.com.intellij.lang.findUsagesProvider",
        LanguageExtensionPoint::class.java,
    )
    val javaFindUsagesProvider = LanguageExtensionPoint<FindUsagesProvider>().apply {
      language = "JAVA"
      implementationClass = JavaFindUsagesProvider::class.java.name
      setPluginDescriptor(DefaultPluginDescriptor(PluginId.getId("com.intellij.java"), JavaFindUsagesProvider::class.java.classLoader))
    }
    if (application.extensionArea.getExtensionPoint<LanguageExtensionPoint<FindUsagesProvider>>("org.jetbrains.kotlin.com.intellij.lang.findUsagesProvider")
            .extensionList.none { it.language == "JAVA" }) {
      application.extensionArea.getExtensionPoint<LanguageExtensionPoint<FindUsagesProvider>>("org.jetbrains.kotlin.com.intellij.lang.findUsagesProvider")
          .registerExtension(javaFindUsagesProvider, application)
      LanguageFindUsages.INSTANCE.invalidateCacheForExtension("JAVA")
    }
    if (!application.extensionArea.hasExtensionPoint(InspectionExtensionsFactory.EP_NAME.name)) {
      CoreApplicationEnvironment.registerExtensionPoint(
          application.extensionArea,
          InspectionExtensionsFactory.EP_NAME,
          InspectionExtensionsFactory::class.java,
      )
    }
    if (!application.extensionArea.hasExtensionPoint("org.jetbrains.kotlin.com.intellij.lang.inspectionSuppressor")) {
      CoreApplicationEnvironment.registerExtensionPoint(
          application.extensionArea,
          "org.jetbrains.kotlin.com.intellij.lang.inspectionSuppressor",
          LanguageExtensionPoint::class.java,
      )
    }
    if (!application.extensionArea.hasExtensionPoint(InspectionElementsMerger.EP_NAME.name)) {
      CoreApplicationEnvironment.registerExtensionPoint(
          application.extensionArea,
          InspectionElementsMerger.EP_NAME,
          InspectionElementsMerger::class.java,
      )
    }
    if (!application.extensionArea.hasExtensionPoint(ReadWriteAccessDetector.EP_NAME.name)) {
      CoreApplicationEnvironment.registerExtensionPoint(
          application.extensionArea,
          ReadWriteAccessDetector.EP_NAME,
          ReadWriteAccessDetector::class.java,
      )
    }
    val readWriteAccessPoint = application.extensionArea.getExtensionPoint(ReadWriteAccessDetector.EP_NAME)
    if (readWriteAccessPoint.extensionList.none { it is JavaReadWriteAccessDetector }) {
      readWriteAccessPoint.registerExtension(JavaReadWriteAccessDetector(), application)
    }
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        ImplicitUsageProvider.EP_NAME,
        ImplicitUsageProvider::class.java,
    )
    application.extensionArea.getExtensionPoint(ImplicitUsageProvider.EP_NAME)
        .registerExtension(AtomicReferenceImplicitUsageProvider(), application)
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        EntryPointsManagerBase.DEAD_CODE_EP_NAME,
        EntryPoint::class.java,
    )
    application.extensionArea.getExtensionPoint(EntryPointsManagerBase.DEAD_CODE_EP_NAME)
        .registerExtension(DeprecatedEntryPoint(), application)
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        UnusedDeclarationFixProvider.EP_NAME,
        UnusedDeclarationFixProvider::class.java,
    )
    val suppressorPoint = application.extensionArea
        .getExtensionPoint<LanguageExtensionPoint<InspectionSuppressor>>("org.jetbrains.kotlin.com.intellij.lang.inspectionSuppressor")
    val javaSuppressorImplementation = "org.jetbrains.kotlin.com.intellij.codeInspection.JavaInspectionSuppressor"
    var javaInspectionSuppressor: LanguageExtensionPoint<InspectionSuppressor>? =
        suppressorPoint.extensionList.firstOrNull { it.implementationClass == javaSuppressorImplementation }
    if (javaInspectionSuppressor == null) {
      val javaPluginDescriptor = DefaultPluginDescriptor(
          PluginId.getId("com.intellij.java.analysis"),
          InspectionSuppressor::class.java.classLoader,
      )
      javaInspectionSuppressor = LanguageExtensionPoint<InspectionSuppressor>().apply {
        language = "JAVA"
        implementationClass = javaSuppressorImplementation
        setPluginDescriptor(javaPluginDescriptor)
      }
      suppressorPoint.registerExtension(javaInspectionSuppressor, javaPluginDescriptor, application)
    }
    LanguageInspectionSuppressors.INSTANCE.invalidateCacheForExtension("JAVA")
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        DocumentWriteAccessGuard.EP_NAME,
        AnalysisDocumentWriteAccessGuard::class.java,
    )
    // TODO(ACS-ANALYSIS-EXPERIMENT): Validate checked-exception diagnostics and throws/catch handling on device.
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        CustomExceptionHandler.KEY,
        CustomExceptionHandler::class.java,
    )
    KlsLogs.info(
        "Kotlin Analysis Java exception extension registered: application={}, point={}",
        System.identityHashCode(application),
        CustomExceptionHandler.KEY.name,
    )
    application.registerService(KotlinAnalysisPermissionOptions::class.java, AnalysisPermissionOptions::class.java)
    application.registerService(AsyncExecutionService::class.java, NoOpAsyncExecutionService::class.java)
    application.registerService(FileBasedIndex::class.java, LiveFileBasedIndex())
    // Java inspections query this index when checking inherited methods for redundant throws.
    application.registerService(StubIndex::class.java, LiveStubIndex())
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        ImplicitClassSearch.EP_NAME.name,
        QueryExecutor::class.java,
    )
    application.extensionArea.getExtensionPoint(ImplicitClassSearch.EP_NAME)
        .registerExtension(LiveImplicitClassesSearcher(), application)
    KlsLogs.info(
        "Kotlin Analysis Java search extension registered: application={}, point={}, executor={}",
        System.identityHashCode(application),
        ImplicitClassSearch.EP_NAME.name,
        LiveImplicitClassesSearcher::class.java.name,
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        DirectClassInheritorsSearch.EP_NAME.name,
        QueryExecutor::class.java,
    )
    application.extensionArea.getExtensionPoint(DirectClassInheritorsSearch.EP_NAME)
        .registerExtension(LiveJavaDirectInheritorsSearcher(), application)
    KlsLogs.info(
        "Kotlin Analysis Java search extension registered: application={}, point={}, executor={}",
        System.identityHashCode(application),
        DirectClassInheritorsSearch.EP_NAME.name,
        LiveJavaDirectInheritorsSearcher::class.java.name,
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        AllClassesSearch.EP_NAME.name,
        QueryExecutor::class.java,
    )
    application.extensionArea.getExtensionPoint(AllClassesSearch.EP_NAME)
        .registerExtension(AllClassesSearchExecutor(), application)
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        ClassInheritorsSearch.EP_NAME.name,
        QueryExecutor::class.java,
    )
    application.extensionArea.getExtensionPoint(ClassInheritorsSearch.EP_NAME)
        .registerExtension(JavaClassInheritorsSearcher(), application)
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        OverridingMethodsSearch.EP_NAME.name,
        QueryExecutor::class.java,
    )
    application.extensionArea.getExtensionPoint(OverridingMethodsSearch.EP_NAME)
        .registerExtension(JavaOverridingMethodsSearcher(), application)
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        SuperMethodsSearch.EP_NAME.name,
        QueryExecutor::class.java,
    )
    application.extensionArea.getExtensionPoint(SuperMethodsSearch.EP_NAME)
        .registerExtension(MethodSuperSearcher(), application)
    KlsLogs.info(
        "Kotlin Analysis Java search extension registered: application={}, point={}, executor={}",
        System.identityHashCode(application),
        SuperMethodsSearch.EP_NAME.name,
        MethodSuperSearcher::class.java.name,
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        DeepestSuperMethodsSearch.EP_NAME.name,
        QueryExecutor::class.java,
    )
    application.extensionArea.getExtensionPoint(DeepestSuperMethodsSearch.EP_NAME)
        .registerExtension(MethodDeepestSuperSearcher(), application)
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        MethodReferencesSearch.EP_NAME.name,
        QueryExecutor::class.java,
    )
    application.extensionArea.getExtensionPoint(MethodReferencesSearch.EP_NAME)
        .registerExtension(MethodUsagesSearcher(), application)
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        ReferencesSearch.EP_NAME.name,
        QueryExecutor::class.java,
    )
    application.extensionArea.getExtensionPoint(ReferencesSearch.EP_NAME).apply {
      registerExtension(SearcherQueryExecutor(), application)
      registerExtension(CachesBasedRefSearcher(), application)
      registerExtension(NonPhysicalReferenceSearcher(), application)
      registerExtension(VariableInIncompleteCodeSearcher(), application)
      registerExtension(SimpleAccessorReferenceSearcher(), application)
      registerExtension(SPIReferencesSearcher(), application)
      registerExtension(ConstructorReferencesSearcher(), application)
      registerExtension(PsiAnnotationMethodReferencesSearcher(), application)
      registerExtension(JavaRecordComponentSearcher(), application)
    }
    CoreApplicationEnvironment.registerExtensionPoint(
        application.extensionArea,
        CustomPropertyScopeProvider.EP_NAME.name,
        CustomPropertyScopeProvider::class.java,
    )
    KlsLogs.info(
        "Kotlin Analysis official Java reference search extensions registered: application={}, references={}, methods={}",
        System.identityHashCode(application),
        ReferencesSearch.EP_NAME.name,
        MethodReferencesSearch.EP_NAME.name,
    )
    // TODO(ACS-ANALYSIS-EXPERIMENT): Validate record synthetic fields, accessors, and canonical constructors on device.
    application.extensionArea.getExtensionPoint(PsiAugmentProvider.EP_NAME)
        .registerExtension(JShellPsiAugmentProvider(), application)
    application.extensionArea.getExtensionPoint(PsiAugmentProvider.EP_NAME)
        .registerExtension(RecordAugmentProvider(), application)
    KlsLogs.info(
        "Kotlin Analysis Java PSI augment extension registered: application={}, point={}, providers={}",
        System.identityHashCode(application),
        PsiAugmentProvider.EP_NAME.name,
        listOf(JShellPsiAugmentProvider::class.java.name, RecordAugmentProvider::class.java.name),
    )
  }

  @Suppress("UnstableApiUsage")
  private class AnalysisDocumentWriteAccessGuard : DocumentWriteAccessGuard() {
    override fun isWritable(document: Document): Result = success()
  }

  override fun registerProjectExtensionPoints(project: MockProject) {
    CoreApplicationEnvironment.registerExtensionPoint(
        project.extensionArea,
        PsiTreeChangeListener.EP.name,
        AnalysisPsiTreeChangeListener::class.java,
    )
  }

  private class AnalysisPsiTreeChangeListener : PsiTreeChangeListener {
    override fun beforeChildAddition(event: PsiTreeChangeEvent) {}
    override fun beforeChildRemoval(event: PsiTreeChangeEvent) {}
    override fun beforeChildReplacement(event: PsiTreeChangeEvent) {}
    override fun beforeChildMovement(event: PsiTreeChangeEvent) {}
    override fun beforeChildrenChange(event: PsiTreeChangeEvent) {}
    override fun beforePropertyChange(event: PsiTreeChangeEvent) {}
    override fun childAdded(event: PsiTreeChangeEvent) {}
    override fun childRemoved(event: PsiTreeChangeEvent) {}
    override fun childReplaced(event: PsiTreeChangeEvent) {}
    override fun childrenChanged(event: PsiTreeChangeEvent) {}
    override fun childMoved(event: PsiTreeChangeEvent) {}
    override fun propertyChanged(event: PsiTreeChangeEvent) {}
  }

  override fun registerProjectServices(project: MockProject) {
    PluginStructureProvider.registerProjectServices(project, PLUGIN_PATH)
    project.registerService(PsiNameHelper::class.java, PsiNameHelperImpl(project))
    project.picoContainer.unregisterComponent(InspectionManager::class.java.name)
    project.registerService(InspectionManager::class.java, LiveInspectionManager(project))
    project.registerService(InspectionProjectProfileManager::class.java, LiveInspectionProfileManager::class.java)
    project.picoContainer.unregisterComponent(CacheManager::class.java.name)
    project.registerService(CacheManager::class.java, IndexCacheManagerImpl::class.java)
    project.picoContainer.unregisterComponent(PsiSearchHelper::class.java.name)
    project.registerService(PsiSearchHelper::class.java, PsiSearchHelperImpl::class.java)
    project.picoContainer.unregisterComponent(FileEditorManager::class.java.name)
    project.registerService(FileEditorManager::class.java, LiveFileEditorManager(project, sourceRegistry))
    project.picoContainer.unregisterComponent(IncompleteDependenciesService::class.java.name)
    project.registerService(IncompleteDependenciesService::class.java, LiveIncompleteDependenciesService(project))
    val workspaceModel = LiveWorkspaceModel(projectContext)
    project.picoContainer.unregisterComponent(WorkspaceModel::class.java.name)
    project.registerService(WorkspaceModel::class.java, workspaceModel)
    val projectFileIndex = LiveProjectFileIndex(project, projectContext, sourceRegistry)
    project.picoContainer.unregisterComponent(ProjectFileIndex::class.java.name)
    project.registerService(ProjectFileIndex::class.java, projectFileIndex)
    project.picoContainer.unregisterComponent(FileIndexFacade::class.java.name)
    project.registerService(FileIndexFacade::class.java, LiveFileIndexFacade(project, projectFileIndex))
    project.picoContainer.unregisterComponent(ProjectRootManager::class.java.name)
    project.registerService(ProjectRootManager::class.java, projectFileIndex.projectRootManager)
    project.picoContainer.unregisterComponent(ModuleManager::class.java.name)
    project.registerService(ModuleManager::class.java, projectFileIndex.moduleManager)
    val projectFiles = LiveProjectFiles(projectContext, sourceRegistry)
    project.registerService(LiveProjectFiles::class.java, projectFiles)
    val directInheritorsIndex = LiveJavaDirectInheritorsIndex(project, projectFiles, sourceRegistry)
    project.registerService(LiveJavaDirectInheritorsIndex::class.java, directInheritorsIndex)
    val fileBasedIndex = FileBasedIndex.getInstance() as LiveFileBasedIndex
    fileBasedIndex.bind(project, projectFiles)
    val stubIndex = StubIndex.getInstance() as LiveStubIndex
    stubIndex.bind(project, projectFiles)
    Disposer.register(project, Disposable {
      fileBasedIndex.unbind(project)
      stubIndex.unbind(project)
      directInheritorsIndex.close()
      projectFiles.close()
    })
    project.picoContainer.unregisterComponent(ProjectRootModificationTracker::class.java.name)
    project.registerService(ProjectRootModificationTracker::class.java, object : ProjectRootModificationTracker() {
      override fun getModificationCount(): Long = projectFiles.modificationStamp
    })
    project.registerService(PsiJavaModuleModificationTracker::class.java, PsiJavaModuleModificationTracker::class.java)
    KlsLogs.info("Kotlin Analysis file index registered: project={}, modules={}", System.identityHashCode(project), projectContext.modules.size)
    project.picoContainer.unregisterComponent(org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider::class.java.name)
    project.picoContainer.unregisterComponent(JavaElementSourceFactory::class.java.name)
    project.registerService(KotlinLifetimeTokenFactory::class.java, KotlinReadActionConfinementLifetimeTokenFactory::class.java)
    project.registerService(KotlinPlatformSettings::class.java, AnalysisPlatformSettings::class.java)
    project.registerService(LLFirElementByPsiElementChooser::class.java, LLStandaloneFirElementByPsiElementChooser::class.java)
    project.registerService(SmartTypePointerManager::class.java, SmartTypePointerManagerImpl::class.java)
    project.registerService(KotlinProjectStructureProvider::class.java, projectStructureProvider)
    project.registerService(KotlinDirectInheritorsProvider::class.java, directInheritorsProvider)
    project.registerService(KaResolutionScopeProvider::class.java, com.tom.rv2ide.language.services.kotlin.backend.analysis.services.AnalysisResolutionScopeProvider::class.java)
    project.registerService(KotlinModuleDependentsProvider::class.java, moduleDependentsProvider)
    project.registerService(KotlinModificationTrackerFactory::class.java, AnalysisModificationTrackerFactory::class.java)
    project.registerService(KotlinAnnotationsResolverFactory::class.java, annotationsResolverFactory)
    project.registerService(KotlinDeclarationProviderFactory::class.java, declarationProviderFactory)
    project.registerService(KotlinDeclarationProviderMerger::class.java, declarationProviderMerger)
    project.registerService(KotlinPackageProviderFactory::class.java, packageProviderFactory)
    project.registerService(KotlinPackageProviderMerger::class.java, packageProviderMerger)
    project.registerService(KotlinPackagePartProviderFactory::class.java, packagePartProviderFactory)
    project.registerService(JavaElementSourceFactory::class.java, JavaFixedElementSourceFactory::class.java)
    // TODO(ACS-ANALYSIS-EXPERIMENT): Replace with the production standalone PSI service set after B-path validation.
    project.registerService(TreeAspect::class.java)
    project.registerService(PomModel::class.java, PomModelImpl::class.java)
    project.registerService(BlockSupport::class.java, BlockSupportImpl::class.java)
    project.registerService(CodeStyleManager::class.java, AnalysisPsiReparseCodeStyleManager(project))
    project.registerService(ScriptDefinitionProvider::class.java, scriptDefinitionProvider)
    project.registerService(ScriptConfigurationsProvider::class.java, scriptConfigurationsProvider)
    logPsiReparseServices(project)
  }

  private fun logPsiReparseServices(project: MockProject) {
    val blockSupport = runCatching {
      project.getService(org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport::class.java)
    }.getOrNull()
    val pomModel = runCatching {
      project.getService(org.jetbrains.kotlin.com.intellij.pom.PomModel::class.java)
    }.getOrNull()
    val treeAspect = runCatching {
      org.jetbrains.kotlin.com.intellij.pom.tree.TreeAspect.getInstance(project)
    }.getOrNull()
    val codeStyleManager = runCatching {
      org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project)
    }.getOrNull()
    KlsLogs.info(
        "Kotlin Analysis PSI reparse services: project={}, blockSupport={}, pomModel={}, treeAspect={}, codeStyleManager={}",
        System.identityHashCode(project),
        blockSupport?.javaClass?.name ?: "missing",
        pomModel?.javaClass?.name ?: "missing",
        treeAspect?.javaClass?.name ?: "missing",
        codeStyleManager?.javaClass?.name ?: "missing",
    )
  }

  override fun registerProjectModelServices(project: MockProject, disposable: Disposable) {
    with(PsiElementFinder.EP.getPoint(project)) {
      registerExtension(JavaElementFinder(project), disposable)
      registerExtension(
          LiveJavaElementFinder(
              project,
              project.getService(JavaFileManager::class.java) as LiveJavaFileManager,
          ),
          disposable,
      )
    }
  }
}
