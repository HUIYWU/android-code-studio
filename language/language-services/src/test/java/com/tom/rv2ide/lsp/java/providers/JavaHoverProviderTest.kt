package com.tom.rv2ide.lsp.java.providers

import com.tom.rv2ide.lsp.java.utils.MarkdownHelper
import java.net.URI
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.VariableElement
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.source.util.JavacTask
import openjdk.tools.javac.api.JavacTool
import org.junit.Assert.assertEquals
import org.junit.Test

class JavaHoverProviderTest {

  @Test
  fun formatsCompilerAttributedGenericAndWildcardSurfaces() {
    val owner = compileType(
      """
      package hover;
      class MultipleBounds<T extends CharSequence & Comparable<? super T>> {
        public java.util.List<? extends String> forced;
        MultipleBounds(T value) {}
        <R extends CharSequence & Comparable<? super R>> R multiple(R value) { return value; }
      }
      """.trimIndent(),
      "hover.MultipleBounds",
    )
    val method = owner.enclosedElements
      .filterIsInstance<ExecutableElement>()
      .single { it.simpleName.contentEquals("multiple") }
    val constructor = owner.enclosedElements
      .filterIsInstance<ExecutableElement>()
      .single { it.simpleName.contentEquals("<init>") }
    val field = owner.enclosedElements
      .filterIsInstance<VariableElement>()
      .single { it.simpleName.contentEquals("forced") }

    assertEquals(
      "class MultipleBounds<T extends CharSequence & Comparable<? super T>>",
      JavaHoverProvider.formatSignature(owner),
    )
    assertEquals(
      "<R extends CharSequence & Comparable<? super R>> R multiple(R value)",
      JavaHoverProvider.formatSignature(method),
    )
    assertEquals("MultipleBounds(T value)", JavaHoverProvider.formatSignature(constructor))
    assertEquals("List<? extends String> forced", JavaHoverProvider.formatSignature(field))
    assertEquals("T extends CharSequence & Comparable<? super T>",
      JavaHoverProvider.formatSignature(owner.typeParameters.single()))
  }

  @Test
  fun formatsJavaHoverMarkdownLikeKotlinHover() {
    assertEquals(
      "```java\npublic class AndroidDeviceOverlay\n```\n---\nAndroid device overlay.",
      JavaHoverProvider.formatHoverMarkdown(
        "public class AndroidDeviceOverlay",
        "Android device overlay.",
      ),
    )
    assertEquals(
      "```java\npublic class AndroidDeviceOverlay\n```",
      JavaHoverProvider.formatHoverMarkdown("public class AndroidDeviceOverlay", ""),
    )
  }

  @Test
  fun decodesNumericHtmlEntitiesInJavaDocumentation() {
    assertEquals(
      "Android设备性能叠加层",
      MarkdownHelper.asMarkdown("Android&#35774;&#22791;&#24615;&#33021;&#21472;&#21152;&#23618;"),
    )
    assertEquals("Android设备", MarkdownHelper.asMarkdown("Android&#x8BBE;&#x5907;"))
    assertEquals("Android设备", MarkdownHelper.asMarkdown("Android\\u8bbe\\u5907"))
    assertEquals("Android设备", MarkdownHelper.asMarkdown("Android\\uuuu8bbe\\uuuu5907"))
    assertEquals("&#1114112;", MarkdownHelper.asMarkdown("&#1114112;"))
  }

  @Test
  fun extractsOnlyKDocAdjacentToTheResolvedDeclaration() {
    val documented =
      """
      /**
       * Kotlin documentation.
       *
       * @param value input value
       * @return projected result
       */
      fun documented(value: String): String = value
      """.trimIndent()
    val declaration = documented.indexOf("fun documented")

    assertEquals(
      "Kotlin documentation.\n\n@param value input value\n@return projected result",
      JavaHoverProvider.extractAdjacentKDoc(documented, declaration),
    )

    val annotated =
      """
      /** KDoc belongs to the annotation boundary. */
      @Deprecated("test")
      fun annotated() = Unit
      """.trimIndent()
    assertEquals("", JavaHoverProvider.extractAdjacentKDoc(annotated, annotated.indexOf("fun annotated")))

    val previousDeclaration =
      """
      /** First declaration docs. */
      fun first() = Unit

      fun second() = Unit
      """.trimIndent()
    assertEquals("", JavaHoverProvider.extractAdjacentKDoc(
      previousDeclaration, previousDeclaration.indexOf("fun second")))
  }

  private fun compileType(source: String, qualifiedName: String): TypeElement {
    val task =
      JavacTool.create()
        .getTask(
          null,
          null,
          null,
          emptyList<String>(),
          emptyList<String>(),
          listOf(FakeSourceFile(source)),
        ) as JavacTask
    task.parse()
    task.analyze()
    return task.elements.getTypeElement(qualifiedName) as TypeElement
  }

  private class FakeSourceFile(private val source: String) :
    SimpleJavaFileObject(URI.create("string:///HoverFixture.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
  }
}
