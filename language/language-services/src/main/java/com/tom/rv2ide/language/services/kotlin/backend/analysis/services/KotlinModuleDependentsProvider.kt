package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

@OptIn(KaExperimentalApi::class)
internal class LiveModuleDependentsProvider : KotlinModuleDependentsProviderBase() {
  private fun moduleIdentity(module: KaModule): String = when (module) {
    is KaSourceModule -> module.name
    is KaLibraryModule -> module.libraryName
    else -> module.stableModuleName ?: module.javaClass.name
  }

  private lateinit var modules: List<KaModule>
  private val modulesByIdentity = ConcurrentHashMap<String, KaModule>()
  private val direct = ConcurrentHashMap<KaModule, Set<KaModule>>()
  private val transitive = ConcurrentHashMap<KaModule, Set<KaModule>>()

  fun setup(
      modules: List<KaModule>,
      dependentsByIdentity: Map<String, List<String>>,
  ) {
    this.modules = modules.sortedBy { moduleIdentity(it) }
    modulesByIdentity.clear()
    modulesByIdentity.putAll(this.modules.associateBy(::moduleIdentity))
    direct.clear()
    transitive.clear()

    dependentsByIdentity.toSortedMap().forEach { (dependencyIdentity, dependentIdentities) ->
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