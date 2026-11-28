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
class JavaPsiLoopAndResourceInspectionCapabilityTest {
  @Test
  fun publishesOfficialListRemoveAndExplicitCloseWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiLoopAndResourceInspection.java",
      )
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiLoopAndResourceInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import java.io.Closeable;
          import java.io.IOException;
          import java.util.List;

          class JavaPsiLoopAndResourceInspection {
            void removeForward(List<Integer> forward) {
              for (int i = 0; i < forward.size(); i++) { forward.remove(i); }
            }

            void removeBackward(List<Integer> backward) {
              for (int i = backward.size() - 1; i >= 0; i--) { backward.remove(i); }
            }

            void removeOnce(List<Integer> once) {
              for (int i = 0; i < once.size(); i++) { once.remove(i); break; }
            }

            void removeValue(List<Integer> values) {
              for (int i = 0; i < values.size(); i++) { values.remove(Integer.valueOf(i)); }
            }

            @SuppressWarnings("SuspiciousListRemoveInLoop")
            void removeSuppressed(List<Integer> items) {
              for (int i = 0; i < items.size(); i++) { items.remove(i); }
            }

            static void touch() {}

            void closeAtEnd(Closeable input) throws IOException {
              try (Closeable terminal = input) { terminal.close(); }
            }

            void closeInBranch(Closeable input, boolean flag) throws IOException {
              try (Closeable branch = input) { if (flag) { branch.close(); } }
            }

            void closeBeforeOtherWork(Closeable input) throws IOException {
              try (Closeable early = input) { early.close(); touch(); }
            }

            void closeAnotherResource(Closeable input, Closeable other) throws IOException {
              try (Closeable managed = input) { other.close(); }
            }

            @SuppressWarnings("RedundantExplicitClose")
            void closeSuppressed(Closeable input) throws IOException {
              try (Closeable ignored = input) { ignored.close(); }
            }
          }
      """.trimIndent()
      val forwardRemove = Warning(
          "SuspiciousListRemoveInLoop", "inspection.suspicious.list.remove.display.name", "forward.", "remove",
      )
      val terminalClose = Warning(
          "RedundantExplicitClose", "inspection.redundant.explicit.close", "", "terminal.close();",
      )
      val branchClose = terminalClose.copy(text = "branch.close();")
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 2101L)
        withRuntime(workspace) { runtime ->
          fun diagnostics(version: Int, revision: Long) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          runAnalysis { runtime.onFileOpened(path) }
          assertWarnings(content, diagnostics(1, 2101L), forwardRemove, terminalClose, branchClose)

          val edited = content.replace(
              "int i = 0; i < forward.size(); i++", "int i = forward.size() - 1; i >= 0; i--",
          ).replace("terminal.close();", "touch();")
          openDocument(path, edited, version = 2, revision = 2102L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(edited, diagnostics(2, 2102L), branchClose)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    assertEquals(emptyList<DiagnosticItem>(), diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    val warnings = diagnostics.filter { it.code == "SuspiciousListRemoveInLoop" || it.code == "RedundantExplicitClose" }
    val index = LineIndex.from(content)
    val items = expected.map { (code, key, prefix, text) ->
      val match = content.indexOf(prefix + text)
      check(match >= 0) { "Missing $prefix$text in fixture" }
      val start = match + prefix.length
      DiagnosticItem(
          message = JavaBundle.message(key),
          code = code,
          range = Range(index.indexToPosition(start), index.indexToPosition(start + text.length)),
          source = "java-inspection",
          severity = DiagnosticSeverity.WARNING,
      )
    }
    assertEquals(items, warnings)
    assertTrue(warnings.all { it.extra.javaClass == Any::class.java })
  }

  private data class Warning(val code: String, val key: String, val prefix: String, val text: String)
}