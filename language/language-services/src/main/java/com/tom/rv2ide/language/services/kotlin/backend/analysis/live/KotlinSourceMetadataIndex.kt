package com.tom.rv2ide.language.services.kotlin.backend.analysis.live

import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal data class KotlinSourceFileMetadata(
    val path: Path,
    val packageName: String,
    val topLevelClassifierNames: Set<String>,
    val topLevelCallableNames: Set<String>,
)

internal class KotlinSourceMetadataIndex {
  private val records = ConcurrentHashMap<Path, KotlinSourceFileMetadata>()
  private val packageToPaths = ConcurrentHashMap<String, MutableSet<Path>>()

  fun replace(path: Path, file: KtFile) {
    val normalized = path.normalize()
    remove(normalized)
    val metadata = KotlinSourceFileMetadata(
        path = normalized,
        packageName = file.packageFqName.asString(),
        topLevelClassifierNames = file.declarations.asSequence()
            .filterIsInstance<KtClassLikeDeclaration>()
            .mapNotNull { it.name }
            .toSet(),
        topLevelCallableNames = file.declarations.asSequence()
            .filterIsInstance<KtCallableDeclaration>()
            .mapNotNull { it.name }
            .toSet(),
    )
    records[normalized] = metadata
    packageToPaths.computeIfAbsent(metadata.packageName) { ConcurrentHashMap.newKeySet() }.add(normalized)
  }

  fun remove(path: Path) {
    val normalized = path.normalize()
    records.remove(normalized)?.let { record ->
      packageToPaths[record.packageName]?.let { paths ->
        paths.remove(normalized)
        if (paths.isEmpty()) packageToPaths.remove(record.packageName, paths)
      }
    }
  }

  fun filesForPackage(packageName: String): Set<Path> = packageToPaths[packageName]?.toSet().orEmpty()

  fun packageExists(packageName: String): Boolean = packageName.isEmpty() || packageToPaths.containsKey(packageName)

  fun subpackageNames(packageName: String): Set<String> {
    val prefix = if (packageName.isEmpty()) "" else "$packageName."
    return packageToPaths.keys.asSequence()
        .filter { it.startsWith(prefix) && it.length > prefix.length }
        .map { it.removePrefix(prefix).substringBefore('.') }
        .toSet()
  }

  fun metadata(path: Path): KotlinSourceFileMetadata? = records[path.normalize()]

  fun paths(): Set<Path> = records.keys.toSet()
}