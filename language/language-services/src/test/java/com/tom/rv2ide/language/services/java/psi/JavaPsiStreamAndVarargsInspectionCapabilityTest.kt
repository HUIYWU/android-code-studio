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
class JavaPsiStreamAndVarargsInspectionCapabilityTest {
  @Test
  fun publishesOfficialEndlessStreamAndMixedVarargsWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiStreamAndVarargsInspection.java",
      )
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiStreamAndVarargsInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import java.util.stream.Stream;

          class JavaPsiStreamAndVarargsInspection {
            long endless() { return Stream.generate(() -> "value").map(String::trim).count(); }
            long limited() { return Stream.generate(() -> "value").limit(1).count(); }
            String first() { return Stream.generate(() -> "value").findFirst().orElse(""); }
            long finite() { return Stream.of("value").count(); }

            @SuppressWarnings("EndlessStream")
            long suppressedStream() { return Stream.generate(() -> "value").count(); }

            static void sink(Object... args) {}
            static void sinkOne(Object arg) {}

            void mixed(boolean flag, Object[] items, Object[] other, Object scalar, int[] primitive) {
              sink(flag ? items : scalar);
              sink(flag ? scalar : primitive);
              sink(flag ? items : other);
              sink(flag ? items : null);
              sink(scalar, flag ? items : scalar);
              sinkOne(flag ? items : scalar);
            }

            @SuppressWarnings("SuspiciousTernaryOperatorInVarargsCall")
            void suppressedVarargs(boolean flag, Object[] values, Object value) {
              sink(flag ? values : value);
            }
          }
      """.trimIndent()
      val endless = Warning("EndlessStream", "inspection.endless.stream.description", ".map(String::trim).", "count")
      val mixed = Warning(
          "SuspiciousTernaryOperatorInVarargsCall", "inspection.suspicious.ternary.in.varargs.description",
          "sink(flag ? items : ", "scalar",
      )
      val primitive = mixed.copy(prefix = "sink(flag ? ")
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 2001L)
        withRuntime(workspace) { runtime ->
          fun diagnostics(version: Int, revision: Long) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          runAnalysis { runtime.onFileOpened(path) }
          assertWarnings(content, diagnostics(1, 2001L), endless, mixed, primitive)

          val edited = content.replace(".map(String::trim).count()", ".map(String::trim).limit(1).count()")
              .replace("sink(flag ? items : scalar);", "sink(flag ? items : other);")
          openDocument(path, edited, version = 2, revision = 2002L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(edited, diagnostics(2, 2002L), primitive)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    assertEquals(emptyList<DiagnosticItem>(), diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    val warnings = diagnostics.filter { it.code == "EndlessStream" || it.code == "SuspiciousTernaryOperatorInVarargsCall" }
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
