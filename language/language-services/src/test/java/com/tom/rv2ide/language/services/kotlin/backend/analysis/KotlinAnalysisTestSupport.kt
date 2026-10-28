package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.google.gson.JsonObject
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.testing.LspWorkspaceScope
import com.tom.rv2ide.lsp.testing.LspWorkspaceTestSupport
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import com.tom.rv2ide.utils.FileProvider
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

internal object KotlinAnalysisTestSupport {

  fun withWorkspace(action: LspWorkspaceScope.() -> Unit) {
    IdeLogConfig.setIdeLogsEnabledProvider { true }
    IdeLogConfig.setIdeDebugLogsEnabledProvider { true }
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot(), action)
  }

  fun withRuntime(workspace: IWorkspace, action: (KotlinStandaloneAnalysisRuntime) -> Unit) {
    prepareAnalysisPaths()
    val runtime = KotlinStandaloneAnalysisRuntime(KotlinProjectClasspathProvider(workspace), analysisApiJar())
    try {
      val started = runtime.start()
      assertTrue(
          "${runtime.startFailureReason ?: "Kotlin Analysis runtime failed to start"}\n${runtime.startFailure?.stackTraceToString().orEmpty()}",
          started,
      )
      action(runtime)
    } finally {
      runtime.close()
    }
  }

  fun <T> runAnalysis(action: () -> T): T = CompletableFuture.supplyAsync(action).join()

  fun <T> runSuspend(action: suspend () -> T): T = runBlocking { action() }

  fun fixturePath(relative: String): Path =
      FileProvider.kotlinAnalysisProjectRoot().resolve(relative).normalize()

  fun analysisApiJar(): String =
      FileProvider.projectRoot()
          .resolve("docs/misc/kotlin-android/analysis-api-standalone-embeddable-for-ide-2.3.255-SNAPSHOT.jar")
          .toString()

  fun createBackend(): KotlinAnalysisBackendConnection {
    prepareAnalysisPaths()
    return KotlinAnalysisBackendConnection(analysisApiJar())
  }

  fun documentNotification(file: Path): JsonObject =
      JsonObject().apply {
        add(
            "textDocument",
            JsonObject().apply { addProperty("uri", file.normalize().toUri().toString()) },
        )
      }

  fun prepareAnalysisPaths() {
    val home = File(BaseApplication.getBaseInstance()!!.filesDir, "kotlin-analysis-fixture")
    val config = File(home, "config")
    val system = File(home, "system")
    listOf(home, config, system).forEach { check(it.isDirectory || it.mkdirs()) }
    System.setProperty("idea.home.path", home.absolutePath)
    System.setProperty("idea.config.path", config.absolutePath)
    System.setProperty("idea.system.path", system.absolutePath)
  }

  fun diskRequest(
      file: Path,
      content: String,
      marker: String,
      occurrence: Int = 0,
      offset: Int? = null,
  ): KotlinSemanticRequest {
    val resolvedOffset = offset ?: content.offsetOf(marker, occurrence)
    val position = LineIndex.from(content).indexToPosition(resolvedOffset)
    return KotlinSemanticRequest(
        snapshot = ActiveDocumentSnapshot(file, -1, Instant.EPOCH, content, -1L),
        line = position.line,
        column = position.column,
        backendGeneration = 1L,
        cancelChecker = ICancelChecker.NOOP,
        prefix = marker,
    )
  }

  fun request(
       file: Path,
       content: String,
       marker: String,
       includeDeclaration: Boolean = false,
       occurrence: Int = 0,
       offset: Int? = null,
   ): KotlinSemanticRequest {
     val resolvedOffset = offset ?: content.offsetOf(marker, occurrence)
     val position = LineIndex.from(content).indexToPosition(resolvedOffset)
     val snapshot = requireNotNull(FileManager.getActiveDocumentSnapshot(file))
     return KotlinSemanticRequest(
         snapshot = ActiveDocumentSnapshot(file, snapshot.version, Instant.EPOCH, content, snapshot.revision),
         line = position.line,
         column = position.column,
         backendGeneration = 1L,
         cancelChecker = ICancelChecker.NOOP,
         prefix = marker,
         includeDeclaration = includeDeclaration,
     )
   }

  private fun String.offsetOf(marker: String, occurrence: Int): Int {
    require(occurrence >= 0) { "Occurrence must be non-negative: $occurrence" }
    var fromIndex = 0
    repeat(occurrence + 1) {
      fromIndex = indexOf(marker, fromIndex)
      require(fromIndex >= 0) { "Fixture marker occurrence not found: marker=$marker occurrence=$occurrence" }
      if (it < occurrence) fromIndex += marker.length
    }
    return fromIndex
  }
}
