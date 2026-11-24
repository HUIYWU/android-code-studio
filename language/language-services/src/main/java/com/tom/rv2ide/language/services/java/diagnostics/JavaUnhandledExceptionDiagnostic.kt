package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.lsp.models.DiagnosticItem
import java.nio.file.Path

internal data class JavaUnhandledExceptionDiagnostic(
    val file: Path,
    val documentVersion: Int,
    val documentRevision: Long,
    val highlightStart: Int,
    val highlightEnd: Int,
    val methodStart: Int,
    val methodEnd: Int,
    val ownerStart: Int,
    val ownerEnd: Int,
    val ownerQualifiedName: String?,
    val methodName: String,
    val constructor: Boolean,
    val erasedParameterTypes: List<String>,
    val exceptionQualifiedNames: List<String>,
) {
  companion object {
    const val CODESERVER_KEY = "exception.unhandled"
    const val CODESERVER_SOURCE = "java-codeserver"

    fun from(item: DiagnosticItem): JavaUnhandledExceptionDiagnostic? {
      val target = item.extra as? JavaUnhandledExceptionDiagnostic ?: return null
      return target.takeIf {
        item.code == CODESERVER_KEY && item.source == CODESERVER_SOURCE &&
            it.documentVersion >= 0 && it.documentRevision >= 0L && it.exceptionQualifiedNames.isNotEmpty()
      }
    }
  }
}
