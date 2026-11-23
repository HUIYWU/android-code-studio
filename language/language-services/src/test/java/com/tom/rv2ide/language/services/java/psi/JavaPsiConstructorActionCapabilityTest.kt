package com.tom.rv2ide.language.services.java.psi

import com.github.javaparser.StaticJavaParser
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.interop.JavaPsiConstructorResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateConstructorParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
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
class JavaPsiConstructorActionCapabilityTest {
  @Test
  fun constructorsUseTheActiveClassAndReturnSnapshotBoundEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiConstructors.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiConstructors {}"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          class JavaPsiConstructors<T> {
            final java.util.List<T> values;
            java.util.Date utilDate;
            java.sql.Date sqlDate;
            static int ignored;
            class Nested { int first, second; }
          }
          class Duplicate {
            java.util.List<String> values;
            Duplicate(java.util.List<Integer> other) {}
          }
          class Overloaded {
            String value;
            Overloaded(Object other) {}
          }
          class ArrayDuplicate {
            String[] values;
            ArrayDuplicate(String... other) {}
          }
          class ShortTypes<T> {
            java.util.Map<java.lang.String, java.util.List<T[]>>[] entries;
          }
          class Initialized { final int value = 1; }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      openDocument(path, content, version = 1, revision = 401L)
      try {
        withRuntime(workspace) {
          fun request(marker: String, names: Set<String>, revision: Long = 401L): JavaPsiGenerateConstructorParams {
            val offset = content.indexOf(marker)
            check(offset >= 0) { marker }
            val position = LineIndex.from(content).indexToPosition(offset).apply { index = -1 }
            return JavaPsiGenerateConstructorParams(
                path, Range.pointRange(position), names, 1, revision, 2, true, ICancelChecker.NOOP,
            )
          }
          fun generate(marker: String, vararg names: String) = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.generateConstructor(request(marker, names.toSet()))
          })
          val generated = generate("final java.util.List<T> values", "sqlDate", "values", "utilDate")
          assertEquals(JavaPsiConstructorResultStatus.GENERATED, generated.status)
          val edit = requireNotNull(generated.edit)
          assertEquals(path.normalize(), edit.file)
          assertEquals(1, edit.documentVersion)
          assertEquals(401L, edit.documentRevision)
          assertEquals(edit.startLine, edit.endLine)
          assertEquals(edit.startColumn, edit.endColumn)
          assertTrue(edit.newText.contains("List<T> values, Date utilDate, Date sqlDate"))
          assertFalse(edit.newText.contains("java.util."))
          assertFalse(edit.newText.contains("java.sql."))
          assertFalse(edit.newText.contains("ignored"))
          val parsed = StaticJavaParser.parse(apply(content, edit))
          val owner = parsed.getClassByName("JavaPsiConstructors").get()
          val constructor = owner.constructors.single()
          assertEquals(listOf("values", "utilDate", "sqlDate"), constructor.parameters.map { it.nameAsString })
          assertEquals(listOf("List<T>", "Date", "Date"), constructor.parameters.map { it.type.asString() })
          assertEquals(
              listOf("this.values = values;", "this.utilDate = utilDate;", "this.sqlDate = sqlDate;"),
              constructor.body.statements.map { it.toString() },
          )

          val nested = generate("int first, second", "first")
          val nestedEdit = requireNotNull(nested.edit)
          val nestedSource = apply(content, nestedEdit)
          val nestedType = StaticJavaParser.parse(nestedSource).getClassByName("JavaPsiConstructors").get().members
              .filterIsInstance<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration>().single()
          assertEquals(listOf("first"), nestedType.constructors.single().parameters.map { it.nameAsString })
          assertTrue(nestedSource.contains("int first, second;"))
          val shortTypes = generate("class ShortTypes", "entries")
          assertEquals(JavaPsiConstructorResultStatus.GENERATED, shortTypes.status)
          val shortTypesEdit = requireNotNull(shortTypes.edit)
          assertTrue(shortTypesEdit.newText.contains("Map<String, List<T[]>>[] entries"))
          assertFalse(shortTypesEdit.newText.contains("java."))
          val shortTypesConstructor = StaticJavaParser.parse(apply(content, shortTypesEdit))
              .getClassByName("ShortTypes").get().constructors.single()
          assertEquals("Map<String, List<T[]>>[]", shortTypesConstructor.parameters.single().type.asString())
          assertEquals(JavaPsiConstructorResultStatus.CONSTRUCTOR_EXISTS, generate("class Duplicate", "values").status)
          assertEquals(JavaPsiConstructorResultStatus.GENERATED, generate("class Overloaded", "value").status)
          assertEquals(JavaPsiConstructorResultStatus.CONSTRUCTOR_EXISTS, generate("class ArrayDuplicate", "values").status)
          assertEquals(JavaPsiConstructorResultStatus.UNSUPPORTED, generate("class Initialized", "value").status)
          assertNull(runAnalysis { JavaPsiServiceInterop.generateConstructor(request("class Duplicate", setOf("values"), 400L)) })
          val cancelled = request("class Duplicate", setOf("values"))
          assertThrows(CancellationException::class.java) {
            JavaPsiServiceInterop.generateConstructor(
                JavaPsiGenerateConstructorParams(
                    cancelled.file, cancelled.selection, cancelled.selectedFields, 1, 401L, 2, true, ICancelChecker.CANCELLED,
                ),
            )
          }
          assertEquals(disk, Files.readAllBytes(path).decodeToString())
        }
      } finally {
        closeDocument(path)
        Files.deleteIfExists(path)
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
