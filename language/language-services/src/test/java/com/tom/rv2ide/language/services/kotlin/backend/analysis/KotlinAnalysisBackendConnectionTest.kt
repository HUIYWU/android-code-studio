package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendState
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
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

  private class FakeRuntime(private val startResult: Boolean) : KotlinAnalysisRuntime {
    var closed = false

    override fun start(): Boolean = startResult

    override fun analyze(
        path: Path,
        snapshot: com.tom.rv2ide.projects.models.ActiveDocumentSnapshot?,
    ) = null

    override fun close() {
      closed = true
    }
  }
}
