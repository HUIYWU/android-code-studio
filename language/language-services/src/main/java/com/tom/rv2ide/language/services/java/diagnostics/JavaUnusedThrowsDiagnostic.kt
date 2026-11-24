package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.language.services.java.models.DiagnosticCode
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import java.nio.file.Path

internal data class JavaUnusedThrowsDiagnostic(
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
    val exceptionQualifiedName: String,
    val referenceStart: Int,
    val referenceEnd: Int,
    val referenceText: String,
) {
  companion object {
    const val INSPECTION_SHORT_NAME = "RedundantThrowsDeclaration"
    const val INSPECTION_SOURCE = "java-inspection"

    fun from(item: DiagnosticItem): JavaUnusedThrowsDiagnostic? {
      val target = item.extra as? JavaUnusedThrowsDiagnostic ?: return null
      return target.takeIf {
        item.code == DiagnosticCode.UNUSED_THROWS.id && item.source == INSPECTION_SOURCE &&
            item.severity == DiagnosticSeverity.WARNING && it.documentVersion >= 0 &&
            it.documentRevision >= 0L && it.highlightStart <= it.referenceStart &&
            it.referenceEnd <= it.highlightEnd && it.referenceEnd > it.referenceStart &&
            it.referenceText.isNotEmpty() && it.exceptionQualifiedName.isNotEmpty()
      }
    }
  }
}
