package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.analysisApiJar
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.documentNotification
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.prepareAnalysisPaths
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runSuspend
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequestValidator
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisReferencesCancellationTest {

  @Test
  fun multipleSearchCheckpointsAndRecovery() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val content = file.readText()
      openDocument(file, content, version = 5, revision = 5L)
      val appModule = workspace.androidProjects().single { it.path == ":app" }
      val variant = requireNotNull(appModule.getSelectedVariant())
      val generatedRoot = variant.mainArtifact.generatedSourceFolders
          .map { it.toPath().normalize() }
          .sortedBy(Path::toString)
          .firstOrNull()
          ?: error("Tooling fixture did not expose an AGP generated source root")
      Files.createDirectories(generatedRoot)
      val directory = Files.createTempDirectory(generatedRoot, "acs-references-fixture-")
      val fanoutFiles = (0 until 8).map { directory.resolve("ReferenceFanout$it.kt") }
      val returnedSearches = AtomicInteger()
      val cancelledSearches = AtomicInteger()
      var backend: KotlinAnalysisBackendConnection? = null
      try {
        fanoutFiles.forEachIndexed { fileIndex, path ->
          val source = buildString {
            append("package com.tom.rv2ide.testing.kotlinfixture.fanout\n\n")
            append("import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi\n\n")
            repeat(32) { referenceIndex ->
              append("fun reference${fileIndex}_$referenceIndex(): LibraryApi = LibraryApi()\n")
            }
          }
          Files.write(path, source.toByteArray(Charsets.UTF_8))
        }
        prepareAnalysisPaths()
        val connection = KotlinAnalysisBackendConnection(
            KotlinAnalysisRuntimeFactory { provider ->
              val delegate = KotlinStandaloneAnalysisRuntime(provider, analysisApiJar())
              object : KotlinAnalysisRuntime by delegate {
                override fun findReferences(request: KotlinSemanticRequest): ReferenceResult? {
                  return try {
                    delegate.findReferences(request).also { returnedSearches.incrementAndGet() }
                  } catch (cancelled: CancellationException) {
                    cancelledSearches.incrementAndGet()
                    throw cancelled
                  }
                }
              }
            },
        )
        backend = connection
        assertTrue(connection.startBackend(KotlinProjectClasspathProvider(workspace)))
        connection.markInitialized()
        assertTrue(connection.isInitialized)
        connection.sendNotificationOrThrow("textDocument/didOpen", documentNotification(file))
        val validator = KotlinSemanticRequestValidator(connection)
        val semanticBackend = AnalysisKotlinSemanticBackend(connection, validator)
        val position = LineIndex.from(content).indexToPosition(content.indexOf("LibraryApi"))

        fun search(checker: ICancelChecker): ReferenceResult {
          val captured = requireNotNull(
              validator.capture(
                  file,
                  position,
                  documentVersion = 5,
                  documentRevision = 5L,
                  cancelChecker = checker,
                  includeDeclaration = true,
              ),
          )
          return runSuspend {
            withTimeout(30_000L) { semanticBackend.findReferences(captured) }
          }
        }

        val baselineChecker = CheckpointCancelChecker()
        val baseline = search(baselineChecker)
        assertEquals(baseline.locations.size, baseline.roles.size)
        fanoutFiles.forEach { path ->
          assertTrue("References did not include fan-out file: $path", baseline.locations.count { it.file == path } >= 32)
        }
        assertEquals(1, returnedSearches.get())
        assertEquals(0, cancelledSearches.get())
        val checkpoints = baselineChecker.checkpoints.get()
        assertTrue("References did not traverse enough cancellation checkpoints", checkpoints > 300)

        listOf(checkpoints / 3, checkpoints * 2 / 3).forEachIndexed { index, cancelAt ->
          val checker = CheckpointCancelChecker(cancelAt)
          val cancelled = search(checker)
          assertTrue(checker.isCancelled())
          assertEquals(cancelAt, checker.checkpoints.get())
          assertTrue(cancelled.locations.isEmpty())
          assertTrue(cancelled.roles.isEmpty())
          assertEquals(index + 1, cancelledSearches.get())
          assertEquals(1, returnedSearches.get())
        }

        val recovered = search(ICancelChecker.Default())
        assertEquals(baseline.locations.toSet(), recovered.locations.toSet())
        assertEquals(2, returnedSearches.get())
        assertEquals(2, cancelledSearches.get())
      } finally {
        try {
          backend?.close()
        } finally {
          fanoutFiles.forEach { Files.deleteIfExists(it) }
          Files.deleteIfExists(directory)
        }
      }
    }
  }

  private class CheckpointCancelChecker(
      private val cancelAt: Int = Int.MAX_VALUE,
  ) : ICancelChecker {
    val checkpoints = AtomicInteger()
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
      cancelled.set(true)
    }

    override fun isCancelled(): Boolean = cancelled.get()

    override fun abortIfCancelled() {
      if (checkpoints.incrementAndGet() >= cancelAt) cancel()
      if (isCancelled()) throw CancellationException()
    }
  }
}