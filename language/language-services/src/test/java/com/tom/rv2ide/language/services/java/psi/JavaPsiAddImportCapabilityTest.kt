package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnresolvedTypeDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiResolveImportCandidatesParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
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
class JavaPsiAddImportCapabilityTest {
  @Test
  fun sharedUnresolvedTypeDiagnosticsGenerateSnapshotBoundImportCandidates() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiAddImport.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiAddImport {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          class JavaPsiAddImport {
            Date value;
            TimerTask task = new TimerTask() {
              @Override public void run() { }
            };
            void unresolved() { missingCall(); }
          }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      try {
        openDocument(path, content, version = 1, revision = 1001L)
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(path) }
          fun diagnostics(version: Int = 1, revision: Long = 1001L) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          val allDiagnostics = diagnostics()
          val typeTargets = allDiagnostics.mapNotNull(JavaUnresolvedTypeDiagnostic::from)
          val target = requireNotNull(typeTargets.firstOrNull { it.simpleName == "Date" })
          val anonymousStart = content.indexOf("new TimerTask") + "new ".length
          val anonymousTarget = requireNotNull(typeTargets.firstOrNull {
            it.simpleName == "TimerTask" && it.referenceStart == anonymousStart
          })
          assertEquals(path.normalize(), target.file)
          assertEquals(1, target.documentVersion)
          assertEquals(1001L, target.documentRevision)

          val module = requireNotNull(workspace.findModuleForFile(path))
          val candidates = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.resolveImportCandidates(
                JavaPsiResolveImportCandidatesParams(target, module, ICancelChecker.NOOP),
            )
          })
          val anonymousCandidates = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.resolveImportCandidates(
                JavaPsiResolveImportCandidatesParams(anonymousTarget, module, ICancelChecker.NOOP),
            )
          })
          assertEquals(setOf("java.sql.Date", "java.util.Date"), candidates.map { it.qualifiedName }.toSet())
          assertEquals(setOf("java.util.TimerTask"), anonymousCandidates.map { it.qualifiedName }.toSet())
          assertTrue(candidates.all {
            it.edit.file.normalize() == path.normalize() && it.edit.documentVersion == 1 &&
                it.edit.documentRevision == 1001L && it.edit.startLine == it.edit.endLine &&
                it.edit.startColumn == it.edit.endColumn
          })
          val importedSources = candidates.map { apply(content, it.edit) }
          assertTrue(importedSources.any { it.contains("import java.sql.Date;\n") })
          assertTrue(importedSources.any { it.contains("import java.util.Date;\n") })

          val unresolvedDiagnostics = allDiagnostics.filter {
            it.source == JavaUnresolvedTypeDiagnostic.CODESERVER_SOURCE &&
                it.code == JavaUnresolvedTypeDiagnostic.REFERENCE_UNRESOLVED_KEY
          }
          assertTrue("expected a shared unresolved reference diagnostic", unresolvedDiagnostics.isNotEmpty())
          assertTrue(unresolvedDiagnostics.none {
            JavaUnresolvedTypeDiagnostic.from(it)?.simpleName == "missingCall"
          })

          openDocument(path, content, version = 2, revision = 1002L)
          runAnalysis { runtime.onFileOpened(path) }
          assertNull(runAnalysis {
            JavaPsiServiceInterop.resolveImportCandidates(
                JavaPsiResolveImportCandidatesParams(target, module, ICancelChecker.NOOP),
            )
          })
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
