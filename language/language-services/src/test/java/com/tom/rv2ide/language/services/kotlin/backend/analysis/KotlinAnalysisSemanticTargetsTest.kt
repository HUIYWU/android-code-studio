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
  fun arrayAccessResolvesGetOperatorOnBracketRange() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ArrayAccessTargets.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class Box {
            operator fun get(index: Int): String = index.toString()
          }

          fun use(box: Box): String = box[0]
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val bracketOffset = content.indexOf("[0]")
          assertTrue(bracketOffset >= 0)
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "[0]", offset = bracketOffset))
          })
          val location = definition.locations.single()
          assertEquals(file, location.file)
          val nameStart = content.indexOf("get(index")
          assertTrue(nameStart >= 0)
          val getStart = nameStart
          assertEquals(
              offsetOfLocation(content, location.range.start.line, location.range.start.column),
              getStart,
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun operatorExpressionResolvesPlusOperatorOnTokenRange() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/OperatorTargets.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class NumberBox {
            operator fun plus(other: NumberBox): NumberBox = this
          }

          fun use(left: NumberBox, right: NumberBox): NumberBox = left + right
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val operatorOffset = content.indexOf(" + ") + 1
          assertTrue(operatorOffset >= 0)
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "+", offset = operatorOffset))
          })
          val location = definition.locations.single()
          assertEquals(file, location.file)
          val plusStart = content.indexOf("plus(other")
          assertTrue(plusStart >= 0)
          assertEquals(
              offsetOfLocation(content, location.range.start.line, location.range.start.column),
              plusStart,
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun invokeCallResolvesInvokeOperatorOnParenthesisRange() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/InvokeTargets.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class CallableBox {
            operator fun invoke(value: Int): String = value.toString()
          }

          fun use(box: CallableBox): String = box(0)
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val callOffset = content.indexOf("box(0)") + "box".length
          assertTrue(callOffset >= "box".length)
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "(", offset = callOffset))
          })
          val location = definition.locations.single()
          assertEquals(file, location.file)
          val invokeStart = content.indexOf("invoke(value")
          assertTrue(invokeStart >= 0)
          assertEquals(
              offsetOfLocation(content, location.range.start.line, location.range.start.column),
              invokeStart,
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun destructuringEntryResolvesComponentOperator() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/DestructuringTargets.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class PairBox(private val value: String) {
            operator fun component1(): String = value
          }

          fun use(box: PairBox): String {
            val (first) = box
            return first
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "first"))
          })
          val location = definition.locations.single()
          assertEquals(file, location.file)
          val componentStart = content.indexOf("component1()")
          assertTrue(componentStart >= 0)
          assertEquals(
              offsetOfLocation(content, location.range.start.line, location.range.start.column),
              componentStart,
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun forLoopInKeepsIteratorConventionTargetsAmbiguous() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ForLoopTargets.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class LoopRange {
            operator fun iterator(): LoopIterator = LoopIterator()
          }

          class LoopIterator {
            operator fun hasNext(): Boolean = false
            operator fun next(): Int = 0
          }

          fun use(range: LoopRange): Int {
            for (item in range) {
              return item
            }
            return 0
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val inOffset = content.indexOf(" in ") + 1
          assertTrue(inOffset >= 0)
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "in", offset = inOffset))
          })
          assertEquals(0, definition.locations.size)
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun propertyDelegateResolvesGetValueOnByRange() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/PropertyDelegateTargets.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class Delegate {
            operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String = "value"
          }

          fun use(): String {
            val value by Delegate()
            return value
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val byOffset = content.indexOf(" by ") + 1
          assertTrue(byOffset >= 0)
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "by", offset = byOffset))
          })
          val location = definition.locations.single()
          assertEquals(file, location.file)
          val getValueStart = content.indexOf("getValue(")
          assertTrue(getValueStart >= 0)
          assertEquals(
              offsetOfLocation(content, location.range.start.line, location.range.start.column),
              getValueStart,
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun compoundAssignmentResolvesPlusAssignOnOperatorToken() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/CompoundAssignmentTargets.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class Counter {
            operator fun plusAssign(other: Counter) {}
          }

          fun use(left: Counter, right: Counter) {
            left += right
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val operatorOffset = content.indexOf(" += ") + 1
          assertTrue(operatorOffset >= 0)
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "+=", offset = operatorOffset))
          })
          val location = definition.locations.single()
          assertEquals(file, location.file)
          val plusAssignStart = content.indexOf("plusAssign(")
          assertTrue(plusAssignStart >= 0)
          assertEquals(
              offsetOfLocation(content, location.range.start.line, location.range.start.column),
              plusAssignStart,
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun compoundAssignmentPlusUsesOnlyResolvedOperationTarget() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/CompoundPlusTargets.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class Counter {
            operator fun plus(other: Counter): Counter = this
          }

          fun use(initial: Counter, right: Counter) {
            var left = initial
            left += right
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val operatorOffset = content.indexOf(" += ") + 1
          assertTrue(operatorOffset >= 0)
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "+=", offset = operatorOffset))
          })
          val location = definition.locations.single()
          assertEquals(file, location.file)
          val plusStart = content.indexOf("plus(other")
          assertTrue(plusStart >= 0)
          assertEquals(
              offsetOfLocation(content, location.range.start.line, location.range.start.column),
              plusStart,
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

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
