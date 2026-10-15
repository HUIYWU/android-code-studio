package com.tom.rv2ide.language.services.kotlin.backend.analysis.input

import com.tom.rv2ide.projects.models.ProjectInputSnapshot
import com.tom.rv2ide.projects.models.ProjectModuleInputSnapshot
import java.util.ArrayDeque

internal enum class KotlinProjectInputDimension {
  MODULE_IDENTITY,
  COMPILER_CONFIGURATION,
  SOURCE_STRUCTURE,
  BINARIES,
  BOOT,
  DEPENDENCY_GRAPH,
  VARIANT,
}

internal enum class KotlinProjectChangeKind {
  NONE,
  MODULE_SOURCE_STATE,
  DEPENDENCY_GRAPH,
  LIBRARY_CLASSPATH,
  BOOT_CLASSPATH,
  VARIANT,
  WORKSPACE_STRUCTURE,
}

internal data class KotlinProjectChange(
    val kind: KotlinProjectChangeKind,
    val dimensions: Set<KotlinProjectInputDimension>,
    val affectedModulesByDimension: Map<KotlinProjectInputDimension, Set<String>>,
) {
  val affectedModules: Set<String>
    get() = affectedModulesByDimension.values.flatten().toSortedSet()
}

internal object KotlinProjectInputDiff {
  fun compare(
      previous: KotlinProjectInputSnapshotState?,
      current: KotlinProjectInputSnapshotState,
  ): KotlinProjectChange {
    if (previous == null) {
      val modules = current.snapshot.modules.mapTo(linkedSetOf()) { it.identity.modulePath }
      return KotlinProjectChange(
          kind = KotlinProjectChangeKind.WORKSPACE_STRUCTURE,
          dimensions = setOf(
              KotlinProjectInputDimension.MODULE_IDENTITY,
              KotlinProjectInputDimension.COMPILER_CONFIGURATION,
              KotlinProjectInputDimension.SOURCE_STRUCTURE,
              KotlinProjectInputDimension.BINARIES,
              KotlinProjectInputDimension.BOOT,
              KotlinProjectInputDimension.DEPENDENCY_GRAPH,
              KotlinProjectInputDimension.VARIANT,
          ),
          affectedModulesByDimension = mapOf(
              KotlinProjectInputDimension.MODULE_IDENTITY to modules,
              KotlinProjectInputDimension.COMPILER_CONFIGURATION to modules,
              KotlinProjectInputDimension.SOURCE_STRUCTURE to modules,
              KotlinProjectInputDimension.BINARIES to modules,
              KotlinProjectInputDimension.BOOT to modules,
              KotlinProjectInputDimension.DEPENDENCY_GRAPH to modules,
              KotlinProjectInputDimension.VARIANT to modules,
          ),
      )
    }

    val oldFingerprints = previous.fingerprints
    val newFingerprints = current.fingerprints
    val dimensions = buildSet {
      if (oldFingerprints.moduleIdentity != newFingerprints.moduleIdentity) {
        add(KotlinProjectInputDimension.MODULE_IDENTITY)
      }
      if (oldFingerprints.compilerConfiguration != newFingerprints.compilerConfiguration) {
        add(KotlinProjectInputDimension.COMPILER_CONFIGURATION)
      }
      if (oldFingerprints.sourceStructure != newFingerprints.sourceStructure) {
        add(KotlinProjectInputDimension.SOURCE_STRUCTURE)
      }
      if (oldFingerprints.binaries != newFingerprints.binaries) {
        add(KotlinProjectInputDimension.BINARIES)
      }
      if (oldFingerprints.boot != newFingerprints.boot) {
        add(KotlinProjectInputDimension.BOOT)
      }
      if (oldFingerprints.dependencyGraph != newFingerprints.dependencyGraph) {
        add(KotlinProjectInputDimension.DEPENDENCY_GRAPH)
      }
      if (oldFingerprints.variant != newFingerprints.variant) {
        add(KotlinProjectInputDimension.VARIANT)
      }
    }
    if (dimensions.isEmpty()) {
      return KotlinProjectChange(
          kind = KotlinProjectChangeKind.NONE,
          dimensions = emptySet(),
          affectedModulesByDimension = emptyMap(),
      )
    }

    val kind = when {
      KotlinProjectInputDimension.MODULE_IDENTITY in dimensions ||
          KotlinProjectInputDimension.COMPILER_CONFIGURATION in dimensions ->
          KotlinProjectChangeKind.WORKSPACE_STRUCTURE
      KotlinProjectInputDimension.VARIANT in dimensions -> KotlinProjectChangeKind.VARIANT
      KotlinProjectInputDimension.DEPENDENCY_GRAPH in dimensions ->
          KotlinProjectChangeKind.DEPENDENCY_GRAPH
      KotlinProjectInputDimension.BOOT in dimensions -> KotlinProjectChangeKind.BOOT_CLASSPATH
      KotlinProjectInputDimension.BINARIES in dimensions ->
          KotlinProjectChangeKind.LIBRARY_CLASSPATH
      else -> KotlinProjectChangeKind.MODULE_SOURCE_STATE
    }
    return KotlinProjectChange(
        kind = kind,
        dimensions = dimensions,
        affectedModulesByDimension = changedModulesByDimension(previous, current, dimensions),
    )
  }

  private fun changedModulesByDimension(
      previous: KotlinProjectInputSnapshotState,
      current: KotlinProjectInputSnapshotState,
      dimensions: Set<KotlinProjectInputDimension>,
  ): Map<KotlinProjectInputDimension, Set<String>> {
    val oldModules = previous.snapshot.modules.associateBy { it.identity.modulePath }
    val newModules = current.snapshot.modules.associateBy { it.identity.modulePath }
    val previousDependents = dependentsByDependency(previous.snapshot.modules)
    val currentDependents = dependentsByDependency(current.snapshot.modules)

    return dimensions.associateWith { dimension ->
      val directlyChanged = linkedSetOf<String>()
      if ((dimension == KotlinProjectInputDimension.MODULE_IDENTITY ||
              dimension == KotlinProjectInputDimension.COMPILER_CONFIGURATION) &&
          previous.snapshot.workspaceRoot != current.snapshot.workspaceRoot) {
        directlyChanged += oldModules.keys
        directlyChanged += newModules.keys
      } else {
        (oldModules.keys + newModules.keys).forEach { modulePath ->
          val old = oldModules[modulePath]
          val new = newModules[modulePath]
          if (old == null || new == null) {
            directlyChanged += modulePath
          } else if (moduleChanged(old, new, dimension)) {
            directlyChanged += modulePath
          }
        }
      }
      if (dimension != KotlinProjectInputDimension.MODULE_IDENTITY &&
          dimension != KotlinProjectInputDimension.COMPILER_CONFIGURATION) {
        expandToDependents(directlyChanged, previousDependents, currentDependents)
      } else {
        directlyChanged
      }
    }
  }

  private fun dependentsByDependency(
      modules: List<ProjectModuleInputSnapshot>,
  ): Map<String, Set<String>> {
    val result = mutableMapOf<String, MutableSet<String>>()
    modules.forEach { module ->
      module.compileModulePaths.forEach { dependencyPath ->
        result.getOrPut(dependencyPath) { linkedSetOf() }.add(module.identity.modulePath)
      }
    }
    return result
  }

  private fun expandToDependents(
      roots: Set<String>,
      previousDependents: Map<String, Set<String>>,
      currentDependents: Map<String, Set<String>>,
  ): Set<String> {
    val affected = roots.toMutableSet()
    val queue = ArrayDeque<String>(roots)
    while (queue.isNotEmpty()) {
      val dependency = queue.removeFirst()
      (previousDependents[dependency].orEmpty() + currentDependents[dependency].orEmpty())
          .forEach { dependent ->
            if (affected.add(dependent)) queue.addLast(dependent)
          }
    }
    return affected
  }

  private fun moduleChanged(
      old: ProjectModuleInputSnapshot,
      new: ProjectModuleInputSnapshot,
      dimension: KotlinProjectInputDimension,
  ): Boolean = when (dimension) {
    KotlinProjectInputDimension.MODULE_IDENTITY -> old.identity != new.identity
    KotlinProjectInputDimension.COMPILER_CONFIGURATION -> old.compilerSettings != new.compilerSettings
    KotlinProjectInputDimension.SOURCE_STRUCTURE ->
        old.ordinarySourceRoots != new.ordinarySourceRoots ||
            old.generatedSourceRoots != new.generatedSourceRoots ||
            old.javaSourceRoots != new.javaSourceRoots
    KotlinProjectInputDimension.BINARIES ->
        old.moduleOutputs != new.moduleOutputs || old.externalBinaries != new.externalBinaries
    KotlinProjectInputDimension.BOOT -> old.bootBinaries != new.bootBinaries
    KotlinProjectInputDimension.DEPENDENCY_GRAPH ->
        old.compileModulePaths != new.compileModulePaths ||
            old.externalDependencyIdentities != new.externalDependencyIdentities
    KotlinProjectInputDimension.VARIANT ->
        old.variantSelection != new.variantSelection || old.variantArtifact != new.variantArtifact
  }
}
