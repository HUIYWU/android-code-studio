package com.tom.rv2ide.language.services.java.psi

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateOverridesParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverrideMethodsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverridesResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import java.util.concurrent.CompletionException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiOverrideActionCapabilityTest {
  @Test
  fun overridesUseSnapshotBoundCandidatesAndGeneratePsiEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiOverrides.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiOverrides {}"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import java.util.List;
          interface Contract<T> { T convert(T value) throws java.io.IOException; }
          class Parent<T> {
            protected T echo(T value) { return value; }
            public static void ignored() {}
            public final void fixed() {}
            public void ready(int value) {}
            public <X extends CharSequence> X choose(X value) { return value; }
            public List<T> values() { return null; }
          }
          class Child extends Parent<String> implements Contract<String> { void keep() {} }
          class Missing { void keep() {} }
          abstract class AbstractBase { protected abstract long count(); }
          abstract class AbstractChild extends AbstractBase { }
          class Existing extends Parent<String> { @Override protected String echo(String value) { return value; } }
          class InlineBase { public void invoke(String value) {} }
          class InlineHolder { Object make() { return new InlineBase() { int anchor; }; } }
          class DuplicateImportParent { public void first(java.io.IOException error) {} public void second(java.io.IOException error) {} }
          class DuplicateImportChild extends DuplicateImportParent { }
          class GenericParent<T> { public T map(T value) { return value; } }
          class GenericChild<U> extends GenericParent<List<U>> { }
          class ImportCollision extends Parent<String> { static class List<X> {} }
          class CollisionParent { public java.util.Date date() { return null; } }
          class CollisionChild extends CollisionParent { static class Date {} }
      """.trimIndent().replace("\n", "\r\n")
      Files.write(path, disk.toByteArray())
      openDocument(path, content, version = 1, revision = 701L)
      try {
        withRuntime(workspace) {
          fun range(source: String, marker: String): Range {
            val offset = source.indexOf(marker)
            check(offset >= 0) { marker }
            val position = LineIndex.from(source).indexToPosition(offset).apply { index = -1 }
            return Range.pointRange(position)
          }
          fun methods(
              marker: String,
              source: String = content,
              version: Int = 1,
              revision: Long = 701L,
              checker: ICancelChecker = ICancelChecker.NOOP,
          ) = runAnalysis {
            JavaPsiServiceInterop.overrideMethods(
                JavaPsiOverrideMethodsParams(path, range(source, marker), version, revision, checker),
            )
          }
          val candidates = requireNotNull(methods("class Child extends Parent<String>"))
          val byLabel = candidates.methods.associateBy { it.label }
          val echo = requireNotNull(byLabel["echo(String value)"])
          val convert = requireNotNull(byLabel["convert(String value)"])
          assertTrue(byLabel.containsKey("choose(X value)"))
          assertTrue(byLabel.containsKey("values()"))
          assertTrue(byLabel.containsKey("ready(int value)"))
          assertFalse(byLabel.keys.any { it.startsWith("ignored(") })
          assertFalse(byLabel.keys.any { it.startsWith("fixed(") })
          assertFalse(byLabel.values.any { it.id.substringBefore('#') == "java.lang.Object" })

          fun generate(
              marker: String,
              ids: Set<String>,
              source: String = content,
              version: Int = 1,
              revision: Long = 701L,
              useSoftTabs: Boolean = true,
              checker: ICancelChecker = ICancelChecker.NOOP,
          ) = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.generateOverrides(
                JavaPsiGenerateOverridesParams(
                    path, range(source, marker), ids, version, revision,
                    2, useSoftTabs, checker,
                ),
            )
          })

          val generated = generate("class Child extends Parent<String>", setOf(echo.id, convert.id), useSoftTabs = false)
          assertEquals(JavaPsiOverridesResultStatus.GENERATED, generated.status)
          assertEquals(2, generated.edits.size)
          assertEquals(setOf(path.normalize()), generated.edits.map { it.file }.toSet())
          assertTrue(generated.edits.all { it.documentVersion == 1 && it.documentRevision == 701L })
          assertTrue(generated.edits.all { it.startLine == it.endLine && it.startColumn == it.endColumn })
          val applied = StaticJavaParser.parse(applyAll(content, generated.edits))
          val memberText = generated.edits.single { it.newText.contains("@Override") }.newText
          assertTrue(memberText.contains("\r\n\t@Override\r\n"))
          assertTrue(memberText.contains("\r\n\t\treturn super.echo(value);\r\n"))
          assertTrue(generated.edits.none { it.newText.replace("\r\n", "").contains('\n') })
          val child = applied.getClassByName("Child").get()
          assertEquals(listOf("keep", "echo", "convert"), child.methods.map { it.nameAsString })
          val echoMethod = child.getMethodsByName("echo").single()
          assertEquals("String", echoMethod.type.asString())
          assertEquals("String", echoMethod.parameters.single().type.asString())
          assertEquals(
              listOf("return super.echo(value);"),
              echoMethod.body.get().statements.map { it.toString() },
          )
          val convertMethod = child.getMethodsByName("convert").single()
          assertEquals("String", convertMethod.type.asString())
          assertEquals(1, convertMethod.thrownExceptions.size)
          assertTrue(convertMethod.thrownExceptions.single().asString().endsWith("IOException"))
          assertEquals(listOf("return null;"), convertMethod.body.get().statements.map { it.toString() })
          assertEquals(listOf("java.util.List", "java.io.IOException"), applied.imports.map { it.nameAsString })
          assertTrue(applied.imports.none { it.nameAsString.startsWith("java.lang.") })
          assertTrue(requireNotNull(methods("class Missing")).methods.isEmpty())
          val duplicateImportMethods = requireNotNull(methods("class DuplicateImportChild"))
          val duplicateImportResult = generate(
              "class DuplicateImportChild",
              duplicateImportMethods.methods.map { it.id }.toSet(),
          )
          assertEquals(1, duplicateImportResult.edits.count { it.newText.contains("import java.io.IOException;") })

          val empty = generate("class Child extends Parent<String>", emptySet())
          assertEquals(JavaPsiOverridesResultStatus.EMPTY_SELECTION, empty.status)
          val invalid = generate("class Child extends Parent<String>", setOf("not-a-candidate"))
          assertEquals(JavaPsiOverridesResultStatus.UNSUPPORTED, invalid.status)
          val abstractMethod = requireNotNull(methods("abstract class AbstractChild extends AbstractBase"))
              .methods.single { it.label == "count()" }
          val abstractGenerated = generate("abstract class AbstractChild extends AbstractBase", setOf(abstractMethod.id))
          val abstractSource = applyAll(content, abstractGenerated.edits)
          val abstractChild = StaticJavaParser.parse(abstractSource).getClassByName("AbstractChild").get()
          assertTrue(abstractChild.isAbstract)
          assertEquals(listOf("return 0L;"), abstractChild.methods.single().body.get().statements.map { it.toString() })

          val existing = requireNotNull(methods("class Existing extends Parent<String>"))
          assertFalse(existing.methods.any { it.label == "echo(String value)" })
          val genericMethods = requireNotNull(methods("class GenericChild<U>"))
          val genericMap = genericMethods.methods.single { it.label == "map(List<U> value)" }
          val genericSource = applyAll(content, generate("class GenericChild<U>", setOf(genericMap.id)).edits)
          assertEquals("List<U>", StaticJavaParser.parse(genericSource).getClassByName("GenericChild").get()
              .getMethodsByName("map").single().parameters.single().type.asString())

          val collision = requireNotNull(methods("class ImportCollision"))
              .methods.single { it.label == "values()" }
          val collisionResult = generate("class ImportCollision", setOf(collision.id))
          val collisionSource = applyAll(content, collisionResult.edits)
          val collisionClass = StaticJavaParser.parse(collisionSource).getClassByName("ImportCollision").get()
          assertTrue(collisionClass.methods.single().type.asString().contains("List"))
          assertTrue(collisionSource.contains("java.util.List<String> values()"))

          val dateMethod = requireNotNull(methods("class CollisionChild"))
              .methods.single { it.label == "date()" }
          val dateSource = applyAll(content, generate("class CollisionChild", setOf(dateMethod.id)).edits)
          val dateClass = StaticJavaParser.parse(dateSource).getClassByName("CollisionChild").get()
          assertEquals("java.util.Date", dateClass.getMethodsByName("date").single().type.asString())
          assertFalse(dateSource.contains("import java.util.Date;"))

          val anonymous = requireNotNull(methods("int anchor;"))
          val invoke = anonymous.methods.single { it.label == "invoke(String value)" }
          val anonymousSource = applyAll(content, generate("int anchor;", setOf(invoke.id)).edits)
          val creation = StaticJavaParser.parse(anonymousSource).findAll(ObjectCreationExpr::class.java)
              .single { it.anonymousClassBody.isPresent }
          val anonymousMethods = creation.anonymousClassBody.get().filterIsInstance<MethodDeclaration>()
          assertEquals(listOf("invoke"), anonymousMethods.map { it.nameAsString })
          assertEquals(listOf("super.invoke(value);"), anonymousMethods.single().body.get().statements.map { it.toString() })

          val inline = requireNotNull(methods("class InlineHolder"))
          assertTrue(inline.methods.isEmpty())

          val staleVersion = methods("class Child extends Parent<String>", version = 0)
          assertNull(staleVersion)
          val staleRevision = methods("class Child extends Parent<String>", revision = 700L)
          assertNull(staleRevision)
          val methodCancellation = assertThrows(CompletionException::class.java) {
            methods("class Child extends Parent<String>", checker = ICancelChecker.CANCELLED)
          }
          assertTrue(methodCancellation.cause is CancellationException)

          openDocument(path, content, version = 2, revision = 702L)
          assertNull(runAnalysis {
            JavaPsiServiceInterop.generateOverrides(
                JavaPsiGenerateOverridesParams(
                    path, range(content, "class Child extends Parent<String>"), setOf(echo.id, convert.id),
                    1, 701L, 2, true, ICancelChecker.NOOP,
                ),
            )
          })
          val refreshed = requireNotNull(methods("class Child extends Parent<String>", content, 2, 702L))
          val refreshedEcho = refreshed.methods.single { it.label == "echo(String value)" }
          assertEquals(echo.id, refreshedEcho.id)
          assertEquals(2, refreshed.documentVersion)
          assertEquals(702L, refreshed.documentRevision)
          val generationCancellation = assertThrows(CompletionException::class.java) {
            runAnalysis {
              JavaPsiServiceInterop.generateOverrides(
                  JavaPsiGenerateOverridesParams(
                      path, range(content, "class Child extends Parent<String>"), setOf(refreshedEcho.id),
                      2, 702L, 2, true, ICancelChecker.CANCELLED,
                  ),
              )
            }
          }
          assertTrue(generationCancellation.cause is CancellationException)
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