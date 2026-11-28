package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import org.jetbrains.kotlin.com.intellij.java.JavaBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiCleanerAndTextBlockInspectionCapabilityTest {
  @Test
  fun publishesOfficialCleanerAndTextBlockWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiCleanerAndTextBlockInspection.java",
      )
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiCleanerAndTextBlockInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val delimiter = "\"\"\""
      val spaces = "  "
      val tab = "\t"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import java.lang.ref.Cleaner;

          class JavaPsiCleanerAndTextBlockInspection {
            private void release() {}
            private static void releaseShared() {}
            private static void consume(Object object) {}

            void capturesThis(Cleaner cleaner) {
              cleaner.register(this, this::release);
            }

            void capturesParameter(Cleaner cleaner, Object referent) {
              cleaner.register(referent, () -> consume(referent));
            }

            void capturesDifferentObject(Cleaner cleaner, Object tracked, Object other) {
              cleaner.register(tracked, () -> consume(other));
            }

            @SuppressWarnings("CapturingCleaner")
            void suppressedCleaner(Cleaner cleaner) { cleaner.register(this, () -> release()); }

            String trailingWhitespace() {
              return $delimiter
                  first line$spaces
                  second line$tab
                  $delimiter;
            }

            String escapedWhitespace() {
              return $delimiter
                  escaped\s
                  $delimiter;
            }

            @SuppressWarnings("TrailingWhitespacesInTextBlock")
            String suppressedWhitespace() {
              return $delimiter
                  suppressed line$spaces
                  $delimiter;
            }
          }
      """.trimIndent()
      val captureThis = Warning(
          "CapturingCleaner", JavaBundle.message("inspection.capturing.cleaner", "this"), "cleaner.register(this, ", "this",
      )
      val captureParameter = Warning(
          "CapturingCleaner", JavaBundle.message("inspection.capturing.cleaner", "referent"), "() -> consume(", "referent",
      )
      val firstTrailing = Warning(
          "TrailingWhitespacesInTextBlock", JavaBundle.message("inspection.trailing.whitespaces.in.text.block.message"),
          "first line", spaces,
      )
      val secondTrailing = firstTrailing.copy(prefix = "second line", text = tab)
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 2401L)
        withRuntime(workspace) { runtime ->
          fun diagnostics(version: Int, revision: Long) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          runAnalysis { runtime.onFileOpened(path) }
          assertWarnings(content, diagnostics(1, 2401L), captureThis, captureParameter, firstTrailing)

          val edited = content.replace("this::release", "JavaPsiCleanerAndTextBlockInspection::releaseShared")
              .replace("first line$spaces\n", "first line\n")
          openDocument(path, edited, version = 2, revision = 2402L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(edited, diagnostics(2, 2402L), captureParameter, secondTrailing)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    assertEquals(emptyList<DiagnosticItem>(), diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    val warnings = diagnostics.filter { it.code == "CapturingCleaner" || it.code == "TrailingWhitespacesInTextBlock" }
    val index = LineIndex.from(content)
    val items = expected.map { (code, message, prefix, text) ->
      val match = content.indexOf(prefix + text)
      check(match >= 0) { "Missing $prefix$text in fixture" }
      val start = match + prefix.length
      DiagnosticItem(
          message = message,
          code = code,
          range = Range(index.indexToPosition(start), index.indexToPosition(start + text.length)),
          source = "java-inspection",
          severity = DiagnosticSeverity.WARNING,
      )
    }
    assertEquals(items, warnings)
    assertTrue(warnings.all { it.extra.javaClass == Any::class.java })
  }

  private data class Warning(val code: String, val message: String, val prefix: String, val text: String)
}