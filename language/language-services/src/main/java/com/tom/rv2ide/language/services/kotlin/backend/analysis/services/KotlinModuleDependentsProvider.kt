package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.allDirectDependencies
import java.util.concurrent.ConcurrentHashMap

internal class LiveModuleDependentsProvider : KotlinModuleDependentsProviderBase() {
  private lateinit var modules: List<KaModule>
  private val direct = ConcurrentHashMap<KaModule, Set<KaModule>>()

  fun setup(modules: List<KaModule>) {
    this.modules = modules
    direct.clear()
    val map = linkedMapOf<KaModule, MutableSet<KaModule>>()
    modules.forEach { module ->
      module.allDirectDependencies().forEach { dependency ->
        if (dependency != module) map.getOrPut(dependency) { linkedSetOf() }.add(module)
      }
    }
    map.forEach { (module, dependents) -> direct[module] = dependents.toSet() }
  }

  override fun getDirectDependents(module: KaModule): Set<KaModule> = direct[module].orEmpty()

  override fun getRefinementDependents(module: KaModule): Set<KaModule> = emptySet()
}