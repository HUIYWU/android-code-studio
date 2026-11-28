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
import org.jetbrains.kotlin.com.intellij.codeInsight.daemon.QuickFixBundle
import org.jetbrains.kotlin.com.intellij.java.JavaBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiSortAndComparatorInspectionCapabilityTest {
  @Test
  fun publishesOfficialListSortAndSimplifiableComparatorWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiSortAndComparatorInspection.java",
      )
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiSortAndComparatorInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import java.util.Collections;
          import java.util.Comparator;
          import java.util.List;
          import java.util.Map;

          class JavaPsiSortAndComparatorInspection {
            void sortLists(List<String> values, Comparator<String> order) {
              Collections.sort(values, order);
              Collections.sort(values, null);
              Collections.sort(values);
              values.sort(order);
            }

            Comparator<Map.Entry<String, Integer>> byKey() {
              return Comparator.<Map.Entry<String, Integer>, String>comparing(Map.Entry::getKey);
            }

            Comparator<Map.Entry<String, Integer>> byKeyWithParameterizedQualifier() {
              return Comparator.comparing(Map.Entry<String, Integer>::getKey);
            }

            Comparator<String> thenInteger(Comparator<String> order) {
              return order.thenComparing(Comparator.comparingInt(String::length));
            }

            Comparator<String> thenFunction(Comparator<String> order) {
              return order.thenComparing(Comparator.comparing(String::length));
            }

            static int lengthKey(String value) { return value.length(); }

            static int lengthKey(String left, String right) { return Integer.compare(left.length(), right.length()); }

            Comparator<String> ambiguousThenComparing(Comparator<String> order) {
              return order.thenComparing(Comparator.comparing(JavaPsiSortAndComparatorInspection::lengthKey));
            }

            String reversedMaximum(List<String> values, Comparator<String> order) {
              return Collections.max(values, order.reversed());
            }

            Comparator<String> byLength() { return Comparator.comparing(String::length); }

            String plainMaximum(List<String> values, Comparator<String> order) {
              return Collections.max(values, order);
            }

            @SuppressWarnings("Java8ListSort")
            void suppressedSort(List<String> values, Comparator<String> order) { Collections.sort(values, order); }

            @SuppressWarnings("RedundantComparatorComparing")
            String suppressedReverse(List<String> values, Comparator<String> order) {
              return Collections.max(values, order.reversed());
            }
          }
      """.trimIndent()
      val listSort = Warning(
          "Java8ListSort", QuickFixBundle.message("java.8.list.sort.inspection.description"), "Collections.", "sort", "(values, order)",
      )
      val naturalSort = listSort.copy(suffix = "(values, null)")
      val entryComparator = Warning(
          "RedundantComparatorComparing",
          JavaBundle.message("inspection.simplifiable.comparator.entry.comparator.message", "Entry.comparingByKey()"),
          "return Comparator.<Map.Entry<String, Integer>, String>", "comparing", "(Map.Entry::getKey)",
      )
      val integerComparator = Warning(
          "RedundantComparatorComparing", JavaBundle.message("inspection.simplifiable.comparator.comparing.message", "comparingInt"),
          "thenComparing(", "Comparator.comparingInt", "(String::length)",
      )
      val functionComparator = integerComparator.copy(
          message = JavaBundle.message("inspection.simplifiable.comparator.comparing.message", "comparing"),
          text = "Comparator.comparing",
      )
      val reversedMaximum = Warning(
          "RedundantComparatorComparing", JavaBundle.message("inspection.simplifiable.comparator.reversed.message", "max", "min"),
          "Collections.", "max", "(values, order.reversed())",
      )
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 2801L)
        withRuntime(workspace) { runtime ->
          fun diagnostics(version: Int, revision: Long) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          runAnalysis { runtime.onFileOpened(path) }
          assertWarnings(
              content, diagnostics(1, 2801L), listSort, naturalSort, entryComparator, integerComparator, functionComparator, reversedMaximum,
          )

          val edited = content.replaceFirst("Collections.sort(values, order)", "values.sort(order)")
              .replace("thenComparing(Comparator.comparingInt(String::length))", "thenComparingInt(String::length)")
          openDocument(path, edited, version = 2, revision = 2802L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(edited, diagnostics(2, 2802L), naturalSort, entryComparator, functionComparator, reversedMaximum)
          assertEquals(disk, Files.readAllBytes(path).decodeToString())
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    assertEquals(emptyList<DiagnosticItem>(), diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    val warnings = diagnostics.filter { it.code == "Java8ListSort" || it.code == "RedundantComparatorComparing" }
    val index = LineIndex.from(content)
    val items = expected.map { (code, message, prefix, text, suffix) ->
      val context = prefix + text + suffix
      val match = content.indexOf(context)
      check(match >= 0) { "Missing $context in fixture" }
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

  private data class Warning(val code: String, val message: String, val prefix: String, val text: String, val suffix: String)
}