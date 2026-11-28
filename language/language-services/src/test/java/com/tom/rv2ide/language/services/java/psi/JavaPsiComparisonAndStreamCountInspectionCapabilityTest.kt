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
class JavaPsiComparisonAndStreamCountInspectionCapabilityTest {
  @Test
  fun publishesOfficialComparisonAndMappingBeforeCountWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiComparisonAndStreamCountInspection.java",
      )
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiComparisonAndStreamCountInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import java.util.Comparator;
          import java.util.function.IntConsumer;
          import java.util.stream.IntStream;
          import java.util.stream.Stream;

          class JavaPsiComparisonAndStreamCountInspection {
            boolean fixedResult(Comparator<String> ordering, String first, String second) {
              return ordering.compare(first, second) == 1;
            }

            boolean storedResult(String first, String second) {
              int result = first.compareTo(second);
              return -1 == result;
            }

            boolean overwrittenResult(Comparator<String> order, String first, String second, int replacement) {
              int result = order.compare(first, second);
              result = replacement;
              return result == 1;
            }

            boolean relativeResult(Comparator<String> order, String first, String second) {
              return order.compare(first, second) > 0 || first.compareTo(second) == 0;
            }

            boolean unrelatedCompare(int a, int b) { return Integer.compare(a, b) == 1; }

            @SuppressWarnings("ComparatorResultComparison")
            boolean suppressedCompare(String first, String second) { return first.compareTo(second) == 1; }

            long mapBeforeCount(Stream<String> mappedStream) {
              return mappedStream.map(String::trim).count();
            }

            long peekBeforeCount(IntStream observedStream, IntConsumer observer) {
              return observedStream.peek(observer).count();
            }

            long filterBeforeCount(Stream<String> source) {
              return source.filter(value -> !value.isEmpty()).count();
            }

            long nonAdjacentMap(Stream<String> source) {
              return source.map(String::trim).limit(1).count();
            }

            @SuppressWarnings("MappingBeforeCount")
            long suppressedMap(Stream<String> source) { return source.map(String::trim).count(); }
          }
      """.trimIndent()
      val comparison = Warning(
          "ComparatorResultComparison", JavaBundle.message("inspection.comparator.result.comparison.problem.display.name"),
          "ordering.compare(first, second) ", "==",
      )
      val storedResult = comparison.copy(prefix = "return -1 ")
      val mapping = Warning(
          "MappingBeforeCount", JavaBundle.message("inspection.mapping.before.count.message", "map"), "return mappedStream.", "map",
      )
      val peek = Warning(
          "MappingBeforeCount", JavaBundle.message("inspection.mapping.before.count.message", "peek"), "return observedStream.", "peek",
      )
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 2301L)
        withRuntime(workspace) { runtime ->
          fun diagnostics(version: Int, revision: Long) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          runAnalysis { runtime.onFileOpened(path) }
          assertWarnings(content, diagnostics(1, 2301L), comparison, storedResult, mapping, peek)

          val edited = content.replace("ordering.compare(first, second) == 1", "ordering.compare(first, second) > 0")
              .replace("mappedStream.map(String::trim).count()", "mappedStream.map(String::trim).limit(1).count()")
          openDocument(path, edited, version = 2, revision = 2302L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(edited, diagnostics(2, 2302L), storedResult, peek)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    assertEquals(emptyList<DiagnosticItem>(), diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    val warnings = diagnostics.filter { it.code == "ComparatorResultComparison" || it.code == "MappingBeforeCount" }
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
