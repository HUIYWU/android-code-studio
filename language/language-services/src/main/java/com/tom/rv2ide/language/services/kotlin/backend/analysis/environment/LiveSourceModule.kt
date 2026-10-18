package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceRootDescriptor
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiFileSystemItem
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.platform.TargetPlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.asSequence

@OptIn(KaPlatformInterface::class, KaExperimentalApi::class)
internal class LiveSourceModule(
    override val project: Project,
    override val name: String,
    val sourceRootDescriptors: List<KotlinSourceRootDescriptor>,
    val sourceRoots: Set<Path>,
    private val registry: LiveSourceFileRegistry,
    override val directRegularDependencies: List<KaModule>,
    override val targetPlatform: TargetPlatform,
    override val languageVersionSettings: LanguageVersionSettings,
) : KaModuleBase(), KaSourceModule {
  override val directDependsOnDependencies: List<KaModule> = emptyList()
  override val directFriendDependencies: List<KaModule> = emptyList()

  override val baseContentScope: GlobalSearchScope = object : GlobalSearchScope(project) {
    override fun contains(file: VirtualFile): Boolean {
      if (registry.moduleForVirtualFile(file) === this@LiveSourceModule) return true
      val path = runCatching { Path.of(file.path).normalize() }.getOrNull() ?: return false
      val extension = path.extension
      return (extension == "kt" || extension == "kts" || extension == "java") &&
          sourceRoots.any { path == it || path.startsWith(it) }
    }

    override fun isSearchInModuleContent(aModule: Module): Boolean = true
    override fun isSearchInLibraries(): Boolean = false
  }

  override val psiRoots: List<PsiFileSystemItem>
    get() = sourceRoots.flatMap { root ->
      if (!Files.exists(root)) return@flatMap emptyList()
      Files.walk(root).use { stream ->
        stream.iterator().asSequence()
            .filter { Files.isRegularFile(it) }
            .filter { it.extension == "kt" || it.extension == "kts" || it.extension == "java" }
            .mapNotNull { path ->
              VirtualFileManager.getInstance().findFileByNioPath(path)?.let {
                PsiManager.getInstance(project).findFile(it)
              }
            }
            .toList()
      }
    }

  @OptIn(KaExperimentalApi::class)
  override val stableModuleName: String
    get() = name
}

@OptIn(KaPlatformInterface::class, KaExperimentalApi::class)
internal class LiveOutsideContentRootModule(
    override val project: Project,
    override val file: org.jetbrains.kotlin.com.intellij.psi.PsiFile?,
) : KaModuleBase(), org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule {
  override val name: String = "acs-outside-content-root"
  override val directRegularDependencies: List<KaModule> = emptyList()
  override val directDependsOnDependencies: List<KaModule> = emptyList()
  override val directFriendDependencies: List<KaModule> = emptyList()
  override val targetPlatform: TargetPlatform = org.jetbrains.kotlin.platform.jvm.JvmPlatforms.defaultJvmPlatform
  override val baseContentScope: GlobalSearchScope =
      file?.let(GlobalSearchScope::fileScope) ?: GlobalSearchScope.EMPTY_SCOPE
  override val moduleDescription: String = "ACS source outside content root"
}