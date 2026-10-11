package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface

import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.KotlinStaticProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiFileSystemItem

@OptIn(KaExperimentalApi::class, KaImplementationDetail::class, KaPlatformInterface::class)
internal class LiveStaticProjectStructureProvider(
    private val project: Project,
    override val allModules: List<KaModule>,
    private val registry: com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry,
) : KotlinStaticProjectStructureProvider() {
  override val allSourceFiles: List<PsiFileSystemItem> =
      allModules.filterIsInstance<LiveSourceModule>().flatMap { it.psiRoots }

  private val outside = LiveOutsideContentRootModule(project, null)

  override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule = outside

  override fun getModule(
      element: org.jetbrains.kotlin.com.intellij.psi.PsiElement,
      useSiteModule: KaModule?,
  ): KaModule =
      element.containingFile?.virtualFile?.let { virtualFile ->
        registry.moduleForVirtualFile(virtualFile)
            ?: allModules.firstOrNull { it.contentScope.contains(virtualFile) }
      } ?: LiveOutsideContentRootModule(project, element.containingFile)

  override fun getImplementingModules(module: KaModule): List<KaModule> =
      allModules.filter { module in it.directDependsOnDependencies }
}