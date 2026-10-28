package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.google.gson.JsonObject
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.analysisApiJar
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.createBackend
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.documentNotification
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.prepareAnalysisPaths
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runSuspend
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequestValidator
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisBackendFenceTest {

  @Test
  fun staleActiveDocumentRequest() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ActiveDocument.kt")
      val diskContent = file.readText()
      val firstContent = diskContent.replace("diskFunction(): Int = 1", "firstFunction(): Int = 1")
      val secondContent = firstContent.replace("firstFunction(): Int = 1", "secondFunction(): Int = 2")
      openDocument(file, firstContent, version = 1, revision = 11L)

      val backend = createBackend()
      try {
        assertTrue(backend.startBackend(KotlinProjectClasspathProvider(workspace)))
        var initialized = false
        backend.initialize(JsonObject()) { initialized = it != null }
        assertTrue(initialized)
        backend.sendNotification("textDocument/didOpen", documentNotification(file))
        val validator = KotlinSemanticRequestValidator(backend)
        val semanticBackend = AnalysisKotlinSemanticBackend(backend, validator)
        val firstPosition = LineIndex.from(firstContent).indexToPosition(firstContent.indexOf("firstFunction"))
        val oldRequest = requireNotNull(
            validator.capture(
                file,
                firstPosition,
                documentVersion = 1,
                documentRevision = 11L,
                cancelChecker = ICancelChecker.Default(),
            ),
        )
        val oldHover = runSuspend { semanticBackend.hover(oldRequest) }
        assertTrue(oldHover.value.contains("firstFunction"))

        changeDocument(file, firstContent, secondContent, version = 2, revision = 12L)
        backend.sendNotification("textDocument/didChange", documentNotification(file))
        val snapshot = requireNotNull(FileManager.getActiveDocumentSnapshot(file))
        assertEquals(secondContent, snapshot.content)
        assertEquals(2, snapshot.version)
        assertEquals(12L, snapshot.revision)
        assertTrue(!validator.isCurrent(oldRequest))
        assertEquals("", runSuspend { semanticBackend.hover(oldRequest) }.value)

        val secondPosition = LineIndex.from(secondContent).indexToPosition(secondContent.indexOf("secondFunction"))
        val currentRequest = requireNotNull(
            validator.capture(
                file,
                secondPosition,
                documentVersion = 2,
                documentRevision = 12L,
                cancelChecker = ICancelChecker.Default(),
            ),
        )
        val currentHover = runSuspend { semanticBackend.hover(currentRequest) }
        assertTrue(currentHover.value.contains("secondFunction"))
      } finally {
        backend.close()
      }
    }
  }

  @Test
  fun cancelledResultAfterRuntimeExecution() = runBlocking {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ActiveDocument.kt")
      val content = file.readText()
      openDocument(file, content, version = 1, revision = 1L)
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      prepareAnalysisPaths()
      val backend = KotlinAnalysisBackendConnection(
          KotlinAnalysisRuntimeFactory { provider ->
            val delegate = KotlinStandaloneAnalysisRuntime(provider, analysisApiJar())
            object : KotlinAnalysisRuntime by delegate {
              override fun hover(request: KotlinSemanticRequest): MarkupContent? {
                val result = delegate.hover(request)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return result
              }
            }
          },
      )
      try {
        assertTrue(backend.startBackend(KotlinProjectClasspathProvider(workspace)))
        var initialized = false
        backend.initialize(JsonObject()) { initialized = it != null }
        assertTrue(initialized)
        backend.sendNotification("textDocument/didOpen", documentNotification(file))
        val validator = KotlinSemanticRequestValidator(backend)
        val semanticBackend = AnalysisKotlinSemanticBackend(backend, validator)
        val position = LineIndex.from(content).indexToPosition(content.indexOf("diskFunction"))
        val checker = ICancelChecker.Default()
        val request = requireNotNull(
            validator.capture(
                file,
                position,
                documentVersion = 1,
                documentRevision = 1L,
                cancelChecker = checker,
            ),
        )
        val result = async(Dispatchers.Default) { semanticBackend.hover(request) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        checker.cancel()
        release.countDown()
        assertEquals("", runSuspend { withTimeout(5_000L) { result.await() } }.value)
      } finally {
        release.countDown()
        backend.close()
      }
    }
  }
}