package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldInfo
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import org.junit.Assert.assertEquals
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

  private fun selection(content: String, marker: String): Range {
    val start = content.indexOf(marker)
    check(start >= 0) { marker }
    val end = start + marker.length
    val index = LineIndex.from(content)
    return Range(index.indexToPosition(start), index.indexToPosition(end))
  }
}
