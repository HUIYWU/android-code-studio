package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.actions.FieldBasedAction
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldInfo
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runSuspend
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiFieldActionCapabilityTest {
  @Test
  fun activeClassFieldEnumerationUsesPsiAndRejectsStaleRequests() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiFields.java")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import java.util.List;
          class JavaPsiFields {
            private int count;
            static String ignored;
            final List<String> names = null;
            class Nested {
              long nestedValue;
            }
            void use() { count++; }
          }
      """.trimIndent()
      Files.write(path, "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiFields {}".toByteArray())
      openDocument(path, content, version = 1, revision = 301L)
      try {
        withRuntime(workspace) {
          val fields = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.fields(
                JavaPsiFieldsParams(
                    path,
                    selection(content, "count;"),
                    1,
                    301L,
                    ICancelChecker.NOOP,
                ),
            )
          })
          assertEquals(
              listOf(
                  JavaPsiFieldInfo("count", "int", false),
                  JavaPsiFieldInfo("names", "List<String>", true),
              ),
              fields.fields,
          )
          assertEquals(1, fields.documentVersion)
          assertEquals(301L, fields.documentRevision)

          val nested = runAnalysis {
            JavaPsiServiceInterop.fields(
                JavaPsiFieldsParams(
                    path,
                    selection(content, "nestedValue;"),
                    1,
                    301L,
                    ICancelChecker.NOOP,
                ),
            )
          }
          assertEquals(listOf(JavaPsiFieldInfo("nestedValue", "long", false)), nested?.fields)

          openDocument(path, content, version = 2, revision = 302L)
          assertNull(
              runAnalysis {
                JavaPsiServiceInterop.fields(
                    JavaPsiFieldsParams(
                        path,
                        selection(content, "count;"),
                        1,
                        301L,
                        ICancelChecker.NOOP,
                    ),
                )
              },
          )
        }
      } finally {
        closeDocument(path)
        Files.deleteIfExists(path)
      }
    }
  }

  @Test
  fun fieldActionsOnlyDeliverResultsForTheMenuSnapshot() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiFieldActions.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiFieldActions {}"
      val content = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiFieldActions { int count; }"
      Files.write(path, disk.toByteArray())
      openDocument(path, content, version = 1, revision = 311L)
      try {
        withRuntime(workspace) {
          val action = CapturingFieldAction()
          val identity = DocumentSnapshotIdentity(path, 1, 311L)
          fun data(identity: DocumentSnapshotIdentity?): ActionData = ActionData().apply {
            put(File::class.java, path.toFile())
            put(Range::class.java, selection(content, "count;"))
            put(DocumentSnapshotIdentity::class.java, identity)
          }
          fun execute(data: ActionData): Any = runAnalysis { runSuspend { action.execAction(data) } }
          val invalidIdentities = listOf(
              null,
              identity.copy(file = path.resolveSibling("Another.java")),
              identity.copy(version = 0),
              identity.copy(revision = 310L),
          )
          for (invalid in invalidIdentities) {
            val request = data(invalid)
            val result = execute(request)
            assertFalse(result is JavaPsiFieldsResult)
            action.postExec(request, result)
            assertNull(action.delivered)
          }
          val currentData = data(identity)
          val current = execute(currentData) as JavaPsiFieldsResult
          assertEquals(listOf(JavaPsiFieldInfo("count", "int", false)), current.fields)
          action.postExec(currentData, current)
          assertEquals(current, action.delivered)
          action.delivered = null

          openDocument(path, content, version = 2, revision = 312L)
          action.postExec(currentData, current)
          assertNull(action.delivered)
          val stale = execute(currentData)
          assertFalse(stale is JavaPsiFieldsResult)
          action.postExec(currentData, stale)
          assertNull(action.delivered)
          val refreshedData = data(identity.copy(version = 2, revision = 312L))
          val refreshed = execute(refreshedData) as JavaPsiFieldsResult
          action.postExec(refreshedData, refreshed)
          assertEquals(refreshed, action.delivered)
          action.delivered = null

          closeDocument(path)
          action.postExec(refreshedData, refreshed)
          assertNull(action.delivered)
          val closed = execute(refreshedData)
          assertFalse(closed is JavaPsiFieldsResult)
          action.postExec(refreshedData, closed)
          assertNull(action.delivered)
          assertEquals(disk, Files.readAllBytes(path).decodeToString())
        }
      } finally {
        closeDocument(path)
        Files.deleteIfExists(path)
      }
    }
  }

  private class CapturingFieldAction : FieldBasedAction() {
    override val titleTextRes: Int = -1
    override val id: String = "test.java.fields"
    override var label: String = ""
    var delivered: JavaPsiFieldsResult? = null

    override fun onGetPsiFields(result: JavaPsiFieldsResult, data: ActionData) {
      delivered = result
    }
  }

  private fun selection(content: String, marker: String): Range {
    val start = content.indexOf(marker)
    check(start >= 0) { marker }
    val end = start + marker.length
    val index = LineIndex.from(content)
    return Range(index.indexToPosition(start), index.indexToPosition(end))
  }
}
