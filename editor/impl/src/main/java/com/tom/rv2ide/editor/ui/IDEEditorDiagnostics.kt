package com.tom.rv2ide.editor.ui

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.models.DiagnosticItem
import org.slf4j.LoggerFactory

/** Diagnostic handling extensions for IDEEditor - Simplified Version */
private val log = LoggerFactory.getLogger("IDEEditorDiagnostics")

// Storage key for diagnostic handler
private const val DIAGNOSTIC_HANDLER_KEY = "diagnostic_handler"

private class DiagnosticHandler {
  private val diagnosticsByLine = mutableMapOf<Int, MutableList<DiagnosticItem>>()

  fun updateDiagnostics(diagnostics: List<DiagnosticItem>) {
    diagnosticsByLine.clear()
    diagnostics.forEach { diagnostic ->
      val line = diagnostic.range.start.line
      diagnosticsByLine.getOrPut(line) { mutableListOf() }.add(diagnostic)
    }
    if (IdeLogConfig.shouldLogInfo()) {
      log.info("Updated diagnostics for {} lines", diagnosticsByLine.size)
    }
  }

  fun getDiagnosticsAtLine(line: Int): List<DiagnosticItem> {
    return diagnosticsByLine[line] ?: emptyList()
  }

  fun getDiagnosticAt(line: Int, column: Int): DiagnosticItem? {
    return diagnosticsByLine[line]?.firstOrNull { diagnostic ->
      val range = diagnostic.range
      line == range.start.line && column >= range.start.column && column <= range.end.column
    }
  }

  fun clear() {
    diagnosticsByLine.clear()
  }
}

/** Get or create diagnostic handler for this editor */
private fun IDEEditor.getDiagnosticHandler(): DiagnosticHandler {
  var handler = getTag(DIAGNOSTIC_HANDLER_KEY.hashCode()) as? DiagnosticHandler
  if (handler == null) {
    handler = DiagnosticHandler()
    setTag(DIAGNOSTIC_HANDLER_KEY.hashCode(), handler)
  }
  return handler
}

/** Initialize diagnostic handling for the editor Simplified version - no touch listener */
fun IDEEditor.initDiagnosticHandling() {
  // Just create the handler
  getDiagnosticHandler()
  if (IdeLogConfig.shouldLogInfo()) {
    log.info("Diagnostic handling initialized for editor")
  }
}

/** Update diagnostics in the editor */
fun IDEEditor.updateEditorDiagnostics(diagnostics: List<DiagnosticItem>) {
  getDiagnosticHandler().updateDiagnostics(diagnostics)
}

/** Clear all diagnostics */
fun IDEEditor.clearDiagnostics() {
  getDiagnosticHandler().clear()
}
