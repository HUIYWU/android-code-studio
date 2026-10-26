package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendState
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequestValidator
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinAnalysisBackendConnectionTest {

  @Test
  fun startInitializeRefreshAndShutdownAdvanceRuntimeGeneration() {
    val runtimes = mutableListOf<FakeRuntime>()
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory {
          FakeRuntime(true).also { runtimes += it }
        },
        activeDocuments = { emptyList() },
    )
    val classpathProvider = KotlinProjectClasspathProvider()

    assertTrue(connection.startBackend(classpathProvider))
    assertEquals(KotlinBackendState.READY, connection.state)
    assertEquals(1L, connection.generation)

    var initializeResult = false
    connection.initialize(com.google.gson.JsonObject()) { result ->
      initializeResult = result?.get("capabilities") != null
    }
    assertTrue(initializeResult)
    assertTrue(connection.isInitialized)

    assertTrue(connection.refreshEnvironment(classpathProvider))
    assertEquals(2L, connection.generation)
    assertEquals(2, runtimes.size)
    assertTrue(runtimes.first().closed)
    assertTrue(connection.isInitialized)

    connection.close()
    assertEquals(KotlinBackendState.CLOSED, connection.state)
    assertTrue(runtimes.last().closed)
  }

  @Test
  fun failedStartClosesRuntimeAndCanRecoverOnRefresh() {
    val index = AtomicInteger(0)
    val runtimes = mutableListOf<FakeRuntime>()
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory {
          val runtime = FakeRuntime(index.incrementAndGet() > 1)
          runtimes += runtime
          runtime
        },
        activeDocuments = { emptyList() },
    )
    val classpathProvider = KotlinProjectClasspathProvider()

    assertTrue(!connection.startBackend(classpathProvider))
    assertEquals(KotlinBackendState.FAILED, connection.state)
    assertTrue(runtimes.single().closed)

    assertTrue(connection.refreshEnvironment(classpathProvider))
    assertEquals(KotlinBackendState.READY, connection.state)
    assertEquals(1L, connection.generation)
    assertNotNull(runtimes.getOrNull(1))

    connection.close()
  }

  @Test
  fun semanticAdapterUsesCurrentRuntimeAndRejectsOldGenerationAndClosedBackend() = runBlocking {
    val runtimes = mutableListOf<FakeRuntime>()
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { FakeRuntime(true).also { runtimes += it } },
        activeDocuments = { emptyList() },
    )
    val file = Paths.get("/project/Main.kt")
    val snapshot = ActiveDocumentSnapshot(file, 1, Instant.EPOCH, "snapshot content", 10L)
    val provider = KotlinProjectClasspathProvider()
    try {
      assertTrue(connection.startBackend(provider))
      connection.initialize(com.google.gson.JsonObject()) {}
      val validator = KotlinSemanticRequestValidator(connection, snapshotProvider = { snapshot })
      val backend = AnalysisKotlinSemanticBackend(connection, validator)
      val oldRequest = validator.capture(file, Position(0, 0), 1, 10L, ICancelChecker.Default())!!
      assertEquals("snapshot content", backend.hover(oldRequest).value)
      assertEquals(1, runtimes.first().hoverCalls)
      assertTrue(runtimes.first().hoverThread!!.startsWith("acs-kotlin-analysis"))
      assertTrue(connection.refreshEnvironment(provider))
      assertEquals("", backend.hover(oldRequest).value)
      assertEquals(0, runtimes.last().hoverCalls)
      val request = validator.capture(file, Position(0, 0), 1, 10L, ICancelChecker.Default())!!
      assertEquals("snapshot content", backend.hover(request).value)
      connection.close()
      assertEquals("", backend.hover(request).value)
      assertEquals(1, runtimes.last().hoverCalls)
    } finally {
      connection.close()
    }
  }

  @Test
  fun cancellationDuringRuntimeExecutionRejectsResult() = runBlocking {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val runtime = FakeRuntime(true).apply { hoverEntered = entered; hoverRelease = release }
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )
    val file = Paths.get("/project/Main.kt")
    val snapshot = ActiveDocumentSnapshot(file, 1, Instant.EPOCH, "snapshot content", 10L)
    try {
      connection.startBackend(KotlinProjectClasspathProvider())
      connection.initialize(com.google.gson.JsonObject()) {}
      val checker = ICancelChecker.Default()
      val validator = KotlinSemanticRequestValidator(connection, snapshotProvider = { snapshot })
      val request = validator.capture(file, Position(0, 0), 1, 10L, checker)!!
      val pending = async(Dispatchers.Default) { connection.hover(request, validator::isCurrent) }
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      checker.cancel()
      release.countDown()
      assertNull(pending.await())
      assertFalse(validator.isCurrent(request))
    } finally {
      release.countDown()
      connection.close()
    }
  }

  @Test
  fun coroutineCancellationDoesNotWaitForRunningRuntime() = runBlocking {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val runtime = FakeRuntime(true).apply { hoverEntered = entered; hoverRelease = release }
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )
    val file = Paths.get("/project/Main.kt")
    val snapshot = ActiveDocumentSnapshot(file, 1, Instant.EPOCH, "snapshot content", 10L)
    try {
      connection.startBackend(KotlinProjectClasspathProvider())
      connection.initialize(com.google.gson.JsonObject()) {}
      val validator = KotlinSemanticRequestValidator(connection, snapshotProvider = { snapshot })
      val request = validator.capture(file, Position(0, 0), 1, 10L, ICancelChecker.Default())!!
      val pending = async(Dispatchers.Default) { connection.hover(request, validator::isCurrent) }
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      pending.cancel()
      withTimeout(1000) { pending.join() }
      assertTrue(pending.isCancelled)
    } finally {
      release.countDown()
      connection.close()
    }
  }

  @Test
  fun runtimeFailureCompletesSemanticRequestWithoutHanging() = runBlocking {
    val runtime = FakeRuntime(true).apply { hoverFailure = AssertionError("runtime failure") }
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )
    val file = Paths.get("/project/Main.kt")
    val snapshot = ActiveDocumentSnapshot(file, 1, Instant.EPOCH, "snapshot content", 10L)
    try {
      connection.startBackend(KotlinProjectClasspathProvider())
      connection.initialize(com.google.gson.JsonObject()) {}
      val validator = KotlinSemanticRequestValidator(connection, snapshotProvider = { snapshot })
      val request = validator.capture(file, Position(0, 0), 1, 10L, ICancelChecker.Default())!!
      assertNull(withTimeout(1000) { connection.hover(request, validator::isCurrent) })
    } finally {
      connection.close()
    }
  }

  @Test
  fun snapshotChangeDuringRuntimeExecutionRejectsResult() = runBlocking {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val runtime = FakeRuntime(true).apply { hoverEntered = entered; hoverRelease = release }
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )
    val file = Paths.get("/project/Main.kt")
    val snapshot = AtomicReference(ActiveDocumentSnapshot(file, 1, Instant.EPOCH, "snapshot content", 10L))
    try {
      connection.startBackend(KotlinProjectClasspathProvider())
      connection.initialize(com.google.gson.JsonObject()) {}
      val validator = KotlinSemanticRequestValidator(connection, snapshotProvider = { snapshot.get() })
      val request = validator.capture(file, Position(0, 0), 1, 10L, ICancelChecker.Default())!!
      val pending = async(Dispatchers.Default) { connection.hover(request, validator::isCurrent) }
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      snapshot.set(snapshot.get().copy(version = 2, revision = 11L))
      release.countDown()
      assertNull(pending.await())
      assertEquals(1, runtime.hoverCalls)
    } finally {
      release.countDown()
      connection.close()
    }
  }

  @Test
  fun oldGenerationRefreshIsDroppedAndCurrentGenerationRefreshRuns() {
    val runtimes = mutableListOf<FakeRuntime>()
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory {
          FakeRuntime(true).also { runtimes += it }
        },
        activeDocuments = { emptyList() },
    )
    val provider = KotlinProjectClasspathProvider()
    val path = Paths.get("/project/Main.kt")
    val oldRefreshCalls = AtomicInteger(0)
    val currentRefreshCalls = AtomicInteger(0)
    val currentRefreshCompleted = CountDownLatch(1)
    try {
      assertTrue(connection.startBackend(provider))
      assertTrue(connection.refreshEnvironment(provider))

      runtimes.first().triggerIndexing(path) { oldRefreshCalls.incrementAndGet() }
      runtimes.last().triggerIndexing(path) {
        currentRefreshCalls.incrementAndGet()
        currentRefreshCompleted.countDown()
      }

      assertTrue(currentRefreshCompleted.await(2, TimeUnit.SECONDS))
      assertEquals(0, oldRefreshCalls.get())
      assertEquals(1, currentRefreshCalls.get())
    } finally {
      connection.close()
    }
  }

  @Test
  fun closeIsIdempotentAndClosesRuntimeOnce() {
    val runtime = FakeRuntime(true)
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )

    assertTrue(connection.startBackend(KotlinProjectClasspathProvider()))

    connection.close()
    connection.close()

    assertEquals(KotlinBackendState.CLOSED, connection.state)
    assertEquals(1, runtime.closeCalls)
  }

  @Test
  fun closePreventsLateRefreshFromOldRuntime() {
    val runtimes = mutableListOf<FakeRuntime>()
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory {
          FakeRuntime(true).also { runtimes += it }
        },
        activeDocuments = { emptyList() },
    )
    val path = Paths.get("/project/Main.kt")
    val refreshCalls = AtomicInteger(0)
    assertTrue(connection.startBackend(KotlinProjectClasspathProvider()))
    connection.close()

    runtimes.single().triggerIndexing(path) { refreshCalls.incrementAndGet() }

    assertEquals(KotlinBackendState.CLOSED, connection.state)
    assertEquals(0, refreshCalls.get())
  }

  @Test
  fun closeCancelsActiveSemanticRequestAndClosesRuntime() = runBlocking {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val runtime = FakeRuntime(true).apply { hoverEntered = entered; hoverRelease = release }
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )
    val file = Paths.get("/project/Main.kt")
    val snapshot = ActiveDocumentSnapshot(file, 1, Instant.EPOCH, "snapshot content", 10L)
    try {
      assertTrue(connection.startBackend(KotlinProjectClasspathProvider()))
      connection.initialize(com.google.gson.JsonObject()) {}
      val validator = KotlinSemanticRequestValidator(connection, snapshotProvider = { snapshot })
      val request = validator.capture(file, Position(0, 0), 1, 10L, ICancelChecker.Default())!!
      val pending = async(Dispatchers.Default) { connection.hover(request, validator::isCurrent) }
      assertTrue(entered.await(2, TimeUnit.SECONDS))

      val closing = async(Dispatchers.Default) { connection.close() }
      withTimeout(1000) {
        while (connection.isReady) yield()
      }
      release.countDown()

      assertNull(pending.await())
      closing.await()
      assertEquals(KotlinBackendState.CLOSED, connection.state)
      assertTrue(runtime.closed)
    } finally {
      release.countDown()
      connection.close()
    }
  }

  @Test
  fun closeCancelsQueuedRefreshBeforeExecution() = runBlocking {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val refreshCalls = AtomicInteger(0)
    val runtime = FakeRuntime(true).apply { hoverEntered = entered; hoverRelease = release }
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )
    val file = Paths.get("/project/Main.kt")
    val snapshot = ActiveDocumentSnapshot(file, 1, Instant.EPOCH, "snapshot content", 10L)
    try {
      assertTrue(connection.startBackend(KotlinProjectClasspathProvider()))
      connection.initialize(com.google.gson.JsonObject()) {}
      val validator = KotlinSemanticRequestValidator(connection, snapshotProvider = { snapshot })
      val request = validator.capture(file, Position(0, 0), 1, 10L, ICancelChecker.Default())!!
      val pending = async(Dispatchers.Default) { connection.hover(request, validator::isCurrent) }
      assertTrue(entered.await(2, TimeUnit.SECONDS))

      runtime.triggerIndexing(file) { refreshCalls.incrementAndGet() }
      val closing = async(Dispatchers.Default) { connection.close() }
      withTimeout(1000) {
        while (connection.isReady) yield()
      }
      release.countDown()

      assertNull(pending.await())
      closing.await()
      assertEquals(0, refreshCalls.get())
      assertEquals(KotlinBackendState.CLOSED, connection.state)
    } finally {
      release.countDown()
      connection.close()
    }
  }

  @Test
  fun closeBeforeDiagnosticsDebouncePreventsAnalyzeAndCallback() {
    val file = Files.createTempFile("kotlin-analysis-diagnostics-debounce", ".kt")
    val runtime = FakeRuntime(true)
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )
    val published = AtomicInteger(0)
    try {
      FileManager.onDocumentOpen(DocumentOpenEvent(file, "val value = 1", 1))
      assertTrue(connection.startBackend(KotlinProjectClasspathProvider()))
      connection.initialize(com.google.gson.JsonObject()) {}
      connection.setDiagnosticsCallback { published.incrementAndGet() }
      connection.sendNotification(
          "textDocument/didOpen",
          com.google.gson.JsonObject().apply {
            add("textDocument", com.google.gson.JsonObject().apply {
              addProperty("uri", file.toUri().toString())
            })
          },
      )

      connection.close()

      assertFalse(runtime.analyzeEntered.await(600, TimeUnit.MILLISECONDS))
      assertEquals(0, runtime.analyzeCalls)
      assertEquals(0, published.get())
      assertTrue(runtime.closed)
    } finally {
      connection.close()
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun interactiveRequestCancelsDiagnosticsAndReschedulesExactlyOnce() = runBlocking {
    val file = Files.createTempFile("kotlin-analysis-diagnostics-reschedule", ".kt")
    val runtime = FakeRuntime(true).apply {
      analyzeBlockCallCount = 1
    }
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )
    val published = AtomicInteger(0)
    val publishedSignal = CountDownLatch(1)
    try {
      FileManager.onDocumentOpen(DocumentOpenEvent(file, "val value = 1", 1))
      assertTrue(connection.startBackend(KotlinProjectClasspathProvider()))
      connection.initialize(com.google.gson.JsonObject()) {}
      connection.setDiagnosticsCallback {
        published.incrementAndGet()
        publishedSignal.countDown()
      }
      connection.sendNotification(
          "textDocument/didOpen",
          com.google.gson.JsonObject().apply {
            add("textDocument", com.google.gson.JsonObject().apply {
              addProperty("uri", file.toUri().toString())
            })
          },
      )
      assertTrue(runtime.analyzeEntered.await(2, TimeUnit.SECONDS))

      val snapshot = checkNotNull(FileManager.getActiveDocumentSnapshot(file))
      val validator = KotlinSemanticRequestValidator(
          connection,
          snapshotProvider = { FileManager.getActiveDocumentSnapshot(file) },
      )
      val request = validator.capture(
          file,
          Position(0, 0),
          snapshot.version,
          snapshot.revision,
          ICancelChecker.Default(),
      )!!

      assertEquals("val value = 1", connection.hover(request, validator::isCurrent)!!.value)
      assertTrue(runtime.analyzeFinished.await(2, TimeUnit.SECONDS))
      assertTrue(waitForAnalyzeCalls(runtime, 2, 2_000L))
      assertTrue(publishedSignal.await(2, TimeUnit.SECONDS))
      assertEquals(1, published.get())

      Thread.sleep(600L)
      assertEquals(2, runtime.analyzeCalls)
    } finally {
      connection.close()
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun closeCancelsActiveDiagnosticsAndSuppressesCallback() {
    val file = Files.createTempFile("kotlin-analysis-active-diagnostics", ".kt")
    val entered = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val runtime = FakeRuntime(true).apply {
      analyzeEntered = entered
      analyzeFinished = finished
      analyzeUntilCancelled = true
    }
    val connection = KotlinAnalysisBackendConnection(
        KotlinAnalysisRuntimeFactory { runtime }, activeDocuments = { emptyList() },
    )
    val published = AtomicInteger(0)
    try {
      FileManager.onDocumentOpen(DocumentOpenEvent(file, "val value = 1", 1))
      assertTrue(connection.startBackend(KotlinProjectClasspathProvider()))
      connection.initialize(com.google.gson.JsonObject()) {}
      connection.setDiagnosticsCallback { published.incrementAndGet() }
      connection.sendNotification(
          "textDocument/didOpen",
          com.google.gson.JsonObject().apply {
            add("textDocument", com.google.gson.JsonObject().apply {
              addProperty("uri", file.toUri().toString())
            })
          },
      )
      assertTrue(entered.await(2, TimeUnit.SECONDS))

      connection.close()

      assertTrue(finished.await(2, TimeUnit.SECONDS))
      assertEquals(1, runtime.analyzeCalls)
      assertEquals(0, published.get())
      assertEquals(KotlinBackendState.CLOSED, connection.state)
      assertTrue(runtime.closed)
    } finally {
      connection.close()
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  private fun waitForAnalyzeCalls(runtime: FakeRuntime, expected: Int, timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
      if (runtime.analyzeCalls >= expected) return true
      Thread.yield()
    }
    return runtime.analyzeCalls >= expected
  }

  private class FakeRuntime(private val startResult: Boolean) : KotlinAnalysisRuntime {
    var closed = false
    var closeCalls = 0
    var hoverCalls = 0
    var hoverFailure: Throwable? = null
    var hoverThread: String? = null
    var hoverEntered: CountDownLatch? = null
    var hoverRelease: CountDownLatch? = null
    @Volatile var analyzeCalls = 0
    var analyzeEntered = CountDownLatch(1)
    var analyzeFinished = CountDownLatch(1)
    var analyzeUntilCancelled = false
    var analyzeBlockCallCount = 0
    private var indexingScheduler: ((Path, () -> Unit) -> Unit)? = null

    override fun configureIndexingScheduler(schedule: (Path, () -> Unit) -> Unit) {
      indexingScheduler = schedule
    }

    fun triggerIndexing(path: Path, action: () -> Unit) {
      indexingScheduler?.invoke(path, action)
    }

    override fun start(): Boolean = startResult

    override fun analyze(
        path: Path,
        snapshot: com.tom.rv2ide.projects.models.ActiveDocumentSnapshot?,
        cancelChecker: ICancelChecker,
    ): DiagnosticResult? {
      analyzeCalls++
      val shouldBlock = analyzeUntilCancelled || analyzeCalls <= analyzeBlockCallCount
      if (shouldBlock) {
        analyzeEntered.countDown()
        try {
          while (!cancelChecker.isCancelled()) {
            Thread.sleep(20)
          }
        } finally {
          analyzeFinished.countDown()
        }
      }
      return DiagnosticResult(
          path,
          emptyList(),
          DiagnosticResult.CHANNEL_KOTLIN,
          snapshot?.version ?: DiagnosticResult.UNKNOWN_DOCUMENT_VERSION,
          snapshot?.revision ?: DiagnosticResult.UNKNOWN_DOCUMENT_REVISION,
      )
    }

    override fun complete(request: KotlinSemanticRequest) = null
    override fun findDefinition(request: KotlinSemanticRequest) = null
    override fun findReferences(request: KotlinSemanticRequest) = null
    override fun signatureHelp(request: KotlinSemanticRequest) = null
    override fun hover(request: KotlinSemanticRequest): MarkupContent {
      hoverCalls++
      hoverFailure?.let { throw it }
      hoverThread = Thread.currentThread().name
      hoverEntered?.countDown()
      hoverRelease?.let { check(it.await(2, TimeUnit.SECONDS)) }
      return MarkupContent(request.snapshot.content, MarkupKind.PLAIN)
    }

    override fun close() {
      closeCalls++
      closed = true
    }
  }
}
