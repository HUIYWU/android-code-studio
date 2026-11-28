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
class JavaPsiAssignmentInspectionCapabilityTest {
  @Test
  fun publishesOfficialSelfAssignmentAndOverwrittenKeyWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiAssignmentInspection.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiAssignmentInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import java.util.EnumSet;
          import java.util.Map;

          class JavaPsiAssignmentInspection {
            private int field;
            enum Option { FIRST, SECOND }

            void assign(int value, int replacement, int[] data, int index) {
              value = value;
              data[index] = data[index];
              data[index++] = data[index++];
              value = (byte) value;
            }

            void assignField(int field) { this.field = field; }

            @SuppressWarnings("SillyAssignment")
            void suppressedAssignment(int ignored) { ignored = ignored; }

            void overwrite(Map<String, Integer> entries, int[] slots) {
              entries.put("repeat", 1);
              entries.put("repeat", 2);
              slots[0] = 1;
              slots[0] = 2;
            }

            void overwriteStableKey(Map<String, Integer> entries, String input) {
              String stableKey = input;
              entries.put(stableKey, 3);
              entries.put(stableKey, 4);
            }

            EnumSet<Option> duplicateElements() { return EnumSet.of(Option.FIRST, Option.FIRST); }
            EnumSet<Option> distinctElements() { return EnumSet.of(Option.FIRST, Option.SECOND); }

            void readBetweenWrites(Map<String, Integer> entries) {
              entries.put("seen", 1);
              Integer seen = entries.get("seen");
              entries.put("seen", seen);
            }

            @SuppressWarnings("OverwrittenKey")
            void suppressedKey(Map<String, Integer> entries) {
              entries.put("muted", 1);
              entries.put("muted", 2);
            }
          }
      """.trimIndent()
      val self = Warning(
          "SillyAssignment", JavaBundle.message("assignment.to.itself.problem.descriptor", "value"), "value = ", "value",
      )
      val element = Warning(
          "SillyAssignment", JavaBundle.message("assignment.array.element.to.itself.problem.descriptor"),
          "data[index] = ", "data[index]",
      )
      val duplicateKey = Warning(
          "OverwrittenKey", JavaBundle.message("inspection.overwritten.key.map.message"), "entries.put(", "\"repeat\"", ", 1);",
      )
      val overwrittenIndex = Warning(
          "OverwrittenKey", JavaBundle.message("inspection.overwritten.key.array.message"), "slots[", "0", "] = 1;",
      )
      val stableKey = duplicateKey.copy(text = "stableKey", suffix = ", 3);")
      val duplicateElement = Warning(
          "OverwrittenKey", JavaBundle.message("inspection.overwritten.key.set.message"),
          "EnumSet.of(", "Option.FIRST", ", Option.FIRST)",
      )
      val unchanged = arrayOf(
          stableKey, stableKey.copy(suffix = ", 4);"),
          duplicateElement, duplicateElement.copy(prefix = "Option.FIRST, ", suffix = ");"),
      )
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 2201L)
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
              content, diagnostics(1, 2201L), self, element,
              duplicateKey, duplicateKey.copy(suffix = ", 2);"),
              overwrittenIndex, overwrittenIndex.copy(suffix = "] = 2;"), *unchanged,
          )

          val edited = content.replace("value = value;", "value = replacement;")
              .replace("entries.put(\"repeat\", 2);", "entries.put(\"different\", 2);")
              .replace("slots[0] = 2;", "slots[1] = 2;")
          openDocument(path, edited, version = 2, revision = 2202L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(edited, diagnostics(2, 2202L), element, *unchanged)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    assertEquals(emptyList<DiagnosticItem>(), diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    val warnings = diagnostics.filter { it.code == "SillyAssignment" || it.code == "OverwrittenKey" }
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

  private data class Warning(
      val code: String,
      val message: String,
      val prefix: String,
      val text: String,
      val suffix: String = "",
  )
}
