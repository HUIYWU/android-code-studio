package com.tom.rv2ide.language.services.java.psi

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.interop.JavaPsiAccessorsResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateAccessorsParams
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
class JavaPsiAccessorsActionCapabilityTest {
  @Test
  fun accessorsUseActiveFieldsAndReturnSnapshotBoundClassEndEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiAccessors.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiAccessors {}"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          class JavaPsiAccessors<T extends Number> {
            private T value;
            boolean ready;
            final java.util.List<T> values = null;
            java.util.Map<java.lang.String, java.util.List<T[]>>[] entries;
            java.util.Date utilDate;
            java.sql.Date sqlDate;
            static int ignored;
            String untouched;
            class Nested { int first, second; void existing() {} }
            void keep() {}
          }
          class Inline { int value; } class Sibling {}
          class Names { int uRL; boolean isReady; int _value; }
          class Existing { int value; public int getValue() { return 0; } }
          enum Stateful { ITEM; int value; }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      openDocument(path, content, version = 1, revision = 501L)
      try {
        withRuntime(workspace) {
          fun request(
              marker: String,
              names: Set<String>,
              source: String = content,
              version: Int = 1,
              revision: Long = 501L,
              useSoftTabs: Boolean = true,
              checker: ICancelChecker = ICancelChecker.NOOP,
          ): JavaPsiGenerateAccessorsParams {
            val offset = source.indexOf(marker)
            check(offset >= 0) { marker }
            val position = LineIndex.from(source).indexToPosition(offset).apply { index = -1 }
            return JavaPsiGenerateAccessorsParams(
                path, Range.pointRange(position), names, version, revision, 2, useSoftTabs, checker,
            )
          }
          fun generate(marker: String, vararg names: String) = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.generateAccessors(request(marker, names.toSet()))
          })
          val generated = generate("private T value", "sqlDate", "entries", "values", "ready", "utilDate", "value")
          assertEquals(JavaPsiAccessorsResultStatus.GENERATED, generated.status)
          val edit = requireNotNull(generated.edit)
          assertEquals(path.normalize(), edit.file)
          assertEquals(1, edit.documentVersion)
          assertEquals(501L, edit.documentRevision)
          assertEquals(edit.startLine, edit.endLine)
          assertEquals(edit.startColumn, edit.endColumn)
          val insertionOffset = LineIndex.from(content).lineColumnToIndex(edit.startLine, edit.startColumn)
          assertTrue(insertionOffset > content.indexOf("void keep() {}"))
          assertFalse(edit.newText.contains("java."))
          assertFalse(edit.newText.contains("ignored"))
          assertFalse(edit.newText.contains("Untouched"))
          val owner = StaticJavaParser.parse(apply(content, edit)).getClassByName("JavaPsiAccessors").get()
          assertEquals(
              listOf("keep", "getValue", "setValue", "getReady", "setReady", "getValues", "getEntries", "setEntries",
                  "getUtilDate", "setUtilDate", "getSqlDate", "setSqlDate"),
              owner.methods.map { it.nameAsString },
          )
          val getter = owner.getMethodsByName("getValue").single()
          assertTrue(getter.isPublic)
          assertTrue(getter.parameters.isEmpty())
          assertEquals("T", getter.type.asString())
          assertEquals(listOf("return this.value;"), getter.body.get().statements.map { it.toString() })
          val setter = owner.getMethodsByName("setValue").single()
          assertTrue(setter.isPublic)
          assertEquals("void", setter.type.asString())
          assertEquals(listOf("value"), setter.parameters.map { it.nameAsString })
          assertEquals(listOf("T"), setter.parameters.map { it.type.asString() })
          assertEquals(listOf("this.value = value;"), setter.body.get().statements.map { it.toString() })
          assertEquals("boolean", owner.getMethodsByName("getReady").single().type.asString())
          assertTrue(owner.getMethodsByName("isReady").isEmpty())
          assertEquals("List<T>", owner.getMethodsByName("getValues").single().type.asString())
          assertTrue(owner.getMethodsByName("setValues").isEmpty())
          val entriesType = StaticJavaParser.parseType("Map<String, List<T[]>>[]").asString()
          assertEquals(entriesType, owner.getMethodsByName("getEntries").single().type.asString())
          assertEquals(entriesType, owner.getMethodsByName("setEntries").single().parameters.single().type.asString())
          assertEquals("Date", owner.getMethodsByName("getUtilDate").single().type.asString())
          assertEquals("Date", owner.getMethodsByName("getSqlDate").single().type.asString())

          val nested = generate("int first, second", "second")
          assertEquals(JavaPsiAccessorsResultStatus.GENERATED, nested.status)
          val nestedSource = apply(content, requireNotNull(nested.edit))
          val nestedOwner = StaticJavaParser.parse(nestedSource).getClassByName("JavaPsiAccessors").get()
          assertEquals(listOf("keep"), nestedOwner.methods.map { it.nameAsString })
          val nestedType = nestedOwner.members.filterIsInstance<ClassOrInterfaceDeclaration>().single()
          assertEquals(listOf("existing", "getSecond", "setSecond"), nestedType.methods.map { it.nameAsString })
          assertEquals(listOf("first", "second"), nestedType.fields.flatMap { it.variables }.map { it.nameAsString })
          val inline = StaticJavaParser.parse(apply(content, requireNotNull(generate("class Inline", "value").edit)))
          assertEquals(listOf("getValue", "setValue"), inline.getClassByName("Inline").get().methods.map { it.nameAsString })
          assertTrue(inline.getClassByName("Sibling").get().methods.isEmpty())
          val names = StaticJavaParser.parse(apply(content, requireNotNull(generate("class Names", "_value", "isReady", "uRL").edit)))
              .getClassByName("Names").get()
          assertEquals(listOf("getURL", "setURL", "getIsReady", "setIsReady", "get_value", "set_value"), names.methods.map { it.nameAsString })
          val existing = StaticJavaParser.parse(apply(content, requireNotNull(generate("class Existing", "value").edit)))
              .getClassByName("Existing").get()
          assertEquals(2, existing.getMethodsByName("getValue").size)
          val stateful = StaticJavaParser.parse(apply(content, requireNotNull(generate("enum Stateful", "value").edit)))
              .getEnumByName("Stateful").get()
          assertEquals(listOf("getValue", "setValue"), stateful.methods.map { it.nameAsString })
          val empty = generate("private T value")
          assertEquals(JavaPsiAccessorsResultStatus.EMPTY_SELECTION, empty.status)
          assertNull(empty.edit)
          val invalid = generate("private T value", "value", "missing")
          assertEquals(JavaPsiAccessorsResultStatus.UNSUPPORTED, invalid.status)
          assertNull(invalid.edit)
          assertEquals(JavaPsiAccessorsResultStatus.UNSUPPORTED, generate("private T value", "ignored").status)
          assertNull(runAnalysis { JavaPsiServiceInterop.generateAccessors(request("private T value", setOf("value"), revision = 500L)) })
          assertNull(runAnalysis { JavaPsiServiceInterop.generateAccessors(request("private T value", setOf("value"), version = 0)) })
          assertThrows(CancellationException::class.java) {
            JavaPsiServiceInterop.generateAccessors(request("private T value", setOf("value"), checker = ICancelChecker.CANCELLED))
          }

          val tabs = content.replace("  private T value;", "\tprivate T value;").replace("\n", "\r\n")
          openDocument(path, tabs, version = 2, revision = 502L)
          assertNull(runAnalysis { JavaPsiServiceInterop.generateAccessors(request("private T value", setOf("value"))) })
          val tabResult = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.generateAccessors(
                request("private T value", setOf("value"), tabs, 2, 502L, useSoftTabs = false),
            )
          })
          val tabEdit = requireNotNull(tabResult.edit)
          assertEquals(2, tabEdit.documentVersion)
          assertEquals(502L, tabEdit.documentRevision)
          assertTrue(tabEdit.newText.contains("\r\n\tpublic T getValue()"))
          assertTrue(tabEdit.newText.contains("\r\n\t\treturn this.value;"))
          assertFalse(tabEdit.newText.replace("\r\n", "").contains('\n'))
          assertEquals(listOf("keep", "getValue", "setValue"), StaticJavaParser.parse(apply(tabs, tabEdit))
              .getClassByName("JavaPsiAccessors").get().methods.map { it.nameAsString })
          val incomplete = "package com.tom.rv2ide.testing.kotlinfixture.app; class Incomplete { int value;"
          openDocument(path, incomplete, version = 3, revision = 503L)
          val unsupported = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.generateAccessors(request("int value", setOf("value"), incomplete, 3, 503L))
          })
          assertEquals(JavaPsiAccessorsResultStatus.UNSUPPORTED, unsupported.status)
          assertNull(unsupported.edit)
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
