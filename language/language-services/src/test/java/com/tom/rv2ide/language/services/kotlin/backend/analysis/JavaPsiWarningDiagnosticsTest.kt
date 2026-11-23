package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiWarningDiagnosticsTest {

  @Test
  fun officialLocalInspectionsPublishWarningsAndHonorSuppressionAfterEdit() {
    val dir =
        KotlinAnalysisTestSupport.fixturePath(
            "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app",
        )
    Files.createDirectories(dir)
    val file = Files.createTempFile(dir, "JavaInspectionDiagnostic", ".java")
    val content =
        """
        package com.tom.rv2ide.testing.kotlinfixture.app;

        class StaticAccessTarget { static int VALUE = 1; }

        class WarningAndError {
          int warning() { return new StaticAccessTarget().VALUE; }
          int error() { return new StaticAccessTarget().VALUE + missingJavaSymbol(); }
          int overflow() { return 1000000000 * 3; }
          boolean suspiciousArrays() {
            return java.util.Arrays.equals(new String[0], new Integer[0]);
          }
        }

        class SafeVarargsProblems {
          @SafeVarargs
          public final <T> void reassigned(T[] values, T... items) {
            items = values;
          }

          @SafeVarargs
          public final void reifiable(String... values) {
          }
        }

        @SuppressWarnings("static-access")
        class SuppressedWarning {
          int use() { return new StaticAccessTarget().VALUE; }
        }
        """
            .trimIndent() + "\n"

    try {
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      KotlinAnalysisTestSupport.withWorkspace {
        openDocument(file, content, version = 1, revision = 1L)
        try {
          KotlinAnalysisTestSupport.withRuntime(workspace) { runtime ->
            KotlinAnalysisTestSupport.runAnalysis { runtime.onFileOpened(file) }
            val initial = sharedDiagnostics(file, 1, 1L)
            val staticAccessWarnings = initial.filter { it.code == "AccessStaticViaInstance" }
            assertEquals(2, staticAccessWarnings.size)
            assertTrue(staticAccessWarnings.all { it.severity == DiagnosticSeverity.WARNING })
            val overflow = initial.single { it.code == "NumericOverflow" }
            assertEquals(DiagnosticSeverity.WARNING, overflow.severity)
            assertEquals("java-inspection", overflow.source)
            val suspiciousArray = initial.single { it.code == "SuspiciousArrayMethodCall" }
            assertEquals(DiagnosticSeverity.WARNING, suspiciousArray.severity)
            assertEquals("java-inspection", suspiciousArray.source)
            val safeVarargsAssignment = initial.single { it.code == "SafeVarargsHasNoEffect" }
            assertEquals(DiagnosticSeverity.WARNING, safeVarargsAssignment.severity)
            assertEquals("java-inspection", safeVarargsAssignment.source)
            val safeVarargsReifiable = initial.single { it.code == "SafeVarargsOnNonReifiableType" }
            assertEquals(DiagnosticSeverity.WARNING, safeVarargsReifiable.severity)
            assertEquals("java-inspection", safeVarargsReifiable.source)
            assertTrue(initial.filter { it.source == "java-inspection" }.all { it.message.isNotEmpty() })
            assertTrue(initial.any { it.severity == DiagnosticSeverity.ERROR })

            val edited = content.replace("@SuppressWarnings(\"static-access\")", "")
            changeDocument(file, content, edited, version = 2, revision = 2L)
            KotlinAnalysisTestSupport.runAnalysis { runtime.onFileContentChanged(file) }
            val afterEdit = sharedDiagnostics(file, 2, 2L)
            assertEquals(3, afterEdit.count { it.code == "AccessStaticViaInstance" })
            assertTrue(afterEdit.any { it.severity == DiagnosticSeverity.ERROR })
          }
        } finally {
          closeDocument(file)
        }
      }
    } finally {
      Files.deleteIfExists(file)
    }
  }

  private fun sharedDiagnostics(file: java.nio.file.Path, version: Int, revision: Long) =
      when (val result = JavaDiagnosticsInterop.diagnostics(file, ICancelChecker.NOOP, version, revision)) {
        is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
        JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
        is JavaDiagnosticsInterop.Result.Failure -> throw result.error
      }
}
