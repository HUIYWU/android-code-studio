package com.tom.rv2ide.language.services.java.psi

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateToStringParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.java.interop.JavaPsiToStringResultStatus
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
class JavaPsiToStringActionCapabilityTest {
  @Test
  fun toStringUsesActiveFieldsAndChecksDeclaredZeroArgumentMethods() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiToStrings.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiToStrings {}"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          class JavaPsiToStrings {
            private int count;
            String label;
            final long locked = 1;
            int[] numbers;
            static int ignored;
            String untouched;
            class Nested { int first, second; void keepNested() {} }
            void keep() { String brace = "}"; }
          }
          class Existing {
            int value;
            @Override public String toString() { return "existing"; }
          }
          class Overload { int value; public String toString(int radix) { return "overload"; } }
          class Parent {
            int inherited;
            @Override public String toString() { return "parent"; }
          }
          class Child extends Parent { int own; }
          class Inline { int value; } class Sibling {}
          enum Stateful { ITEM; int value; }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      openDocument(path, content, version = 1, revision = 601L)
      try {
        withRuntime(workspace) {
          fun request(
              marker: String,
              names: Set<String>,
              source: String = content,
              version: Int = 1,
              revision: Long = 601L,
              useSoftTabs: Boolean = true,
              checker: ICancelChecker = ICancelChecker.NOOP,
          ): JavaPsiGenerateToStringParams {
            val offset = source.indexOf(marker)
            check(offset >= 0) { marker }
            val position = LineIndex.from(source).indexToPosition(offset).apply { index = -1 }
            return JavaPsiGenerateToStringParams(
                path, Range.pointRange(position), names, version, revision, 2, useSoftTabs, checker,
            )
          }
          fun generate(marker: String, vararg names: String) = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.generateToString(request(marker, names.toSet()))
          })
          val generated = generate("private int count", "numbers", "locked", "label", "count")
          assertEquals(JavaPsiToStringResultStatus.GENERATED, generated.status)
          val edit = requireNotNull(generated.edit)
          assertEquals(path.normalize(), edit.file)
          assertEquals(1, edit.documentVersion)
          assertEquals(601L, edit.documentRevision)
          assertEquals(edit.startLine, edit.endLine)
          assertEquals(edit.startColumn, edit.endColumn)
          val insertionOffset = LineIndex.from(content).lineColumnToIndex(edit.startLine, edit.startColumn)
          assertTrue(insertionOffset > content.indexOf("void keep()"))
          val expectedReturn = """return "JavaPsiToStrings[count=" + count + ", label=" + label + ", locked=" + locked + ", numbers=" + numbers + "]";"""
          val owner = StaticJavaParser.parse(apply(content, edit)).getClassByName("JavaPsiToStrings").get()
          assertEquals(listOf("keep", "toString"), owner.methods.map { it.nameAsString })
          val method = owner.getMethodsByName("toString").single()
          assertTrue(method.isPublic)
          assertTrue(method.parameters.isEmpty())
          assertEquals("String", method.type.asString())
          assertEquals(listOf("Override"), method.annotations.map { it.nameAsString })
          assertEquals(
              StaticJavaParser.parseStatement(expectedReturn).toString(),
              method.body.get().statements.single().toString(),
          )
          assertFalse(edit.newText.contains("ignored"))
          assertFalse(edit.newText.contains("untouched"))
          assertFalse(edit.newText.contains("Arrays."))
          assertTrue(edit.newText.contains("\n  @Override\n  public String toString()"))

          val existing = generate("class Existing", "value")
          assertEquals(JavaPsiToStringResultStatus.METHOD_EXISTS, existing.status)
          assertNull(existing.edit)
          val overload = StaticJavaParser.parse(apply(content, requireNotNull(generate("class Overload", "value").edit)))
              .getClassByName("Overload").get().getMethodsByName("toString")
          assertEquals(listOf(1, 0), overload.map { it.parameters.size })
          val child = StaticJavaParser.parse(apply(content, requireNotNull(generate("class Child", "own").edit)))
              .getClassByName("Child").get()
          assertEquals(listOf("toString"), child.methods.map { it.nameAsString })
          assertEquals(
              StaticJavaParser.parseStatement("""return "Child[own=" + own + "]";""").toString(),
              child.methods.single().body.get().statements.single().toString(),
          )
          assertEquals(JavaPsiToStringResultStatus.UNSUPPORTED, generate("class Child", "inherited").status)
          val nestedEdit = requireNotNull(generate("int first, second", "second").edit)
          val nestedOwner = StaticJavaParser.parse(apply(content, nestedEdit)).getClassByName("JavaPsiToStrings").get()
          assertEquals(listOf("keep"), nestedOwner.methods.map { it.nameAsString })
          val nested = nestedOwner.members.filterIsInstance<ClassOrInterfaceDeclaration>().single()
          assertEquals(listOf("keepNested", "toString"), nested.methods.map { it.nameAsString })
          assertEquals(listOf("first", "second"), nested.fields.flatMap { it.variables }.map { it.nameAsString })
          assertEquals(
              StaticJavaParser.parseStatement("""return "Nested[second=" + second + "]";""").toString(),
              nested.getMethodsByName("toString").single().body.get().statements.single().toString(),
          )
          val inline = StaticJavaParser.parse(apply(content, requireNotNull(generate("class Inline", "value").edit)))
          assertEquals(listOf("toString"), inline.getClassByName("Inline").get().methods.map { it.nameAsString })
          assertTrue(inline.getClassByName("Sibling").get().methods.isEmpty())
          val stateful = StaticJavaParser.parse(apply(content, requireNotNull(generate("enum Stateful", "value").edit)))
              .getEnumByName("Stateful").get()
          assertEquals(listOf("toString"), stateful.methods.map { it.nameAsString })
          val empty = generate("private int count")
          assertEquals(JavaPsiToStringResultStatus.EMPTY_SELECTION, empty.status)
          assertNull(empty.edit)
          val invalid = generate("private int count", "count", "missing")
          assertEquals(JavaPsiToStringResultStatus.UNSUPPORTED, invalid.status)
          assertNull(invalid.edit)
          assertEquals(JavaPsiToStringResultStatus.UNSUPPORTED, generate("private int count", "ignored").status)
          assertNull(runAnalysis { JavaPsiServiceInterop.generateToString(request("private int count", setOf("count"), revision = 600L)) })
          assertNull(runAnalysis { JavaPsiServiceInterop.generateToString(request("private int count", setOf("count"), version = 0)) })
          assertThrows(CancellationException::class.java) {
            JavaPsiServiceInterop.generateToString(request("private int count", setOf("count"), checker = ICancelChecker.CANCELLED))
          }

          val tabs = "package com.tom.rv2ide.testing.kotlinfixture.app;\r\nclass Tabbed {\r\n\tclass Inner {\r\n\t\tint value;\r\n\t}\r\n}"
          openDocument(path, tabs, version = 2, revision = 602L)
          assertNull(runAnalysis { JavaPsiServiceInterop.generateToString(request("private int count", setOf("count"))) })
          val tabResult = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.generateToString(request("int value", setOf("value"), tabs, 2, 602L, useSoftTabs = false))
          })
          assertEquals(JavaPsiToStringResultStatus.GENERATED, tabResult.status)
          val tabEdit = requireNotNull(tabResult.edit)
          assertEquals(2, tabEdit.documentVersion)
          assertEquals(602L, tabEdit.documentRevision)
          assertTrue(tabEdit.newText.contains("\r\n\t\t@Override\r\n\t\tpublic String toString()"))
          assertTrue(tabEdit.newText.contains("\r\n\t\t\treturn \"Inner[value=\" + value + \"]\";"))
          assertFalse(tabEdit.newText.replace("\r\n", "").contains('\n'))
          val tabOwner = StaticJavaParser.parse(apply(tabs, tabEdit)).getClassByName("Tabbed").get()
          assertTrue(tabOwner.methods.isEmpty())
          assertEquals(listOf("toString"), tabOwner.members.filterIsInstance<ClassOrInterfaceDeclaration>()
              .single().methods.map { it.nameAsString })
          val incomplete = "package com.tom.rv2ide.testing.kotlinfixture.app; class Incomplete { int value;"
          openDocument(path, incomplete, version = 3, revision = 603L)
          val unsupported = requireNotNull(runAnalysis {
            JavaPsiServiceInterop.generateToString(request("int value", setOf("value"), incomplete, 3, 603L))
          })
          assertEquals(JavaPsiToStringResultStatus.UNSUPPORTED, unsupported.status)
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