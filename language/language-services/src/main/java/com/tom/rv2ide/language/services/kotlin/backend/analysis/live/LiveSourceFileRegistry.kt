package com.tom.rv2ide.language.services.kotlin.backend.analysis.live
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaScriptModule
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension


internal enum class KotlinSourceKind {
  ORDINARY,
  GENERATED,
  UNKNOWN,
}

internal data class KotlinSourceRootDescriptor(
    val path: Path,
    val ownerModulePath: String,
    val sourceKind: KotlinSourceKind,
    val origin: String,
    val variantName: String?,
)

@OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
internal class LiveSourceFileRegistry {
  private val pathToVirtualFile = ConcurrentHashMap<Path, VirtualFile>()
  private val virtualFileToPath = ConcurrentHashMap<VirtualFile, Path>()
  private val virtualFileToModule = ConcurrentHashMap<VirtualFile, KaModule>()
  private val pathToScriptModule = ConcurrentHashMap<Path, KaScriptModule>()
  private val moduleRoots = ConcurrentHashMap<KaModule, List<KotlinSourceRootDescriptor>>()

  fun bindModule(module: KaModule, roots: Collection<KotlinSourceRootDescriptor>) {
    moduleRoots[module] = roots.map { it.copy(path = it.path.normalize()) }
        .sortedByDescending { it.path.toString().length }
  }

  fun bindModule(module: KaModule, roots: Set<Path>) {
    moduleRoots[module] = roots.map { path ->
      KotlinSourceRootDescriptor(
          path = path.normalize(),
          ownerModulePath = "UNKNOWN",
          sourceKind = KotlinSourceKind.UNKNOWN,
          origin = "UNKNOWN",
          variantName = null,
      )
    }.sortedByDescending { it.path.toString().length }
  }

  fun register(path: Path, virtualFile: VirtualFile): KaModule? {
    val normalized = path.normalize()
    pathToVirtualFile.remove(normalized)?.let {
      virtualFileToModule.remove(it)
      virtualFileToPath.remove(it)
    }
    val module = moduleForPath(normalized)
    if (module != null) {
      virtualFileToModule[virtualFile] = module
    }
    virtualFileToPath[virtualFile] = normalized
    pathToVirtualFile[normalized] = virtualFile
    return module
  }

  fun bindScriptModule(path: Path, module: KaScriptModule) {
    val normalized = path.normalize()
    pathToScriptModule[normalized] = module
    pathToVirtualFile[normalized]?.let { virtualFileToModule[it] = module }
  }

  fun scriptModules(): List<KaScriptModule> = pathToScriptModule.entries
      .sortedBy { it.key.toString() }
      .map { it.value }

  fun unregister(path: Path) {
    val normalized = path.normalize()
    pathToScriptModule.remove(normalized)
    pathToVirtualFile.remove(normalized)?.let {
      virtualFileToModule.remove(it)
      virtualFileToPath.remove(it)
    }
  }

  fun virtualFile(path: Path): VirtualFile? = pathToVirtualFile[path.normalize()]

  fun pathForVirtualFile(virtualFile: VirtualFile): Path? = virtualFileToPath[virtualFile]

  fun moduleForVirtualFile(virtualFile: VirtualFile): KaModule? = virtualFileToModule[virtualFile]

  fun moduleForPath(path: Path): KaModule? {
    val normalized = path.normalize()
    pathToScriptModule[normalized]?.let { return it }
    if (normalized.extension == "kts") return null
    return bestRootCandidates(normalized)
        .map { it.first }
        .distinct()
        .singleOrNull()
  }

  fun sourceRootForPath(path: Path): KotlinSourceRootDescriptor? {
    val candidates = bestRootCandidates(path)
    if (candidates.isEmpty()) return null
    if (candidates.map { it.first }.distinct().size != 1) return null
    val roots = candidates.map { it.second }.distinct()
    if (roots.size == 1) return roots.single()
    return roots.first().copy(sourceKind = KotlinSourceKind.UNKNOWN, origin = "AMBIGUOUS", variantName = null)
  }

  private fun bestRootCandidates(path: Path): List<Pair<KaModule, KotlinSourceRootDescriptor>> {
    val normalized = path.normalize()
    val matches = moduleRoots.entries
        .asSequence()
        .flatMap { (module, roots) ->
          roots.asSequence()
              .filter { root -> normalized == root.path || normalized.startsWith(root.path) }
              .map { root -> module to root }
        }
        .toList()
    val longestLength = matches.maxOfOrNull { (_, root) -> root.path.toString().length } ?: return emptyList()
    return matches.filter { (_, root) -> root.path.toString().length == longestLength }
  }

  fun contains(virtualFile: VirtualFile): Boolean = virtualFileToModule.containsKey(virtualFile)

  fun isKotlinSource(path: Path): Boolean {
    val extension = path.extension
    return extension == "kt" || extension == "kts"
  }
}
