package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.diskRequest
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.LineIndex
import java.nio.file.Files
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisDefinitionTargetTest {

  @Test
  fun definitionOnSyntheticEnumValuesTargetsEnumClass() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/DefinitionSynthetic.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          enum class ColorToken { RED, GREEN }

          fun useColorToken(): ColorToken = ColorToken.values().first()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "values()"))
          })
          val location = definition.locations.single()
          assertEquals(file, location.file)
          val classNameStart = content.indexOf("enum class ColorToken") + "enum class ".length
          assertTrue(classNameStart >= "enum class ".length)
          assertEquals(
              classNameStart,
              LineIndex.from(content).lineColumnToIndex(location.range.start.line, location.range.start.column),
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun definitionOnAccessorKeywordTargetsProperty() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/DefinitionAccessor.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class AccessorBox {
            var value: Int = 0
              get() = field + 1
              set(newValue) {
                field = newValue
              }
          }

          fun useAccessorBox(box: AccessorBox) {
            box.value = 1
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val getOffset = content.indexOf("get()")
          assertTrue(getOffset >= 0)
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "get()", offset = getOffset))
          })
          val location = definition.locations.single()
          assertEquals(file, location.file)
          val propertyNameStart = content.indexOf("var value") + "var ".length
          assertTrue(propertyNameStart >= "var ".length)
          assertEquals(
              propertyNameStart,
              LineIndex.from(content).lineColumnToIndex(location.range.start.line, location.range.start.column),
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun definitionOnJavaSourceMethodTargetsJavaFile() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/DefinitionJavaSource.kt")
      val javaFile = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useJavaInteropMethod(): String = JavaInterop().value("x")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "value(\"x\")"))
          })
          val location = definition.locations.single()
          assertEquals(javaFile, location.file)
          val javaContent = javaFile.readText()
          val methodNameStart = javaContent.indexOf("value(")
          assertTrue(methodNameStart >= 0)
          assertEquals(
              methodNameStart,
              LineIndex.from(javaContent).lineColumnToIndex(location.range.start.line, location.range.start.column),
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun definitionOnLibraryTypeReturnsEmptyResult() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/DefinitionLibrary.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useStdlibQueue(): ArrayDeque<String> = ArrayDeque()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(file, content, "ArrayDeque"))
          })
          assertTrue(definition.locations.isEmpty())
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }
}