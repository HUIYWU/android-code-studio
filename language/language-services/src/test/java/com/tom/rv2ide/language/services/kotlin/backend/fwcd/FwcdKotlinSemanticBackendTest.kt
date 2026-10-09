package com.tom.rv2ide.language.services.kotlin.backend.fwcd

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentSync
import com.tom.rv2ide.language.services.kotlin.request.KotlinRequestHandler
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequestValidator
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import java.nio.file.Paths
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FwcdKotlinSemanticBackendTest {
  @Test
  fun routesTypedRequestsToFwcdJsonAndRejectsStaleResponses() = runBlocking {
    val file = Paths.get("/project/Main.kt")
    var snapshot = ActiveDocumentSnapshot(file, 1, Instant.EPOCH, "call(", 10L)
    val connection = TestConnection()
    val validator = KotlinSemanticRequestValidator(connection, snapshotProvider = { snapshot })
    val handler = KotlinRequestHandler(connection, KotlinDocumentSync(connection) { false }, validator)
    val backend = FwcdKotlinSemanticBackend(handler)
    val request = validator.capture(file, Position(0, 5), 1, 10L, ICancelChecker.Default(),
        includeDeclaration = true)!!
    assertEquals("FWCD hover", backend.hover(request).value)
    assertEquals("textDocument/hover", connection.lastMethod)
    assertEquals(file.toUri().toString(), connection.lastParams!!.getAsJsonObject("textDocument").get("uri").asString)
    assertEquals(5, connection.lastParams!!.getAsJsonObject("position").get("character").asInt)
    assertTrue(backend.findReferences(request).locations.isEmpty())
    assertEquals("textDocument/references", connection.lastMethod)
    assertTrue(connection.lastParams!!.getAsJsonObject("context").get("includeDeclaration").asBoolean)
    assertTrue(backend.findDefinition(request).locations.isEmpty())
    assertEquals("textDocument/definition", connection.lastMethod)
    assertTrue(backend.signatureHelp(request).signatures.isEmpty())
    assertEquals("textDocument/signatureHelp", connection.lastMethod)
    assertEquals("(", connection.lastParams!!.getAsJsonObject("context").get("triggerCharacter").asString)
    assertTrue(backend.complete(request).items.isEmpty())
    assertEquals("textDocument/completion", connection.lastMethod)
    connection.beforeResponse = { snapshot = snapshot.copy(version = 2, revision = 11L) }
    assertEquals("", backend.hover(request).value)
    assertFalse(validator.isCurrent(request))
  }

  private class TestConnection : KotlinBackendConnection {
    override val isReady = true
    override val isInitialized = true
    override val generation = 1L
    var lastMethod: String? = null
    var lastParams: JsonObject? = null
    var beforeResponse: () -> Unit = {}
    override fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) = Unit
    override fun startBackend(classpathProvider: KotlinProjectClasspathProvider) = true
    override fun sendRequest(method: String, params: JsonObject, callback: (JsonObject?) -> Unit) {
      lastMethod = method
      lastParams = params
      beforeResponse()
      callback(JsonObject().apply {
        when (method) {
          "textDocument/hover" -> addProperty("contents", "FWCD hover")
          "textDocument/signatureHelp" -> add("signatures", JsonArray())
          else -> add("result", JsonArray())
        }
      })
    }
    override fun sendNotification(method: String, params: JsonObject) = Unit
    override fun sendNotificationOrThrow(method: String, params: JsonObject) = Unit
    override fun close() = Unit
  }
}