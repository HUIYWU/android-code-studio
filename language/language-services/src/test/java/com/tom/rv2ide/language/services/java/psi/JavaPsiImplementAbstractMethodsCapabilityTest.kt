package com.tom.rv2ide.language.services.java.psi

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaAbstractMethodsDiagnostic
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiImplementAbstractMethodsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverridesResultStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiImplementAbstractMethodsCapabilityTest {
  @Test
  fun sharedDiagnosticsDriveClassAndExistingAnonymousClassEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiImplementAbstractMethods.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiImplementAbstractMethods {}"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          interface Contract<T> { T convert(T value); java.util.List<T> values(); default int priority() { return 1; } }
          abstract class Base<T> { abstract T apply(T value); abstract int size(); }
          class Missing implements Contract<String> { }
          class AnonymousHost {
            Object create() {
              return new Base<String>() {
                @Override String apply(String value) { return value; }
                int keep;
              };
            }
          }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      openDocument(path, content, version = 1, revision = 801L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(path) }
          fun diagnostics(version: Int = 1, revision: Long = 801L) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }
          fun generate(target: JavaAbstractMethodsDiagnostic) = runAnalysis {
            JavaPsiServiceInterop.implementAbstractMethods(
                JavaPsiImplementAbstractMethodsParams(target, 2, true, ICancelChecker.NOOP),
            )
          }

          val missingTarget = requireNotNull(JavaAbstractMethodsDiagnostic.from(diagnostics().first {
            it.code == JavaAbstractMethodsDiagnostic.CODESERVER_KEY &&
                (it.extra as? JavaAbstractMethodsDiagnostic)?.targetName?.substringAfterLast('.') == "Missing"
          }))
          val classResult = requireNotNull(generate(missingTarget))
          assertEquals(JavaPsiOverridesResultStatus.GENERATED, classResult.status)
          assertTrue(classResult.edits.all { it.documentVersion == 1 && it.documentRevision == 801L })
          val classSource = applyAll(content, classResult.edits)
          val missing = StaticJavaParser.parse(classSource).getClassByName("Missing").get()
          assertEquals(setOf("convert", "values"), missing.methods.map { it.nameAsString }.toSet())
          assertFalse(missing.methods.any { it.nameAsString == "priority" })
          assertEquals("String", missing.getMethodsByName("convert").single().parameters.single().type.asString())
          assertTrue(missing.getMethodsByName("values").single().type.asString().contains("List<String>"))
          assertTrue(classResult.edits.any { it.newText.contains("import java.util.List;") })

          val anonymousTarget = requireNotNull(JavaAbstractMethodsDiagnostic.from(diagnostics().first {
            it.code == JavaAbstractMethodsDiagnostic.CODESERVER_KEY &&
                (it.extra as? JavaAbstractMethodsDiagnostic)?.anonymous == true
          }))
          val anonymousResult = requireNotNull(generate(anonymousTarget))
          assertEquals(JavaPsiOverridesResultStatus.GENERATED, anonymousResult.status)
          val anonymousSource = applyAll(content, anonymousResult.edits)
          val creation = StaticJavaParser.parse(anonymousSource).findAll(ObjectCreationExpr::class.java)
              .single { it.anonymousClassBody.isPresent }
          val anonymousMembers = creation.anonymousClassBody.get()
          val generatedMethods = anonymousMembers.filterIsInstance<MethodDeclaration>()
          assertEquals(setOf("apply", "size"), generatedMethods.map { it.nameAsString }.toSet())
          assertEquals(1, generatedMethods.count { it.nameAsString == "apply" })
          assertTrue(anonymousMembers.any { it.toString().contains("int keep;") })
          assertTrue(anonymousSource.contains("new Base<String>()"))

          val completed = content.replace("class Missing implements Contract<String> { }", """
              class Missing implements Contract<String> {
                @Override String convert(String value) { return value; }
                @Override java.util.List<String> values() { return null; }
              }
          """.trimIndent())
          openDocument(path, completed, version = 2, revision = 802L)
          runAnalysis { runtime.onFileOpened(path) }
          assertNull(generate(missingTarget))
          val currentDiagnostics = diagnostics(2, 802L)
          assertFalse(currentDiagnostics.any {
            it.code == JavaAbstractMethodsDiagnostic.CODESERVER_KEY &&
                (it.extra as? JavaAbstractMethodsDiagnostic)?.targetName?.substringAfterLast('.') == "Missing"
          })
          assertEquals(disk, Files.readAllBytes(path).decodeToString())
        }
      } finally {
        closeDocument(path)
        Files.deleteIfExists(path)
      }
    }
  }

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
