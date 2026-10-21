package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.liveBackingPath
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaScriptModule
import org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtScriptModule
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

@OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
internal class LiveScriptModuleRegistry(
    coreApplicationEnvironment: CoreApplicationEnvironment,
    project: Project,
    private val sourceRegistry: LiveSourceFileRegistry,
    private val platform: TargetPlatform,
    private val languageVersionSettings: LanguageVersionSettings,
    private val dependenciesByPath: Map<Path, List<KaModule>>,
) {
  private val moduleBuilder = KtModuleProviderBuilder(coreApplicationEnvironment, project).apply {
    this.platform = this@LiveScriptModuleRegistry.platform
  }

  fun refresh(file: KtFile): KaScriptModule? {
    val path = file.liveBackingPath
        ?: file.virtualFile?.let { runCatching { Path.of(it.path).normalize() }.getOrNull() }
        ?: return null
    val normalized = path.normalize()
    val module = moduleBuilder.buildKtScriptModule {
      this.file = file
      this.platform = this@LiveScriptModuleRegistry.platform
      this.languageVersionSettings = this@LiveScriptModuleRegistry.languageVersionSettings
      dependenciesByPath[normalized].orEmpty().forEach(::addRegularDependency)
    }
    sourceRegistry.bindScriptModule(normalized, module)
    return module
  }

}
