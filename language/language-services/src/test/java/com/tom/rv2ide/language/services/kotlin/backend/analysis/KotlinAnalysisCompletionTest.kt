package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.diskRequest
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.InsertTextFormat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisCompletionTest {

  @Test
  fun dotCompletionUsesReceiverMembersOnly() {
    verifyCompletion(
        "CompletionDot.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class SampleBox {
              val name: String = "x"
              fun run() {}
            }

            fun unrelatedHelper() {}

            fun useSampleBox(box: SampleBox) {
              box.
            }
        """,
        "box.",
    ) { labels ->
      assertTrue(labels.contains("name"))
      assertTrue(labels.contains("run"))
      assertFalse(labels.contains("unrelatedHelper"))
    }
  }

  @Test
  fun dotCompletionFiltersByPrefix() {
    verifyCompletion(
        "CompletionPrefix.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class PrefixBox {
              val name: String = "x"
              fun run() {}
            }

            fun usePrefixBox(box: PrefixBox) {
              box.na
            }
        """,
        "box.na",
    ) { labels ->
      assertTrue(labels.contains("name"))
      assertFalse(labels.contains("run"))
    }
  }

  @Test
  fun safeCallCompletionOnNullableReceiver() {
    verifyCompletion(
        "CompletionSafeCall.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class SafeBox {
              val name: String = "x"
              fun run() {}
            }

            fun useSafeBox(box: SafeBox?) {
              box?.
            }
        """,
        "box?.",
    ) { labels ->
      assertTrue(labels.contains("name"))
      assertTrue(labels.contains("run"))
    }
  }

  @Test
  fun dotOnNullableReceiverSkipsMembers() {
    verifyCompletion(
        "CompletionNullableDot.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class NullableBox {
              val name: String = "x"
            }

            fun useNullableBox(box: NullableBox?) {
              box.
            }
        """,
        "box.",
    ) { labels ->
      assertFalse(labels.contains("name"))
    }
  }

  @Test
  fun chainedReceiverCompletion() {
    verifyCompletion(
        "CompletionChain.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class ChainSample {
              val name: String = "x"
              fun run() {}
            }

            class ChainHolder(val sample: ChainSample)

            fun useChainHolder(holder: ChainHolder) {
              holder.sample.
            }
        """,
        "holder.sample.",
    ) { labels ->
      assertTrue(labels.contains("name"))
      assertTrue(labels.contains("run"))
    }
  }

  @Test
  fun stringReceiverCompletion() {
    verifyCompletion(
        "CompletionStringReceiver.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            fun useStringReceiver() {
              "text".
            }
        """,
        "\"text\".",
    ) { labels ->
      assertTrue(labels.contains("length"))
    }
  }

  @Test
  fun implicitItReceiverCompletion() {
    verifyCompletion(
        "CompletionImplicitIt.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            fun useImplicitIt(values: List<String>) {
              values.forEach { it. }
            }
        """,
        "it.",
    ) { labels ->
      assertTrue(labels.contains("length"))
    }
  }

  @Test
  fun invalidReceiverCompletionReturnsEmpty() {
    verifyCompletion(
        "CompletionInvalidReceiver.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            fun useInvalidReceiver() {
              missingReceiver.
            }
        """,
        "missingReceiver.",
    ) { labels ->
      assertTrue(labels.isEmpty())
    }
  }

  @Test
  fun lexicalCompletionStillWorks() {
    val content = """
        package com.tom.rv2ide.testing.kotlinfixture.app

        fun useLexical() {
          val localValue = 1
          loc
        }
    """.trimIndent() + "\n"
    val offset = content.indexOf("loc\n")
    assertTrue(offset >= 0)
    verifyCompletionAtOffset("CompletionLexical.kt", content, offset + "loc".length) { items ->
      assertTrue(items.any { it.ideLabel == "localValue" })
    }
  }

  @Test
  fun extensionFunctionOnExplicitReceiver() {
    verifyCompletion(
        "CompletionExtension.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class ExtBox {
              val name: String = "x"
            }

            fun ExtBox.extValue(): String = name

            fun useExtBox(box: ExtBox) {
              box.extV
            }
        """,
        "box.extV",
    ) { labels, items ->
      assertTrue(labels.contains("extValue"))
      val item = items.single { it.ideLabel == "extValue" }
      assertEquals("extValue(\$0)", item.insertText)
      assertEquals(Command.TRIGGER_PARAMETER_HINTS, item.command?.command)
    }
  }

  @Test
  fun extensionOnSupertypeIsApplicable() {
    verifyCompletion(
        "CompletionExtensionSupertype.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            interface ExtBase {
              val name: String
            }

            class ExtImpl : ExtBase {
              override val name: String = "x"
            }

            fun ExtBase.baseExt(): String = name

            fun useExtImpl(box: ExtImpl) {
              box.baseE
            }
        """,
        "box.baseE",
    ) { labels, _ ->
      assertTrue(labels.contains("baseExt"))
    }
  }

  @Test
  fun extensionWithOtherReceiverTypeIsExcluded() {
    verifyCompletion(
        "CompletionExtensionExcluded.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class ExtBox {
              val name: String = "x"
            }

            fun String.stringOnly(): String = this

            fun useExtBox(box: ExtBox) {
              box.
            }
        """,
        "box.",
    ) { labels, _ ->
      assertTrue(labels.contains("name"))
      assertFalse(labels.contains("stringOnly"))
    }
  }

  @Test
  fun nullableExtensionsMatchSafeCall() {
    verifyCompletion(
        "CompletionExtensionSafeCall.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class ExtBox {
              val name: String = "x"
            }

            fun ExtBox?.nullableExt(): String = ""

            fun ExtBox.nonNullExt(): String = ""

            fun useExtBox(box: ExtBox?) {
              box?.
            }
        """,
        "box?.",
    ) { labels, _ ->
      assertTrue(labels.contains("nullableExt"))
      assertTrue(labels.contains("nonNullExt"))
    }
  }

  @Test
  fun nullableDotKeepsNullableReceiverExtensions() {
    verifyCompletion(
        "CompletionExtensionNullableDot.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class ExtBox {
              val name: String = "x"
            }

            fun ExtBox?.nullableExt(): String = ""

            fun useExtBox(box: ExtBox?) {
              box.
            }
        """,
        "box.",
    ) { labels, _ ->
      assertTrue(labels.contains("nullableExt"))
      assertFalse(labels.contains("name"))
    }
  }

  @Test
  fun genericExtensionOnListReceiverAppears() {
    verifyCompletion(
        "CompletionGenericExtension.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            fun <T> List<T>.secondOrNull(): T? {
              return if (size >= 2) this[1] else null
            }

            fun useGenericExtension() {
              val listi = listOf<Int>(1, 2, 3)
              listi.
            }
        """,
        "listi.",
    ) { labels, items ->
      assertTrue(labels.contains("secondOrNull"))
      val item = items.single { it.ideLabel == "secondOrNull" }
      assertEquals("secondOrNull(\$0)", item.insertText)
    }
  }

  @Test
  fun concreteTypeArgumentExtensionIsExcluded() {
    verifyCompletion(
        "CompletionConcreteExtension.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            fun List<String>.stringListExt(): String = ""

            fun useIntList() {
              val values = listOf<Int>(1)
              values.
            }
        """,
        "values.",
    ) { labels, items ->
      assertTrue(items.isNotEmpty())
      assertFalse(labels.contains("stringListExt"))
    }
  }

  @Test
  fun parameterizedFunctionInsertionDeletesPrefixAndTriggersSignature() {
    verifyCompletion(
        "CompletionInsertionParam.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class InsertParamBox {
              fun combine(other: InsertParamBox): String = ""
            }

            fun useInsertParamBox(box: InsertParamBox) {
              box.co
            }
        """,
        "box.co",
    ) { _, items ->
      val item = items.single { it.ideLabel == "combine" }
      assertEquals("combine(\$0)", item.insertText)
      assertEquals(InsertTextFormat.SNIPPET, item.insertTextFormat)
      assertEquals(Command.TRIGGER_PARAMETER_HINTS, item.command?.command)
      val snippet = requireNotNull(item.snippetDescription)
      assertEquals("co".length, snippet.selectedLength)
      assertTrue(snippet.deleteSelected)
      assertTrue(snippet.allowCommandExecution)
    }
  }

  @Test
  fun zeroParameterFunctionStillUsesCallSnippet() {
    verifyCompletion(
        "CompletionInsertionEmpty.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class InsertEmptyBox {
              fun run() {}
            }

            fun useInsertEmptyBox(box: InsertEmptyBox) {
              box.ru
            }
        """,
        "box.ru",
    ) { _, items ->
      val item = items.single { it.ideLabel == "run" }
      assertEquals("run(\$0)", item.insertText)
      assertEquals(InsertTextFormat.SNIPPET, item.insertTextFormat)
      assertEquals(Command.TRIGGER_PARAMETER_HINTS, item.command?.command)
      val snippet = requireNotNull(item.snippetDescription)
      assertEquals("ru".length, snippet.selectedLength)
      assertTrue(snippet.deleteSelected)
      assertTrue(snippet.allowCommandExecution)
    }
  }

  @Test
  fun overloadSetWithZeroParameterCandidateStillUsesCallSnippet() {
    verifyCompletion(
        "CompletionInsertionOverloads.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class InsertOverloadBox {
              fun pick(): String = ""
              fun pick(value: String): String = value
            }

            fun useInsertOverloadBox(box: InsertOverloadBox) {
              box.pi
            }
        """,
        "box.pi",
    ) { _, items ->
      val item = items.single { it.ideLabel == "pick" }
      assertEquals("pick(\$0)", item.insertText)
      assertEquals(InsertTextFormat.SNIPPET, item.insertTextFormat)
      assertEquals(Command.TRIGGER_PARAMETER_HINTS, item.command?.command)
      assertTrue(requireNotNull(item.snippetDescription).deleteSelected)
    }
  }

  private fun verifyCompletion(
      fixtureName: String,
      body: String,
      marker: String,
      assertions: (List<String>) -> Unit,
  ) {
    verifyCompletion(fixtureName, body, marker) { labels, _ -> assertions(labels) }
  }

  private fun verifyCompletion(
      fixtureName: String,
      body: String,
      marker: String,
      assertions: (List<String>, List<CompletionItem>) -> Unit,
  ) {
    val content = body.trimIndent() + "\n"
    val offset = content.indexOf(marker)
    assertTrue("Missing marker: $marker", offset >= 0)
    verifyCompletionAtOffset(fixtureName, content, offset + marker.length) { items ->
      assertions(items.map { it.ideLabel }, items)
    }
  }

  private fun verifyCompletionAtOffset(
      fixtureName: String,
      content: String,
      offset: Int,
      assertions: (List<CompletionItem>) -> Unit,
  ) {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/$fixtureName")
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val request = completionRequest(file, content, offset)
          val result = requireNotNull(runAnalysis { runtime.complete(request) })
          assertions(result.items)
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  private fun completionRequest(file: Path, content: String, offset: Int): KotlinSemanticRequest =
      diskRequest(file, content, completionPrefix(content, offset), offset = offset)

  private fun completionPrefix(content: String, offset: Int): String {
    val end = offset.coerceIn(0, content.length)
    var start = end
    while (start > 0) {
      val ch = content[start - 1]
      if (ch.isLetterOrDigit() || ch == '_' || ch == '.') start-- else break
    }
    return content.substring(start, end)
  }
}