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
import org.jetbrains.kotlin.com.intellij.java.analysis.JavaAnalysisBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiCallInspectionCapabilityTest {
  @Test
  fun publishesOfficialComparatorAndClassGetClassWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiCallInspection.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiCallInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import java.util.Comparator;
          import java.util.function.BinaryOperator;

          class JavaPsiCallInspection {
            Comparator<Integer> badMin = Integer::min;
            Comparator<Integer> badMax = Math::max;
            Comparator<Integer> compare = Integer::compare;
            BinaryOperator<Integer> minOperator = Integer::min;

            @SuppressWarnings("InvalidComparatorMethodReference")
            Comparator<Integer> suppressedComparator = Integer::max;

            Class<?> classObjectType(Class<?> classValue) { return classValue.getClass(); }
            Class<?> classLiteralType() { return String.class.getClass(); }
            Class<?> ordinaryObjectType(Object value) { return value.getClass(); }
            void nullCheck(Class<?> type) { type.getClass(); }

            @SuppressWarnings("ClassGetClass")
            Class<?> suppressedLookup(Class<?> type) { return type.getClass(); }
          }
      """.trimIndent()
      val min = Warning("InvalidComparatorMethodReference", "badMin = ", "Integer::min")
      val max = Warning("InvalidComparatorMethodReference", "badMax = ", "Math::max")
      val classObject = Warning("ClassGetClass", "return classValue.", "getClass")
      val classLiteral = Warning("ClassGetClass", "return String.class.", "getClass")
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 1801L)
        withRuntime(workspace) { runtime ->
          fun diagnostics(version: Int, revision: Long) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          runAnalysis { runtime.onFileOpened(path) }
          val initial = diagnostics(1, 1801L)
          assertTrue(initial.toString(), initial.none { it.severity == DiagnosticSeverity.ERROR })
          assertWarnings(content, initial, min, max, classObject, classLiteral)

          val editing = content.replace("badMin = Integer::min;", "badMin = Integer::;")
              .replace("return classValue.getClass();", "return classValue.getCla();")
          openDocument(path, editing, version = 2, revision = 1802L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(editing, diagnostics(2, 1802L), max, classLiteral)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    val warnings = diagnostics.filter { it.code == "InvalidComparatorMethodReference" || it.code == "ClassGetClass" }
    val index = LineIndex.from(content)
    val items = expected.map { (code, prefix, text) ->
      val match = content.indexOf(prefix + text)
      check(match >= 0) { "Missing $prefix$text in fixture" }
      val start = match + prefix.length
      val key = when (code) {
        "InvalidComparatorMethodReference" -> "method.reference.mapped.to.comparator"
        "ClassGetClass" -> "inspection.class.getclass.message"
        else -> error("Unexpected inspection code: $code")
      }
      DiagnosticItem(
          message = JavaAnalysisBundle.message(key),
          code = code,
          range = Range(index.indexToPosition(start), index.indexToPosition(start + text.length)),
          source = "java-inspection",
          severity = DiagnosticSeverity.WARNING,
      )
    }
    assertEquals(items, warnings)
    assertTrue(warnings.all { it.extra.javaClass == Any::class.java })
  }

  private data class Warning(val code: String, val prefix: String, val text: String)
}
