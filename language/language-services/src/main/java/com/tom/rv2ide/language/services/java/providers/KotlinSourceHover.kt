package com.tom.rv2ide.language.services.java.providers

import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.projects.FileManager

object KotlinSourceHover {
  @JvmStatic
  fun find(params: DefinitionParams): MarkupContent? {
    params.cancelChecker.abortIfCancelled()
    val snapshot = FileManager.getActiveDocumentSnapshot(params.file) ?: return null
    if (
        params.documentVersion >= 0 && snapshot.version != params.documentVersion ||
            params.documentRevision >= 0L && snapshot.revision != params.documentRevision
    ) {
      return null
    }
    val offset = LineIndex.from(snapshot.content)
        .lineColumnToIndex(params.position.line, params.position.column)
    val content = JavaPsiInterop.kotlinHover(params.file, offset, params.cancelChecker)
    params.cancelChecker.abortIfCancelled()
    val current = FileManager.getActiveDocumentSnapshot(params.file) ?: return null
    return if (current.version == snapshot.version && current.revision == snapshot.revision) {
      content
    } else {
      null
    }
  }
}
