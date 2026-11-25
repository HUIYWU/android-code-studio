package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnresolvedMethodDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.java.interop.JavaPsiUnresolvedMethodParams
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.lsp.models.LineIndex
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiCreateMissingMethodCapabilityTest {
  @Test
  fun sharedUnresolvedMethodDiagnosticsGenerateSnapshotBoundMethodEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiCreateMissingMethod.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiCreateMissingMethod {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          class JavaPsiCreateMissingMethod {
            void call() { missing(1, "value"); }
          }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      try {
        openDocument(path, content, version = 1, revision = 1201L)
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(path) }
          fun diagnostics(version: Int = 1, revision: Long = 1201L) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }
          fun generate(target: JavaUnresolvedMethodDiagnostic) = runAnalysis {
            JavaPsiServiceInterop.createMissingMethod(
                JavaPsiUnresolvedMethodParams(target, 2, true, ICancelChecker.NOOP),
            )
          }

          val target = requireNotNull(diagnostics().mapNotNull(JavaUnresolvedMethodDiagnostic::from)
              .single { it.methodName == "missing" })
          val edit = requireNotNull(generate(target))
          assertEquals(path.normalize(), edit.file.normalize())
          assertEquals(1, edit.documentVersion)
          assertEquals(1201L, edit.documentRevision)
          assertTrue(edit.startLine == edit.endLine && edit.startColumn == edit.endColumn)
          assertTrue(edit.newText.contains("private void missing(int param0, String param1)"))
          assertTrue(edit.newText.contains("// TODO: Implement this method"))
          assertTrue(apply(content, edit).contains("private void missing(int param0, String param1)"))

          openDocument(path, content, version = 2, revision = 1202L)
          runAnalysis { runtime.onFileOpened(path) }
          assertNull(generate(target))
          assertEquals(disk, Files.readAllBytes(path).decodeToString())
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun apply(content: String, edit: JavaPsiTextEdit): String {
    val index = LineIndex.from(content)
    val start = index.lineColumnToIndex(edit.startLine, edit.startColumn)
    val end = index.lineColumnToIndex(edit.endLine, edit.endColumn)
    return content.replaceRange(start, end, edit.newText)
  }
}