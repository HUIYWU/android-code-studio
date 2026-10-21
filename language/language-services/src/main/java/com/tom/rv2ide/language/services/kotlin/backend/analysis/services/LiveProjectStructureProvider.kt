package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveOutsideContentRootModule
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveSourceModule
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.liveBackingPath
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@OptIn(KaPlatformInterface::class, KaExperimentalApi::class)
internal class LiveProjectStructureProvider : KotlinProjectStructureProviderBase() {
  companion object {
    fun getInstance(project: Project): LiveProjectStructureProvider =
        KotlinProjectStructureProvider.getInstance(project) as LiveProjectStructureProvider
  }

  private lateinit var project: Project
  private lateinit var modules: List<KaModule>
  private lateinit var registry: LiveSourceFileRegistry
  private val notUnderContentRoot = ConcurrentHashMap<PsiFile, KaNotUnderContentRootModule>()
  private var notUnderContentRootWithoutFile: KaNotUnderContentRootModule? = null

  fun setup(project: MockProject, modules: List<KaModule>, registry: LiveSourceFileRegistry) {
    this.project = project
    this.modules = modules
    this.registry = registry
    modules.filterIsInstance<KaSourceModule>().forEach { sourceModule ->
      val liveSourceModule = sourceModule as? LiveSourceModule
      if (liveSourceModule != null) {
        registry.bindModule(sourceModule, liveSourceModule.sourceRootDescriptors)
      } else {
        registry.bindModule(sourceModule, sourceModule.contentRoots())
      }
    }
  }

  fun registerInMemoryFile(path: String, virtualFile: VirtualFile) = registry.register(Path.of(path), virtualFile)
  fun unregisterInMemoryFile(path: String) = registry.unregister(Path.of(path))

  override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule {
    val containingFile = element.containingFile ?: return fallback(null)
    val ktFile = containingFile as? KtFile
    val backingPath = ktFile?.liveBackingPath ?: ktFile?.originalKtFile?.liveBackingPath
    if (backingPath != null) registry.moduleForPath(backingPath)?.let { return it }
    val virtualFile = containingFile.virtualFile
    registry.moduleForVirtualFile(virtualFile ?: return fallback(containingFile))?.let { return it }
    if (virtualFile != null) {
      runCatching { Path.of(virtualFile.path).normalize() }.getOrNull()?.let { registry.moduleForPath(it) }?.let { return it }
      modules.firstOrNull { it.contentScope.contains(virtualFile) }?.let { return it }
    }
    return fallback(containingFile)
  }

  override fun getImplementingModules(module: KaModule): List<KaModule> =
      (modules + registry.scriptModules()).filter { module in it.directDependsOnDependencies }

  override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule = fallback(null)

  private fun fallback(file: PsiFile?): KaNotUnderContentRootModule {
    if (file == null) return notUnderContentRootWithoutFile ?: LiveOutsideContentRootModule(project, null).also { notUnderContentRootWithoutFile = it }
    return notUnderContentRoot.computeIfAbsent(file) { LiveOutsideContentRootModule(project, it) }
  }

  private fun KaSourceModule.contentRoots(): Set<Path> =
      psiRoots.mapNotNull { root -> runCatching { Path.of(root.virtualFile.path).normalize() }.getOrNull() }.toSet()
}