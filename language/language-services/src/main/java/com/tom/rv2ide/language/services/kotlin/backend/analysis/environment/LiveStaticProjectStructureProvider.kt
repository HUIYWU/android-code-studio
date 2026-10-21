package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface

import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.KotlinStaticProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiFileSystemItem
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.liveBackingPath

@OptIn(KaExperimentalApi::class, KaImplementationDetail::class, KaPlatformInterface::class)
internal class LiveStaticProjectStructureProvider(
    private val project: Project,
    private val baseModules: List<KaModule>,
    private val registry: com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry,
) : KotlinStaticProjectStructureProvider() {
  override val allModules: List<KaModule>
    get() = baseModules + registry.scriptModules()

  override val allSourceFiles: List<PsiFileSystemItem>
    get() = baseModules.filterIsInstance<LiveSourceModule>().flatMap { it.psiRoots } +
        registry.scriptModules().map { it.file }

  private val outside = LiveOutsideContentRootModule(project, null)

  override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule = outside

override fun getModule(
       element: org.jetbrains.kotlin.com.intellij.psi.PsiElement,
       useSiteModule: KaModule?,
   ): KaModule {
     val file = element.containingFile
     val ktFile = file as? org.jetbrains.kotlin.psi.KtFile
     val backingPath = ktFile?.liveBackingPath ?: ktFile?.originalKtFile?.liveBackingPath
     if (backingPath != null) registry.moduleForPath(backingPath)?.let { return it }
     return file?.virtualFile?.let { virtualFile ->
       registry.moduleForVirtualFile(virtualFile)
           ?: allModules.firstOrNull { it.contentScope.contains(virtualFile) }
     } ?: LiveOutsideContentRootModule(project, file)
   }

  override fun getImplementingModules(module: KaModule): List<KaModule> =
      allModules.filter { module in it.directDependsOnDependencies }
}