package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import java.nio.file.Path

internal data class JavaUncheckedWarningDiagnostic(
    val file: Path,
    val documentVersion: Int,
    val documentRevision: Long,
    val highlightStart: Int,
    val highlightEnd: Int,
    val ownerStart: Int,
    val ownerEnd: Int,
    val ownerKind: OwnerKind,
) {
  enum class OwnerKind {
    CLASS,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    PARAMETER,
    LOCAL_VARIABLE,
  }

  companion object {
    const val INSPECTION_SHORT_NAME = "UNCHECKED_WARNING"
    const val SOURCE = "java-inspection"
    const val CODE = INSPECTION_SHORT_NAME

    fun from(item: DiagnosticItem): JavaUncheckedWarningDiagnostic? {
      val target = item.extra as? JavaUncheckedWarningDiagnostic ?: return null
      return target.takeIf {
        item.code == CODE && item.source == SOURCE && item.severity == DiagnosticSeverity.WARNING &&
            it.documentVersion >= 0 && it.documentRevision >= 0L &&
            it.highlightStart >= 0 && it.highlightEnd > it.highlightStart &&
            it.ownerStart >= 0 && it.ownerEnd > it.ownerStart
      }
    }
  }
}
