package com.tom.rv2ide.language.services.java.psi

import com.github.javaparser.StaticJavaParser
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnhandledExceptionDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiAddUnhandledExceptionsParams
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
class JavaPsiAddThrowsCapabilityTest {
  @Test
  fun sharedUnhandledExceptionDiagnosticGeneratesSnapshotBoundThrowsAndImportEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiAddThrows.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiAddThrows {}"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import java.io.IOException;
          class Service { static void invoke() throws IOException, java.sql.SQLException {} }
          class JavaPsiAddThrows {
            void call() { Service.invoke(); }
            void callWithExisting() throws IOException { Service.invoke(); }
            Object reflect() throws ClassNotFoundException, NoSuchMethodException, InstantiationException, IllegalAccessException {
              Class<?> clazz = Class.forName("com.example.Foo");
              Object obj = clazz.getDeclaredConstructor().newInstance();
              return obj;
            }
          }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      openDocument(path, content, version = 1, revision = 901L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis {
            runtime.onFileCreated(path)
            runtime.onFileOpened(path)
          }
          fun diagnostics(version: Int = 1, revision: Long = 901L) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }
          fun generate(target: JavaUnhandledExceptionDiagnostic) = runAnalysis {
            JavaPsiServiceInterop.addUnhandledExceptions(
                JavaPsiAddUnhandledExceptionsParams(target, ICancelChecker.NOOP),
            )
          }

          val targets = diagnostics().mapNotNull(JavaUnhandledExceptionDiagnostic::from)
              .associateBy { it.methodName }
          val callTarget = requireNotNull(targets["call"])
          val callEdits = requireNotNull(generate(callTarget))
          assertEquals(setOf("java.sql.SQLException"), importNames(callEdits))
          assertTrue(callEdits.all { it.documentVersion == 1 && it.documentRevision == 901L })
          val callSource = applyAll(content, callEdits)
          val callMethod = StaticJavaParser.parse(callSource).getClassByName("JavaPsiAddThrows").get()
              .getMethodsByName("call").single()
          assertEquals(setOf("IOException", "SQLException"), callMethod.thrownExceptions.map { it.asString() }.toSet())
          assertTrue(callSource.contains("import java.sql.SQLException;"))

          val reflectTarget = requireNotNull(targets["reflect"])
          val reflectEdits = requireNotNull(generate(reflectTarget))
          assertEquals(setOf("java.lang.reflect.InvocationTargetException"), importNames(reflectEdits))
          val reflectSource = applyAll(content, reflectEdits)
          val reflectMethod = StaticJavaParser.parse(reflectSource).getClassByName("JavaPsiAddThrows").get()
              .getMethodsByName("reflect").single()
          assertEquals(
              setOf(
                  "ClassNotFoundException", "NoSuchMethodException", "InstantiationException",
                  "IllegalAccessException", "InvocationTargetException",
              ),
              reflectMethod.thrownExceptions.map { it.asString() }.toSet(),
          )

          val existingTarget = requireNotNull(targets["callWithExisting"])
          val existingEdits = requireNotNull(generate(existingTarget))
          assertEquals(setOf("java.sql.SQLException"), importNames(existingEdits))
          val existingSource = applyAll(content, existingEdits)
          val existingMethod = StaticJavaParser.parse(existingSource).getClassByName("JavaPsiAddThrows").get()
              .getMethodsByName("callWithExisting").single()
          assertEquals(setOf("IOException", "SQLException"), existingMethod.thrownExceptions.map { it.asString() }.toSet())

          val covered = existingTarget.copy(exceptionQualifiedNames = listOf("java.io.IOException"))
          assertTrue(requireNotNull(generate(covered)).isEmpty())

          openDocument(path, content, version = 2, revision = 902L)
          runAnalysis { runtime.onFileOpened(path) }
          assertNull(generate(callTarget))
          assertEquals(disk, Files.readAllBytes(path).decodeToString())
        }
      } finally {
        closeDocument(path)
        Files.deleteIfExists(path)
      }
    }
  }

  private fun importNames(edits: List<JavaPsiTextEdit>): Set<String> = edits
      .flatMap { edit -> Regex("import ([A-Za-z0-9_.$]+);").findAll(edit.newText).map { it.groupValues[1] }.toList() }
      .toSet()

  private fun applyAll(content: String, edits: List<JavaPsiTextEdit>): String {
    val index = LineIndex.from(content)
    val ordered = edits.sortedByDescending { index.lineColumnToIndex(it.startLine, it.startColumn) }
    return ordered.fold(content) { source, edit ->
      val start = index.lineColumnToIndex(edit.startLine, edit.startColumn)
      val end = index.lineColumnToIndex(edit.endLine, edit.endColumn)
      source.replaceRange(start, end, edit.newText)
    }
  }
}