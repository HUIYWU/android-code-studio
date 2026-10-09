package com.tom.rv2ide.language.services.kotlin.semantic

import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import java.nio.file.Path

internal data class KotlinSemanticRequest(
    val snapshot: ActiveDocumentSnapshot,
    val line: Int,
    val column: Int,
    val backendGeneration: Long,
    val cancelChecker: ICancelChecker,
    val prefix: String? = null,
    val includeDeclaration: Boolean = false,
) {
  val file: Path
    get() = snapshot.file

  fun position(): Position = Position(line, column)
}

internal class KotlinSemanticRequestValidator(
    private val connection: KotlinBackendConnection,
    private val isAvailable: () -> Boolean = { true },
    private val snapshotProvider: (Path) -> ActiveDocumentSnapshot? = FileManager::getActiveDocumentSnapshot,
) {
  fun capture(
      file: Path,
      position: Position,
      documentVersion: Int,
      documentRevision: Long,
      cancelChecker: ICancelChecker,
      prefix: String? = null,
      includeDeclaration: Boolean = false,
  ): KotlinSemanticRequest? {
    if (position.line < 0 || position.column < 0) return null
    val generation = connection.generation
    val path = file.normalize()
    val snapshot = snapshotProvider(path) ?: return null
    if (documentVersion >= 0 && documentVersion != snapshot.version) return null
    if (documentRevision >= 0 && documentRevision != snapshot.revision) return null
    val request = KotlinSemanticRequest(
        snapshot.copy(file = path),
        position.line,
        position.column,
        generation,
        cancelChecker,
        prefix,
        includeDeclaration,
    )
    return request.takeIf(::isCurrent)
  }

  fun isCurrent(request: KotlinSemanticRequest): Boolean {
    if (!isAvailable() || !connection.isReady || !connection.isInitialized ||
        request.backendGeneration != connection.generation ||
        request.cancelChecker.isCancelled() || !connection.supportsDocument(request.file)) return false
    val current = snapshotProvider(request.file) ?: return false
    return current.version == request.snapshot.version && current.revision == request.snapshot.revision
  }
}
