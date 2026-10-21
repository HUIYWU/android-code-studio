package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProviderBase
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.liveBackingPath
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaScriptModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

@OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
internal class LiveModuleDependentsProvider : KotlinModuleDependentsProviderBase() {
  private fun moduleIdentity(module: KaModule): String = when (module) {
    is KaSourceModule -> module.name
    is KaLibraryModule -> module.libraryName
    is KaLibrarySourceModule -> module.libraryName
    is KaScriptModule -> scriptIdentity(module)
    else -> module.stableModuleName ?: module.javaClass.name
  }

  private fun scriptIdentity(module: KaScriptModule): String =
      module.file.liveBackingPath?.normalize()?.let { "acs-script:$it" }
          ?: "acs-script:psi:${System.identityHashCode(module.file)}"

  private var baseModules: List<KaModule> = emptyList()
  private var baseDependentsByIdentity: Map<String, List<String>> = emptyMap()
  private lateinit var modules: List<KaModule>
  private val modulesByIdentity = ConcurrentHashMap<String, KaModule>()
  private val direct = ConcurrentHashMap<KaModule, Set<KaModule>>()
  private val transitive = ConcurrentHashMap<KaModule, Set<KaModule>>()

  fun setup(
      modules: List<KaModule>,
      dependentsByIdentity: Map<String, List<String>>,
  ) {
    baseModules = modules.filterNot { it is KaScriptModule }
    baseDependentsByIdentity = dependentsByIdentity
    rebuild(emptyList())
  }

  fun refreshScriptModules(scriptModules: Collection<KaScriptModule>) {
    rebuild(scriptModules)
  }

  private fun rebuild(scriptModules: Collection<KaScriptModule>) {
    modules = (baseModules + scriptModules).distinct().sortedBy(::moduleIdentity)
    modulesByIdentity.clear()
    modulesByIdentity.putAll(modules.associateBy(::moduleIdentity))
    direct.clear()
    transitive.clear()

    val dependents = baseDependentsByIdentity
        .mapValues { (_, identities) -> identities.toMutableList() }
        .toMutableMap()
    modules.filterIsInstance<KaScriptModule>().forEach { script ->
      val scriptIdentity = moduleIdentity(script)
      script.directRegularDependencies.forEach { dependency ->
        dependents.getOrPut(moduleIdentity(dependency)) { mutableListOf() }.add(scriptIdentity)
      }
    }
    dependents.toSortedMap().forEach { (dependencyIdentity, dependentIdentities) ->
      val dependency = modulesByIdentity[dependencyIdentity] ?: return@forEach
      direct[dependency] = dependentIdentities
          .mapNotNull(modulesByIdentity::get)
          .distinct()
          .sortedBy(::moduleIdentity)
          .toSet()
    }
  }

  override fun getDirectDependents(module: KaModule): Set<KaModule> = direct[module].orEmpty()

  override fun getTransitiveDependents(module: KaModule): Set<KaModule> =
      transitive.computeIfAbsent(module) {
        val result = linkedSetOf<KaModule>()
        val pending = ArrayDeque<KaModule>()
        pending.addAll(getDirectDependents(module))
        while (pending.isNotEmpty()) {
          val current = pending.removeFirst()
          if (!result.add(current)) continue
          pending.addAll(getDirectDependents(current))
        }
        result
      }

  override fun getRefinementDependents(module: KaModule): Set<KaModule> = emptySet()
}