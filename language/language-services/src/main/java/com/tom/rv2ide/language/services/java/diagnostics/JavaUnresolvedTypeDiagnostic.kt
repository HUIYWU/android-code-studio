package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.lsp.models.DiagnosticItem
import java.nio.file.Path

internal data class JavaUnresolvedTypeDiagnostic(
    val file: Path,
    val documentVersion: Int,
    val documentRevision: Long,
    val highlightStart: Int,
    val highlightEnd: Int,
    val referenceStart: Int,
    val referenceEnd: Int,
    val referenceText: String,
    val simpleName: String,
    val qualifierSuffix: List<String> = emptyList(),
) {
  companion object {
    const val CODESERVER_SOURCE = "java-codeserver"
    const val REFERENCE_UNRESOLVED_KEY = "reference.unresolved"
    const val TYPE_UNKNOWN_CLASS_KEY = "type.unknown.class"

    fun from(item: DiagnosticItem): JavaUnresolvedTypeDiagnostic? {
      val target = item.extra as? JavaUnresolvedTypeDiagnostic ?: return null
      return target.takeIf {
        item.source == CODESERVER_SOURCE && item.code in setOf(REFERENCE_UNRESOLVED_KEY, TYPE_UNKNOWN_CLASS_KEY) &&
            it.documentVersion >= 0 && it.documentRevision >= 0L && it.referenceEnd > it.referenceStart &&
            it.referenceText == it.simpleName && it.simpleName.isNotEmpty()
      }
    }
  }
}
