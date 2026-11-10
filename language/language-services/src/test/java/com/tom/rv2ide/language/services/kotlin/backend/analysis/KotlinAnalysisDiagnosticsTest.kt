package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.lsp.models.LineIndex
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisDiagnosticsTest {

  @Test
  fun diagnosticsKeepCodesAndRanges() {
    val dir =
        KotlinAnalysisTestSupport.fixturePath(
            "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app"
        )
    Files.createDirectories(dir)
    val file = Files.createTempFile(dir, "DiagnosticProbe", ".kt")
    val content =
        """
        package com.tom.rv2ide.testing.kotlinfixture.app

        fun diagnosticProbeTypeMismatch(): Int {
          val value: Int = "text"
          return value
        }

        fun diagnosticProbeUnreachable() {
          return
          println("dead")
        }

        fun diagnosticProbeUnresolved() {
          missingDiagnosticSymbol()
        }

        fun diagnosticProbeUselessCast(): Any {
          val value = 1
          return value as Int
        }

        fun diagnosticProbeValReassignment() {
          val value = 1
          value = 2
        }
        """
            .trimIndent() + "\n"

    try {
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      withWorkspace {
        try {
          openDocument(file, content, version = 1, revision = 1L)

          withRuntime(workspace) { runtime ->
            runAnalysis { runtime.onFileOpened(file) }
            val snapshot = requireNotNull(FileManager.getActiveDocumentSnapshot(file))
            val result = requireNotNull(runAnalysis { runtime.analyze(file, snapshot, ICancelChecker.NOOP) })
            val byCode = result.diagnostics.groupBy { it.code }
            val summary =
                result.diagnostics.joinToString { "${it.code}@${it.range.start.line}" }

            val initializerMismatch =
                requireNotNull(byCode["INITIALIZER_TYPE_MISMATCH"]?.firstOrNull()) {
                  "diagnostics=$summary"
                }
            assertTrue(initializerMismatch.message, initializerMismatch.message.isNotEmpty())
            assertEquals("kotlin", initializerMismatch.source)
            val expectedLine = LineIndex.from(content).indexToPosition(content.indexOf("\"text\"")).line
            assertEquals(expectedLine, initializerMismatch.range.start.line)

            val unreachable = requireNotNull(byCode["UNREACHABLE_CODE"]?.firstOrNull())
            assertTrue(unreachable.message, unreachable.message.isNotEmpty())

            val unresolved = requireNotNull(byCode["UNRESOLVED_REFERENCE"]?.firstOrNull())
            assertTrue(unresolved.message, unresolved.message.contains("missingDiagnosticSymbol"))

            val uselessCast = requireNotNull(byCode["USELESS_CAST"]?.firstOrNull())
            assertTrue(uselessCast.message, uselessCast.message.isNotEmpty())

            val reassignment =
                requireNotNull(byCode["VAL_REASSIGNMENT"]?.firstOrNull()) {
                  "diagnostics=$summary"
                }
            assertFalse(reassignment.message, reassignment.message.contains("''"))
          }
        } finally {
          closeDocument(file)
        }
      }
    } finally {
      Files.deleteIfExists(file)
    }
  }
}