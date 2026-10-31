package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.diskRequest
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import java.nio.file.Files
import java.nio.file.Path
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
  fun dotOnNullableReceiverReturnsEmpty() {
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
      assertTrue(labels.isEmpty())
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
    verifyCompletionAtOffset("CompletionLexical.kt", content, offset + "loc".length) { labels ->
      assertTrue(labels.contains("localValue"))
    }
  }

  private fun verifyCompletion(
      fixtureName: String,
      body: String,
      marker: String,
      assertions: (List<String>) -> Unit,
  ) {
    val content = body.trimIndent() + "\n"
    val offset = content.indexOf(marker)
    assertTrue("Missing marker: $marker", offset >= 0)
    verifyCompletionAtOffset(fixtureName, content, offset + marker.length, assertions)
  }

  private fun verifyCompletionAtOffset(
      fixtureName: String,
      content: String,
      offset: Int,
      assertions: (List<String>) -> Unit,
  ) {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/$fixtureName")
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val request = completionRequest(file, content, offset)
          val result = requireNotNull(runAnalysis { runtime.complete(request) })
          assertions(result.items.map { it.ideLabel })
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  private fun completionRequest(file: Path, content: String, offset: Int): KotlinSemanticRequest =
      diskRequest(file, content, trailingIdentifier(content, offset), offset = offset)

  private fun trailingIdentifier(content: String, offset: Int): String {
    val end = offset.coerceIn(0, content.length)
    var start = end
    while (start > 0 && (content[start - 1].isLetterOrDigit() || content[start - 1] == '_')) start--
    return content.substring(start, end)
  }
}