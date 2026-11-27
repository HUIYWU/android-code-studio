package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import org.jetbrains.kotlin.com.intellij.java.analysis.JavaAnalysisBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiDuplicateThrowsCapabilityTest {
  @Test
  fun publishesOfficialDuplicateThrowsWarningsWithRangesAndSuppression() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiDuplicateThrows.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiDuplicateThrows {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          class JavaPsiDuplicateThrows {
            void duplicate() throws IllegalStateException, IllegalStateException {}
            void covered() throws IllegalArgumentException, RuntimeException {}
            void coveredInReverse() throws RuntimeException, IllegalArgumentException {}
            void unrelated() throws IllegalStateException, IllegalArgumentException {}

            /**
             * @throws IllegalArgumentException for an invalid argument
             * @throws RuntimeException for any other failure
             */
            void documented() throws IllegalArgumentException, RuntimeException {}

            @SuppressWarnings("DuplicateThrows")
            void suppressed() throws IllegalStateException, IllegalStateException {}
          }
      """.trimIndent()
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 1701L)
        withRuntime(workspace) { runtime ->
          val diagnostics = runAnalysis {
            runtime.onFileOpened(path)
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, 1, 1701L)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }
          val warnings = diagnostics.filter { it.code == "DuplicateThrows" }
          val index = LineIndex.from(content)
          val expectedRanges = listOf(
              "duplicate() throws " to "IllegalStateException",
              "covered() throws " to "IllegalArgumentException",
              "coveredInReverse() throws RuntimeException, " to "IllegalArgumentException",
          ).map { (prefix, name) ->
            val start = content.indexOf(prefix) + prefix.length
            Range(index.indexToPosition(start), index.indexToPosition(start + name.length))
          }
          assertEquals(expectedRanges, warnings.map { it.range })
          assertTrue(warnings.all {
            it.source == "java-inspection" && it.severity == DiagnosticSeverity.WARNING && it.extra.javaClass == Any::class.java
          })
          val coveredMessage = JavaAnalysisBundle.message(
              "inspection.duplicate.throws.more.general.problem", "java.lang.RuntimeException",
          )
          assertEquals(listOf(
              JavaAnalysisBundle.message("inspection.duplicate.throws.problem"),
              coveredMessage,
              coveredMessage,
          ), warnings.map { it.message })
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }
}
