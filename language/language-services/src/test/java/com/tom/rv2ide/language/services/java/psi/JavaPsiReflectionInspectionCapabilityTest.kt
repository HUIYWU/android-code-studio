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
class JavaPsiReflectionInspectionCapabilityTest {
  @Test
  fun publishesOfficialReflectionMemberAndInvocationWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiReflectionInspection.java",
      )
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiReflectionInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import java.lang.reflect.Method;

          class JavaPsiReflectionInspection {
            static final class Target {
              private int hidden;
              public int shown;

              public Target(int value) { hidden = value; }
              public static void accept(String text) {}
            }

            void lookUp(Class<?> unknownType) throws ReflectiveOperationException {
              Target.class.getField("hidden");
              Target.class.getDeclaredField("hidden");
              Target.class.getMethod("accept", int.class);
              Target.class.getMethod("accept", String.class);
              unknownType.getMethod("accept", int.class);
            }

            void invoke(Method unknown) throws ReflectiveOperationException {
              Target.class.getMethod("accept", String.class).invoke(null, 42);
              Method method = Target.class.getDeclaredMethod("accept", String.class);
              method.invoke(null, "first", "extra");
              method.invoke(null, "ok");
              Target.class.getConstructor(int.class).newInstance("wrong");
              Target.class.getConstructor(int.class).newInstance(1);
              unknown.invoke(null, 31);
            }

            @SuppressWarnings("JavaReflectionMemberAccess")
            void suppressedLookup() throws ReflectiveOperationException {
              Target.class.getField("missing");
            }

            @SuppressWarnings("JavaReflectionInvocation")
            void suppressedInvoke() throws ReflectiveOperationException {
              Target.class.getMethod("accept", String.class).invoke(null, 87);
            }
          }
      """.trimIndent()
      val privateField = Warning(
          "JavaReflectionMemberAccess", JavaBundle.message("inspection.reflection.member.access.field.not.public", "hidden"),
          "Target.class.getField(", "\"hidden\"",
      )
      val wrongSignature = Warning(
          "JavaReflectionMemberAccess", JavaBundle.message("inspection.reflection.member.access.cannot.resolve.method.arguments", "accept"),
          "Target.class.getMethod(", "\"accept\"", ", int.class)",
      )
      val wrongType = Warning(
          "JavaReflectionInvocation", JavaBundle.message("inspection.reflection.invocation.argument.not.assignable", "java.lang.String"),
          ".invoke(null, ", "42",
      )
      val wrongCount = Warning(
          "JavaReflectionInvocation", JavaBundle.message("inspection.reflection.invocation.reflective.argument.count", 1),
          "method.invoke(null, ", "\"first\", \"extra\"",
      )
      val wrongConstructorType = Warning(
          "JavaReflectionInvocation", JavaBundle.message("inspection.reflection.invocation.argument.not.assignable", "int"),
          ".newInstance(", "\"wrong\"",
      )
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 2601L)
        withRuntime(workspace) { runtime ->
          fun diagnostics(version: Int, revision: Long) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          runAnalysis { runtime.onFileOpened(path) }
          assertWarnings(content, diagnostics(1, 2601L), privateField, wrongSignature, wrongType, wrongCount, wrongConstructorType)

          val edited = content.replace("getField(\"hidden\")", "getField(\"shown\")")
              .replace("invoke(null, 42)", "invoke(null, \"valid\")")
          openDocument(path, edited, version = 2, revision = 2602L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(edited, diagnostics(2, 2602L), wrongSignature, wrongCount, wrongConstructorType)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    assertEquals(emptyList<DiagnosticItem>(), diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    val warnings = diagnostics.filter { it.code == "JavaReflectionMemberAccess" || it.code == "JavaReflectionInvocation" }
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