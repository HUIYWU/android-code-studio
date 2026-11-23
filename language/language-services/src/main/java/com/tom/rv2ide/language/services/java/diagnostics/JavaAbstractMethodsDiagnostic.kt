package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.language.services.java.models.DiagnosticCode
import com.tom.rv2ide.lsp.models.DiagnosticItem
import java.nio.file.Path

internal data class JavaAbstractMethodsDiagnostic(
    val file: Path,
    val documentVersion: Int,
    val documentRevision: Long,
    val targetOffset: Int,
    val anonymous: Boolean,
    val targetName: String?,
    val baseTypeName: String?,
    val highlightStart: Int,
    val highlightEnd: Int,
    val backend: Backend,
) {
  enum class Backend { CODESERVER, JAVAC }

  companion object {
    const val CODESERVER_KEY = "class.must.implement.method"
    const val CODESERVER_SOURCE = "java-codeserver"

    fun from(item: DiagnosticItem): JavaAbstractMethodsDiagnostic? {
      val target = item.extra as? JavaAbstractMethodsDiagnostic ?: return null
      val matches = when (target.backend) {
        Backend.CODESERVER -> item.code == CODESERVER_KEY && item.source == CODESERVER_SOURCE
        Backend.JAVAC -> item.code == DiagnosticCode.DOES_NOT_OVERRIDE_ABSTRACT.id && item.source.isEmpty()
      }
      return target.takeIf { matches && target.documentVersion >= 0 && target.documentRevision >= 0L }
    }
  }
}
