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
import org.jetbrains.kotlin.com.intellij.codeInsight.daemon.JavaErrorBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiDeprecationCapabilityTest {
  @Test
  fun publishesOfficialDeprecationAndRemovalDiagnosticsAcrossEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiDeprecation.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiDeprecation {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          class JavaPsiDeprecation {
            void calls() {
              JavaPsiLegacyApi.oldApi();
              JavaPsiLegacyApi.docOnly();
              JavaPsiLegacyApi.removedSoon();
              JavaPsiLegacyApi.currentApi();
            }

            Runnable reference() { return JavaPsiLegacyApi::oldApi; }
            JavaPsiLegacyApi construct() { return new JavaPsiLegacyApi(); }

            @SuppressWarnings("deprecation")
            void suppressDeprecation() { JavaPsiLegacyApi.oldApi(); JavaPsiLegacyApi.removedSoon(); }

            @SuppressWarnings("removal")
            void suppressRemoval() { JavaPsiLegacyApi.removedSoon(); }
          }

          class JavaPsiLegacyApi {
            @Deprecated
            static void oldApi() {}

            /** @deprecated Use the current API. */
            static void docOnly() {}

            @Deprecated(since = "9", forRemoval = true)
            static void removedSoon() {}

            static void currentApi() {}
            static void sameTopLevel() { oldApi(); removedSoon(); }
          }
      """.trimIndent()
      val old = Usage("JavaPsiLegacyApi.", "oldApi")
      val doc = Usage("JavaPsiLegacyApi.", "docOnly")
      val removal = Usage("JavaPsiLegacyApi.", "removedSoon", forRemoval = true, since = "9")
      val reference = old.copy(prefix = "JavaPsiLegacyApi::")
      val suppressDeprecation = removal.copy(prefix = "oldApi(); JavaPsiLegacyApi.")
      val suppressRemoval = removal.copy(prefix = "void suppressRemoval() { JavaPsiLegacyApi.", forRemoval = false)
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 1901L)
        withRuntime(workspace) { runtime ->
          fun diagnostics(version: Int, revision: Long) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          runAnalysis { runtime.onFileOpened(path) }
          assertDiagnostics(content, diagnostics(1, 1901L), old, doc, removal, reference, suppressDeprecation)

          val edited = content.replace(", forRemoval = true", "")
          openDocument(path, edited, version = 2, revision = 1902L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertDiagnostics(edited, diagnostics(2, 1902L), old, doc, removal.copy(forRemoval = false), reference, suppressRemoval)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertDiagnostics(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Usage) {
    val actual = diagnostics.filter { it.code == "Deprecation" || it.code == "MarkedForRemoval" }
    val index = LineIndex.from(content)
    val items = expected.map { (prefix, name, forRemoval, since) ->
      val match = content.indexOf(prefix + name)
      check(match >= 0) { "Missing $prefix$name in fixture" }
      val start = match + prefix.length
      val symbol = "$name()"
      val message = when {
        since == null -> JavaErrorBundle.message("deprecated.symbol", symbol)
        forRemoval -> JavaErrorBundle.message("marked.for.removal.symbol.since", symbol, since)
        else -> JavaErrorBundle.message("deprecated.since.symbol", symbol, since)
      }
      DiagnosticItem(
          message = message,
          code = if (forRemoval) "MarkedForRemoval" else "Deprecation",
          range = Range(index.indexToPosition(start), index.indexToPosition(start + name.length)),
          source = "java-inspection",
          severity = if (forRemoval) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
      )
    }
    assertEquals(items, actual)
    assertEquals(items.filter { it.severity == DiagnosticSeverity.ERROR }, diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    assertTrue(actual.all { it.extra.javaClass == Any::class.java })
  }

  private data class Usage(val prefix: String, val name: String, val forRemoval: Boolean = false, val since: String? = null)
}
