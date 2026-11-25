package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.lsp.models.DiagnosticItem
import java.nio.file.Path

internal data class JavaUnresolvedMethodDiagnostic(
    val file: Path,
    val documentVersion: Int,
    val documentRevision: Long,
    val highlightStart: Int,
    val highlightEnd: Int,
    val callStart: Int,
    val callEnd: Int,
    val ownerStart: Int,
    val ownerEnd: Int,
    val methodName: String,
) {
  companion object {
    const val CODE = "java.psi.unresolved.method"
    const val SOURCE = "java-psi"

    fun from(item: DiagnosticItem): JavaUnresolvedMethodDiagnostic? {
      val target = item.extra as? JavaUnresolvedMethodDiagnostic ?: return null
      return target.takeIf {
        item.code == CODE && item.source == SOURCE &&
            it.documentVersion >= 0 && it.documentRevision >= 0L &&
            it.highlightStart >= 0 && it.highlightEnd > it.highlightStart &&
            it.callStart >= 0 && it.callEnd > it.callStart && it.ownerStart >= 0 &&
            it.ownerEnd > it.ownerStart && it.methodName.isNotEmpty()
      }
    }
  }
}
