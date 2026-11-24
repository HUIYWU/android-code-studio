package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnusedThrowsDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiRemoveUnusedThrowsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.progress.ICancelChecker
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
class JavaPsiRemoveUnusedThrowsCapabilityTest {
  @Test
  fun sharedRedundantThrowsWarningGeneratesSnapshotBoundRemovalEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiRemoveUnusedThrows.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiRemoveUnusedThrows {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import java.io.IOException;
          import java.sql.SQLException;
          class JavaPsiRemoveUnusedThrows {
            void redundant() throws IOException, SQLException {}
            void single() throws SQLException {}
            void actual() throws IOException { throw new IOException(); }
          }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      try {
        openDocument(path, content, version = 1, revision = 1101L)
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(path) }
          fun diagnostics(version: Int = 1, revision: Long = 1101L) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }
          fun generate(target: JavaUnusedThrowsDiagnostic) = runAnalysis {
            JavaPsiServiceInterop.removeUnusedThrows(
                JavaPsiRemoveUnusedThrowsParams(target, ICancelChecker.NOOP),
            )
          }

          val allDiagnostics = diagnostics()
          val warningItems = allDiagnostics.filter {
            it.code == "ide.java.unused.throws" && it.source == "java-inspection"
          }
          assertTrue(warningItems.isNotEmpty())
          assertTrue(warningItems.all { it.severity == DiagnosticSeverity.WARNING })
          val targets = warningItems.mapNotNull(JavaUnusedThrowsDiagnostic::from)
              .filter { it.methodName == "redundant" }
          assertEquals(2, targets.size)
          val singleTarget = requireNotNull(allDiagnostics.mapNotNull(JavaUnusedThrowsDiagnostic::from)
              .single { it.methodName == "single" })
          assertTrue(allDiagnostics.none {
            JavaUnusedThrowsDiagnostic.from(it)?.methodName == "actual"
          })

          val edits = targets.map { requireNotNull(generate(it)) }
          assertTrue(edits.all {
            it.file.normalize() == path.normalize() && it.documentVersion == 1 &&
                it.documentRevision == 1101L && it.newText.isEmpty()
          })
          val singleEdit = requireNotNull(generate(singleTarget))
          assertTrue(apply(content, singleEdit).contains("void single() {}"))
          val firstApplied = apply(content, edits.first())
          assertTrue(firstApplied.contains("void redundant() throws SQLException {}"))
          val secondApplied = apply(content, edits.last())
          assertTrue(secondApplied.contains("void redundant() throws IOException {}"))

          openDocument(path, content, version = 2, revision = 1102L)
          runAnalysis { runtime.onFileOpened(path) }
          assertNull(generate(targets.first()))
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