package com.tom.rv2ide.language.services.kotlin.semantic

import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import java.nio.file.Paths
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinSemanticServiceTest {
  private val file = Paths.get("/project/Main.kt")
  private var snapshot: ActiveDocumentSnapshot? =
      ActiveDocumentSnapshot(file, 1, Instant.EPOCH, "val value = 1", 10L)
  private val connection = TestConnection()
  private fun validator() = KotlinSemanticRequestValidator(connection, snapshotProvider = { snapshot })

  @Test
  fun captureFreezesCursorAndRejectsWrongIdentity() {
    val validator = validator()
    val position = Position(0, 5)
    val request = validator.capture(file, position, 1, 10L, ICancelChecker.Default())!!
    position.column = 9
    assertEquals(5, request.column)
    assertEquals("val value = 1", request.snapshot.content)
    assertNull(validator.capture(file, Position(0, 5), 2, 10L, ICancelChecker.Default()))
    assertNull(validator.capture(file, Position(0, 5), 1, 11L, ICancelChecker.Default()))
    assertNull(validator.capture(file, Position(-1, 0), 1, 10L, ICancelChecker.Default()))
    assertNotNull(validator.capture(file, Position(0, 5), -1, -1L, ICancelChecker.Default()))
    snapshot = snapshot!!.copy(version = 2, revision = 11L, content = "changed")
    assertFalse(validator.isCurrent(request))
    assertEquals("val value = 1", request.snapshot.content)
  }

  @Test
  fun rejectsReopenGenerationCancellationAndUnavailableBackend() {
    val validator = validator()
    val checker = ICancelChecker.Default()
    val request = validator.capture(file, Position(0, 5), 1, 10L, checker)!!
    snapshot = snapshot!!.copy(revision = 20L)
    assertFalse(validator.isCurrent(request))
    snapshot = snapshot!!.copy(revision = 10L)
    connection.generation++
    assertFalse(validator.isCurrent(request))
    connection.generation--
    checker.cancel()
    assertFalse(validator.isCurrent(request))
    connection.isInitialized = false
    assertNull(validator.capture(file, Position(0, 5), 1, 10L, ICancelChecker.Default()))
    connection.isInitialized = true
    connection.isReady = false
    assertNull(validator.capture(file, Position(0, 5), 1, 10L, ICancelChecker.Default()))
    connection.isReady = true
    snapshot = null
    assertNull(validator.capture(file, Position(0, 5), -1, -1L, ICancelChecker.Default()))
  }

  @Test
  fun availabilityGuardRejectsCapturedAndNewRequests() {
    var available = true
    val validator = KotlinSemanticRequestValidator(connection, { available }, { snapshot })
    val request = validator.capture(file, Position(0, 5), 1, 10L, ICancelChecker.Default())!!
    available = false
    assertFalse(validator.isCurrent(request))
    assertNull(validator.capture(file, Position(0, 5), 1, 10L, ICancelChecker.Default()))
  }

  @Test
  fun serviceUsesSnapshotContentAndDropsResultChangedDuringExecution() = runBlocking {
    var received: KotlinSemanticRequest? = null
    val backend = object : KotlinSemanticBackend by EmptyKotlinSemanticBackend {
      override suspend fun complete(request: KotlinSemanticRequest): com.tom.rv2ide.lsp.models.CompletionResult {
        received = request
        snapshot = snapshot!!.copy(version = 2, revision = 11L)
        return com.tom.rv2ide.lsp.models.CompletionResult(emptyList())
      }
    }
    val service = KotlinSemanticService(backend, validator())
    val params = CompletionParams(Position(0, 5), file, ICancelChecker.Default()).apply {
      content = "stale caller content"
      prefix = "value"
      documentVersion = 1
      documentRevision = 10L
    }
    assertTrue(service.complete(params).items.isEmpty())
    assertEquals("val value = 1", received!!.snapshot.content)
    assertEquals("value", received!!.prefix)
    assertFalse(validator().isCurrent(received!!))
  }

  @Test
  fun serviceRejectsNonEmptyStaleResultAndCancelledCoroutine() = runBlocking {
    val backend = object : KotlinSemanticBackend by EmptyKotlinSemanticBackend {
      override suspend fun hover(request: KotlinSemanticRequest): MarkupContent {
        snapshot = snapshot!!.copy(version = 2, revision = 11L)
        return MarkupContent("stale value", MarkupKind.PLAIN)
      }
    }
    val service = KotlinSemanticService(backend, validator())
    val params = com.tom.rv2ide.lsp.models.DefinitionParams(file, Position(0, 5), ICancelChecker.Default())
    assertEquals("", service.hover(params).value)
    val entered = CompletableDeferred<Unit>()
    val waitingBackend = object : KotlinSemanticBackend by EmptyKotlinSemanticBackend {
      override suspend fun hover(request: KotlinSemanticRequest): MarkupContent {
        entered.complete(Unit)
        kotlinx.coroutines.awaitCancellation()
      }
    }
    val waitingService = KotlinSemanticService(waitingBackend, validator())
    val pending = async { waitingService.hover(params) }
    entered.await()
    pending.cancel()
    pending.join()
    assertTrue(pending.isCancelled)
  }

  @Test
  fun serviceCloseInvalidatesInFlightAndNewRequests() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var calls = 0
    val backend = object : KotlinSemanticBackend by EmptyKotlinSemanticBackend {
      override suspend fun hover(request: KotlinSemanticRequest): MarkupContent {
        calls++
        started.complete(Unit)
        release.await()
        return MarkupContent("late", MarkupKind.PLAIN)
      }
    }
    val service = KotlinSemanticService(backend, validator())
    val params = com.tom.rv2ide.lsp.models.DefinitionParams(file, Position(0, 5), ICancelChecker.Default())
    val pending = async { service.hover(params) }
    started.await()
    service.close()
    release.complete(Unit)
    assertEquals("", pending.await().value)
    assertEquals("", service.hover(params).value)
    assertEquals(1, calls)
  }

  private class TestConnection : KotlinBackendConnection {
    override var isReady = true
    override var isInitialized = true
    override var generation = 1L
    override fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) = Unit
    override fun startBackend(classpathProvider: KotlinProjectClasspathProvider) = true
    override fun sendRequest(method: String, params: JsonObject, callback: (JsonObject?) -> Unit) = callback(null)
    override fun sendNotification(method: String, params: JsonObject) = Unit
    override fun sendNotificationOrThrow(method: String, params: JsonObject) = Unit
    override fun close() { isReady = false }
  }
}