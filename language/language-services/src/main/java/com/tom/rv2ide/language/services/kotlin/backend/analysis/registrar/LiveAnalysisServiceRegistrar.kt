package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.language.services.kotlin.backend.analysis.services.AnalysisModificationTrackerFactory
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
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.mock.MockApplication
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeEvent
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeListener
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFinderImpl
import org.jetbrains.kotlin.com.intellij.psi.SmartTypePointerManager
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

@OptIn(KaImplementationDetail::class)
internal class LiveAnalysisServiceRegistrar(
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
        DocumentWriteAccessGuard.EP_NAME,
        AnalysisDocumentWriteAccessGuard::class.java,
    )
    application.registerService(KotlinAnalysisPermissionOptions::class.java, AnalysisPermissionOptions::class.java)
    application.registerService(AsyncExecutionService::class.java, NoOpAsyncExecutionService::class.java)
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
      registerExtension(PsiElementFinderImpl(project), disposable)
    }
  }
}