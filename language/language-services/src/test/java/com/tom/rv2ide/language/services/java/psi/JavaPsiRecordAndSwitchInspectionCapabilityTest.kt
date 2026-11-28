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
class JavaPsiRecordAndSwitchInspectionCapabilityTest {
  @Test
  fun publishesOfficialRecordConstructorAndSwitchBlockWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiRecordAndSwitchInspection.java",
      )
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiRecordAndSwitchInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          class JavaPsiRecordAndSwitchInspection {
            record EmptyCompact(int value) {
              EmptyCompact {}
            }

            record Canonical(int value) {
              Canonical(int value) { this.value = value; }
            }

            record Validated(int value) {
              Validated(int value) {
                if (value < 0) throw new IllegalArgumentException();
                this.value = value;
              }
            }

            record ValidatedCompact(int value) {
              ValidatedCompact { if (value < 0) throw new IllegalArgumentException(); }
            }

            record Delegating(int value) {
              Delegating() { this(0); }
            }

            @SuppressWarnings("RedundantRecordConstructor")
            record Suppressed(int value) {
              Suppressed(int value) { this.value = value; }
            }

            int select(int key) {
              return switch (key) {
                case 0 -> { yield 1; }
                case 1 -> { int adjusted = key + 1; yield adjusted; }
                default -> 0;
              };
            }

            void invoke(int key) {
              switch (key) {
                case 2 -> { mark(); }
                case 3 -> { throw new IllegalStateException(); }
                default -> mark();
              }
            }

            @SuppressWarnings("RedundantLabeledSwitchRuleCodeBlock")
            int suppressedSwitch(int key) {
              return switch (key) { default -> { yield 0; } };
            }

            private static void mark() {}
          }
      """.trimIndent()
      val emptyCompact = Warning(
          "RedundantRecordConstructor", "inspection.redundant.record.constructor.compact.message", "\n    ", "EmptyCompact",
      )
      val canonical = emptyCompact.copy(key = "inspection.redundant.record.constructor.canonical.message", text = "Canonical")
      val validated = emptyCompact.copy(
          key = "inspection.redundant.record.constructor.can.be.compact.message", prefix = "\n    Validated", text = "(int value)",
      )
      val redundantYield = Warning(
          "RedundantLabeledSwitchRuleCodeBlock", "inspection.labeled.switch.rule.redundant.code.block.message", "case 0 -> { ", "yield",
      )
      val callOpen = redundantYield.copy(prefix = "case 2 -> ", text = "{")
      val callClose = redundantYield.copy(prefix = "case 2 -> { mark(); ", text = "}")
      val throwOpen = redundantYield.copy(prefix = "case 3 -> ", text = "{")
      val throwClose = redundantYield.copy(prefix = "case 3 -> { throw new IllegalStateException(); ", text = "}")
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 2501L)
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
              content, diagnostics(1, 2501L),
              emptyCompact, canonical, validated, redundantYield, callOpen, callClose, throwOpen, throwClose,
          )

          val edited = content.replace(
              "EmptyCompact {}", "EmptyCompact { if (value < 0) throw new IllegalArgumentException(); }",
          ).replace("case 0 -> { yield 1; }", "case 0 -> 1;")
          openDocument(path, edited, version = 2, revision = 2502L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(edited, diagnostics(2, 2502L), canonical, validated, callOpen, callClose, throwOpen, throwClose)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    assertEquals(emptyList<DiagnosticItem>(), diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    val warnings = diagnostics.filter { it.code == "RedundantRecordConstructor" || it.code == "RedundantLabeledSwitchRuleCodeBlock" }
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
