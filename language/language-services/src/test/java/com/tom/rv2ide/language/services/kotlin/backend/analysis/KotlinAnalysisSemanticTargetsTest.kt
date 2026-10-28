package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.diskRequest
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisSemanticTargetsTest {

  @Test
  fun ordinaryDeclarationUsageAndCaretBoundaries() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/SemanticTargets.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class SemanticTargets {
            val targetValue = 1

            fun use(): Int {
              val usage = targetValue
              // targetValue
              val text = "targetValue"
              return usage
            }
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }

          val declarationRequest = diskRequest(file, content, "targetValue")
          val declaration = requireNotNull(runAnalysis { runtime.findDefinition(declarationRequest) })
          assertEquals(file, declaration.locations.single().file)
          assertEquals(
              content.indexOf("targetValue"),
              offsetOfLocation(content, declaration.locations.single().range.start.line, declaration.locations.single().range.start.column),
          )

          val usageOffset = content.offsetOf("targetValue", 1)
          val usageRequest = diskRequest(file, content, "targetValue", occurrence = 1)
          val usageDefinition = requireNotNull(runAnalysis { runtime.findDefinition(usageRequest) })
          assertEquals(file, usageDefinition.locations.single().file)
          assertEquals(
              content.indexOf("targetValue"),
              offsetOfLocation(content, usageDefinition.locations.single().range.start.line, usageDefinition.locations.single().range.start.column),
          )

          assertTrue(runAnalysis { runtime.hover(usageRequest) } != null)
          assertEquals(null, runAnalysis {
            runtime.hover(diskRequest(file, content, "targetValue", offset = usageOffset + "targetValue".length))
          })
          assertEquals(null, runAnalysis {
            runtime.hover(diskRequest(file, content, "targetValue", occurrence = 2))
          })
          assertEquals(null, runAnalysis {
            runtime.hover(diskRequest(file, content, "targetValue", occurrence = 3))
          })
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  private fun String.offsetOf(marker: String, occurrence: Int): Int {
    var fromIndex = 0
    repeat(occurrence + 1) {
      fromIndex = indexOf(marker, fromIndex)
      check(fromIndex >= 0) { "Missing marker occurrence: $marker#$occurrence" }
      if (it < occurrence) fromIndex += marker.length
    }
    return fromIndex
  }

  private fun offsetOfLocation(content: String, line: Int, column: Int): Int =
      com.tom.rv2ide.lsp.models.LineIndex.from(content).lineColumnToIndex(line, column)
}
