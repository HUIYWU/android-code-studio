package com.tom.rv2ide.language.services.kotlin.backend.fwcd

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinNotificationHandlerTest {
  @Test
  fun preservesVersionGenerationCodeSeverityAndRange() {
    val results = mutableListOf<DiagnosticResult>()
    val handler = KotlinNotificationHandler().apply { setDiagnosticsCallback { results.add(it) } }
    val item = diagnostic().apply {
      addProperty("code", 42)
      addProperty("source", "fwcd")
      addProperty("severity", 2)
    }
    handler.handle(notification(JsonArray().apply { add(item) }, 7), 3L)
    val result = results.single()
    assertEquals(DiagnosticResult.CHANNEL_KOTLIN, result.channel)
    assertEquals(7, result.documentVersion)
    assertEquals(DiagnosticResult.UNKNOWN_DOCUMENT_REVISION, result.documentRevision)
    assertEquals(3L, result.backendGeneration)
    val diagnostic = result.diagnostics.single()
    assertEquals("42", diagnostic.code)
    assertEquals("fwcd", diagnostic.source)
    assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
    assertEquals(1, diagnostic.range.start.line)
    assertEquals(2, diagnostic.range.start.column)
    assertEquals(4, diagnostic.range.end.column)
  }

  @Test
  fun emptyArrayClearsButMalformedBatchDoesNotPublish() {
    val results = mutableListOf<DiagnosticResult>()
    val handler = KotlinNotificationHandler().apply { setDiagnosticsCallback { results.add(it) } }
    handler.handle(notification(JsonArray()), 9L)
    assertTrue(results.single().diagnostics.isEmpty())
    assertEquals(DiagnosticResult.UNKNOWN_DOCUMENT_VERSION, results.single().documentVersion)
    handler.handle(notification(JsonArray().apply { add(JsonObject()) }), 9L)
    handler.handle(notification(JsonArray().apply { add(diagnostic()); add(JsonObject()) }), 9L)
    assertEquals(1, results.size)
  }

  private fun notification(items: JsonArray, version: Int? = null) = JsonObject().apply {
    addProperty("method", "textDocument/publishDiagnostics")
    add("params", JsonObject().apply {
      addProperty("uri", Paths.get("/project/Main.kt").toUri().toString())
      add("diagnostics", items)
      version?.let { addProperty("version", it) }
    })
  }

  private fun diagnostic() = JsonObject().apply {
    addProperty("message", "message")
    addProperty("code", "UNRESOLVED_REFERENCE")
    add("range", JsonObject().apply {
      add("start", position(1, 2))
      add("end", position(1, 4))
    })
  }

  private fun position(line: Int, column: Int) = JsonObject().apply {
    addProperty("line", line)
    addProperty("character", column)
  }
}
