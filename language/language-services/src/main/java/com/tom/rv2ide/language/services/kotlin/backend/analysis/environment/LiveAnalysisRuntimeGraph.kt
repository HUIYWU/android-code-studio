package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisBinaryRootContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisModuleContext
import com.tom.rv2ide.projects.models.ProjectBinaryOrigin
import com.tom.rv2ide.projects.models.ProjectBinaryRole
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayDeque

internal enum class LiveAnalysisBinaryRootRole {
  BINARY,
  BOOT,
}

internal data class LiveAnalysisBinaryRootProvenance(
    val roles: Set<ProjectBinaryRole>,
    val origins: Set<ProjectBinaryOrigin>,
    val ownerModulePaths: Set<String>,
    val variantNames: Set<String>,
    val missing: Boolean,
)

internal data class LiveAnalysisBinaryRootKey(
    val path: Path,
) {
  val stableName: String
    get() {
      val digest = MessageDigest.getInstance("SHA-256")
          .digest(path.toString().toByteArray(Charsets.UTF_8))
          .joinToString("") { byte -> "%02x".format(byte) }
      return digest.take(24)
    }

  companion object {
    fun of(path: Path): LiveAnalysisBinaryRootKey =
        LiveAnalysisBinaryRootKey(path.toAbsolutePath().normalize())
  }
}
private fun libraryGroupIdentity(consumers: Set<String>, libraryIdentities: Set<String>): String {
  val digest = MessageDigest.getInstance("SHA-256")
      .digest(
          (consumers.sorted().joinToString("\n") + "\n" + libraryIdentities.sorted().joinToString("\n"))
              .toByteArray(Charsets.UTF_8),
      )
      .joinToString("") { byte -> "%02x".format(byte) }
  return "acs-library-group-${digest.take(24)}"
}

internal data class LiveAnalysisLibraryGroup(
    val identity: String,
    val rootKeys: List<LiveAnalysisBinaryRootKey>,
    val sourcePaths: List<Path>,
    val consumers: List<String>,
)

internal data class LiveAnalysisModuleGraph(
    val modulePaths: List<String>,
    val dependenciesByModule: Map<String, List<String>>,
    val allDependenciesByModule: Map<String, List<String>>,
    val dependentsByIdentity: Map<String, List<String>>,
    val libraryRootsByModule: Map<String, List<LiveAnalysisBinaryRootKey>>,
    val libraryGroupsByModule: Map<String, List<LiveAnalysisLibraryGroup>>,
    val libraryRoles: Map<LiveAnalysisBinaryRootKey, Set<LiveAnalysisBinaryRootRole>>,
    val libraryProvenance: Map<LiveAnalysisBinaryRootKey, LiveAnalysisBinaryRootProvenance>,
    val missingRootsByModule: Map<String, List<LiveAnalysisBinaryRootKey>>,
    val libraryConsumers: Map<LiveAnalysisBinaryRootKey, List<String>>,
) {
  fun directDependents(identity: String): List<String> = dependentsByIdentity[identity].orEmpty()

  fun dependentClosure(identity: String): Set<String> {
    val result = linkedSetOf<String>()
    val pending = ArrayDeque<String>()
    pending.add(identity)
    while (pending.isNotEmpty()) {
      val current = pending.removeFirst()
      directDependents(current).forEach { dependent ->
        if (result.add(dependent)) pending.addLast(dependent)
      }
    }
    return result
  }

  companion object {
    fun build(contexts: List<KotlinAnalysisModuleContext>): LiveAnalysisModuleGraph {
      val sortedContexts = contexts.sortedBy { it.modulePath }
      val modulePaths = sortedContexts.map { it.modulePath }
      val knownModules = modulePaths.toSet()
      require(modulePaths.size == knownModules.size) {
        "Duplicate Kotlin Analysis module paths: ${modulePaths.joinToString(",")}" 
      }

      val dependencies = sortedContexts.associate { context ->
        val paths = context.compileModulePaths.distinct().sorted()
        val selfDependency = paths.firstOrNull { it == context.modulePath }
        require(selfDependency == null) {
          "Kotlin Analysis module depends on itself: ${context.modulePath}"
        }
        val missing = paths.filter { it !in knownModules }
        require(missing.isEmpty()) {
          "Kotlin Analysis module has missing dependencies: module=${context.modulePath}, missing=${missing.joinToString(",")}"
        }
        context.modulePath to paths
      }

      validateCycles(dependencies)

      val missingRootsByModule = linkedMapOf<String, MutableList<LiveAnalysisBinaryRootKey>>()
      val roles = linkedMapOf<LiveAnalysisBinaryRootKey, MutableSet<LiveAnalysisBinaryRootRole>>()
      val provenanceRoles = linkedMapOf<LiveAnalysisBinaryRootKey, MutableSet<ProjectBinaryRole>>()
      val provenanceOrigins = linkedMapOf<LiveAnalysisBinaryRootKey, MutableSet<ProjectBinaryOrigin>>()
      val provenanceOwners = linkedMapOf<LiveAnalysisBinaryRootKey, MutableSet<String>>()
      val provenanceVariants = linkedMapOf<LiveAnalysisBinaryRootKey, MutableSet<String>>()
      val missingRoots = linkedSetOf<LiveAnalysisBinaryRootKey>()
      val rootsByModule = linkedMapOf<String, List<LiveAnalysisBinaryRootKey>>()
      val sourcePathsByRoot = linkedMapOf<LiveAnalysisBinaryRootKey, MutableSet<Path>>()
      val libraryIdentitiesByRoot = linkedMapOf<LiveAnalysisBinaryRootKey, MutableSet<String>>()
      val consumers = linkedMapOf<LiveAnalysisBinaryRootKey, MutableSet<String>>()
      sortedContexts.forEach { context ->
        context.libraryAttachments.forEach { attachment ->
          attachment.binaryPaths.forEach { binaryPath ->
            val root = LiveAnalysisBinaryRootKey.of(binaryPath)
            libraryIdentitiesByRoot.getOrPut(root) { linkedSetOf() }.add(attachment.libraryIdentity)
            sourcePathsByRoot.getOrPut(root) { linkedSetOf() }.addAll(
                attachment.sourcePaths.map { it.toAbsolutePath().normalize() },
            )
          }
        }
        val moduleRoots = buildList {
          context.binaryRootContexts.forEach { binary ->
            val key = LiveAnalysisBinaryRootKey.of(binary.path)
            provenanceRoles.getOrPut(key) { linkedSetOf() }.add(binary.role)
            provenanceOrigins.getOrPut(key) { linkedSetOf() }.add(binary.origin)
            binary.ownerModulePath?.let { provenanceOwners.getOrPut(key) { linkedSetOf() }.add(it) }
            binary.variantName?.let { provenanceVariants.getOrPut(key) { linkedSetOf() }.add(it) }
            if (!binary.exists) {
              missingRoots += key
              missingRootsByModule.getOrPut(context.modulePath) { mutableListOf() }.add(key)
              return@forEach
            }
            roles.getOrPut(key) { linkedSetOf() }.add(
                if (binary.role == ProjectBinaryRole.BOOT_BINARY) {
                  LiveAnalysisBinaryRootRole.BOOT
                } else {
                  LiveAnalysisBinaryRootRole.BINARY
                },
            )
            add(key)
          }
        }.distinct().sortedBy { it.path.toString() }
        rootsByModule[context.modulePath] = moduleRoots
        moduleRoots.forEach { key -> consumers.getOrPut(key) { linkedSetOf() }.add(context.modulePath) }
      }
      val libraryProvenance = (provenanceRoles.keys + provenanceOrigins.keys + provenanceOwners.keys + provenanceVariants.keys + missingRoots)
          .distinct()
          .associateWith { key ->
            LiveAnalysisBinaryRootProvenance(
                roles = provenanceRoles[key].orEmpty().toSet(),
                origins = provenanceOrigins[key].orEmpty().toSet(),
                ownerModulePaths = provenanceOwners[key].orEmpty().toSet(),
                variantNames = provenanceVariants[key].orEmpty().toSet(),
                missing = key in missingRoots,
            )
          }

      val rootConsumers = consumers.mapValues { (_, modules) -> modules.toSet() }
      val groupsByConsumerAndLibrarySet = rootConsumers.entries
          .groupBy { (root, consumerSet) -> consumerSet to libraryIdentitiesByRoot[root].orEmpty().toSet() }
          .mapValues { (groupKey, entries) ->
            val (consumerSet, libraryIdentities) = groupKey
            val roots = entries.map { it.key }
            LiveAnalysisLibraryGroup(
                identity = libraryGroupIdentity(consumerSet, libraryIdentities),
                rootKeys = roots.sortedBy { it.path.toString() },
                sourcePaths = roots.flatMap { sourcePathsByRoot[it].orEmpty() }
                    .distinct()
                    .sortedBy { it.toString() },
                consumers = consumerSet.sorted(),
            )
          }
      val groupsByRoot = groupsByConsumerAndLibrarySet.values.flatMap { group ->
        group.rootKeys.map { root -> root to group }
      }.toMap()
      val libraryGroupsByModule = modulePaths.associateWith { modulePath ->
        rootsByModule[modulePath].orEmpty()
            .mapNotNull(groupsByRoot::get)
            .distinctBy { it.identity }
            .sortedBy { it.identity }
      }
      val allDependencies = modulePaths.associateWith { modulePath ->
        (dependencies[modulePath].orEmpty() + libraryGroupsByModule[modulePath].orEmpty().map { it.identity })
            .distinct()
            .sorted()
      }
      val dependents = linkedMapOf<String, MutableSet<String>>()
      allDependencies.forEach { (module, moduleDependencies) ->
        moduleDependencies.forEach { dependency ->
          dependents.getOrPut(dependency) { linkedSetOf() }.add(module)
        }
      }

      return LiveAnalysisModuleGraph(
          modulePaths = modulePaths,
          dependenciesByModule = dependencies.mapValues { (_, paths) -> paths.toList() },
          allDependenciesByModule = allDependencies,
          dependentsByIdentity = dependents.mapValues { (_, modules) -> modules.sorted() },
          libraryRootsByModule = rootsByModule,
          libraryGroupsByModule = libraryGroupsByModule,
          libraryRoles = roles.mapValues { (_, value) -> value.toSet() },
          libraryProvenance = libraryProvenance,
          missingRootsByModule = missingRootsByModule.mapValues { (_, roots) -> roots.distinct().sortedBy { it.path.toString() } },
          libraryConsumers = consumers.mapValues { (_, value) -> value.sorted() },
      )
    }

    private fun validateCycles(dependencies: Map<String, List<String>>) {
      val state = mutableMapOf<String, Int>()
      val stack = mutableListOf<String>()

      fun visit(modulePath: String) {
        when (state[modulePath]) {
          1 -> {
            val cycleStart = stack.indexOf(modulePath).coerceAtLeast(0)
            val cycle = (stack.subList(cycleStart, stack.size) + modulePath).joinToString(" -> ")
            error("Cyclic Kotlin Analysis module dependency: $cycle")
          }
          2 -> return
        }
        state[modulePath] = 1
        stack += modulePath
        dependencies[modulePath].orEmpty().forEach(::visit)
        stack.removeAt(stack.lastIndex)
        state[modulePath] = 2
      }

      dependencies.keys.sorted().forEach(::visit)
    }
  }
}
