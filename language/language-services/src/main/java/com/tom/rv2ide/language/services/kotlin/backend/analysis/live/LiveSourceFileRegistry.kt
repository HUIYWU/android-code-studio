package com.tom.rv2ide.language.services.kotlin.backend.analysis.live

import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension

internal class LiveSourceFileRegistry {
  private val pathToVirtualFile = ConcurrentHashMap<Path, VirtualFile>()
  private val virtualFileToPath = ConcurrentHashMap<VirtualFile, Path>()
  private val virtualFileToModule = ConcurrentHashMap<VirtualFile, KaModule>()
  private val moduleRoots = ConcurrentHashMap<KaModule, Set<Path>>()

  fun bindModule(module: KaModule, roots: Set<Path>) {
    moduleRoots[module] = roots.map(Path::normalize).toSet()
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

  fun unregister(path: Path) {
    pathToVirtualFile.remove(path.normalize())?.let {
      virtualFileToModule.remove(it)
      virtualFileToPath.remove(it)
    }
  }

  fun virtualFile(path: Path): VirtualFile? = pathToVirtualFile[path.normalize()]

  fun pathForVirtualFile(virtualFile: VirtualFile): Path? = virtualFileToPath[virtualFile]

  fun moduleForVirtualFile(virtualFile: VirtualFile): KaModule? = virtualFileToModule[virtualFile]

  fun moduleForPath(path: Path): KaModule? {
    val normalized = path.normalize()
    return moduleRoots.entries
        .asSequence()
        .flatMap { (module, roots) ->
          roots.asSequence()
              .filter { root -> normalized == root || normalized.startsWith(root) }
              .map { root -> module to root }
        }
        .maxByOrNull { (_, root) -> root.toString().length }
        ?.first
  }

  fun contains(virtualFile: VirtualFile): Boolean = virtualFileToModule.containsKey(virtualFile)

  fun isKotlinSource(path: Path): Boolean {
    val extension = path.extension
    return extension == "kt" || extension == "kts"
  }
}
