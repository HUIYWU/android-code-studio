package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.impl.ResolveScopeManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Path

@OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
)
internal class LiveJavaResolveScopeManager(
    private val project: Project,
    private val registry: LiveSourceFileRegistry,
    private val delegate: ResolveScopeManager,
) : ResolveScopeManager() {
  override fun getResolveScope(element: PsiElement): GlobalSearchScope {
    val file = element.containingFile?.originalFile?.viewProvider?.virtualFile ?: return delegate.getResolveScope(element)
    return scopeFor(file) ?: delegate.getResolveScope(element)
  }

  override fun getDefaultResolveScope(file: VirtualFile): GlobalSearchScope =
      scopeFor(file) ?: delegate.getDefaultResolveScope(file)

  override fun getUseScope(element: PsiElement): GlobalSearchScope = delegate.getUseScope(element)

  private fun scopeFor(file: VirtualFile): GlobalSearchScope? {
    if (file.extension != "java") return null
    val module = registry.moduleForVirtualFile(file)
        ?: runCatching { registry.moduleForPath(Path.of(file.path)) }.getOrNull()
        ?: return null
    return KaResolutionScopeProvider.getInstance(project).getResolutionScope(module)
  }
}
