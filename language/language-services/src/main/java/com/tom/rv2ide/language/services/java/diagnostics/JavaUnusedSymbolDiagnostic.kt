package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import java.nio.file.Path

internal data class JavaUnusedSymbolDiagnostic(
    val file: Path,
    val documentVersion: Int,
    val documentRevision: Long,
    val highlightStart: Int,
    val highlightEnd: Int,
    val declarationStart: Int,
    val declarationEnd: Int,
    val name: String,
    val declarationKind: DeclarationKind,
) {
  enum class DeclarationKind {
    LOCAL_VARIABLE,
    RESOURCE_VARIABLE,
    PARAMETER,
    PATTERN_VARIABLE,
    FIELD,
    ENUM_CONSTANT,
    METHOD,
    CONSTRUCTOR,
    CLASS,
    TYPE_PARAMETER,
  }

  companion object {
    const val INSPECTION_SHORT_NAME = "unused"
    const val SOURCE = "java-inspection"
    const val CODE = "java.inspection.unused.symbol"

    fun from(item: DiagnosticItem): JavaUnusedSymbolDiagnostic? {
      val target = item.extra as? JavaUnusedSymbolDiagnostic ?: return null
      return target.takeIf {
        item.code == CODE && item.source == SOURCE && item.severity == DiagnosticSeverity.WARNING &&
            it.documentVersion >= 0 && it.documentRevision >= 0L &&
            it.declarationStart >= 0 && it.declarationEnd > it.declarationStart &&
            it.highlightStart >= it.declarationStart && it.highlightEnd <= it.declarationEnd &&
            it.highlightEnd > it.highlightStart && it.name.isNotEmpty()
      }
    }
  }
}
