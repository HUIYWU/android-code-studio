package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.diskRequest
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisHoverDocumentationTest {

  @Test
  fun hoverShowsPrimaryDocumentation() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/HoverPrimaryDocumentation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          /**
           * Adds one to the value.
           */
          fun incrementDoc(value: Int): Int = value + 1

          fun useIncrementDoc(): Int = incrementDoc(1)
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val hover = requireNotNull(runAnalysis {
            runtime.hover(diskRequest(file, content, "incrementDoc(1)"))
          })
          assertTrue(hover.value.contains("Adds one to the value."))
          assertTrue(hover.value.contains("\n\n---\n\n"))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun hoverShowsParameterAndReturnSections() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/HoverSectionsDocumentation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          /**
           * Formats the text.
           *
           * @param input the raw input
           * @return the formatted text
           */
          fun formatDoc(input: String): String = input

          fun useFormatDoc(): String = formatDoc("x")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val hover = requireNotNull(runAnalysis {
            runtime.hover(diskRequest(file, content, "formatDoc(\"x\")"))
          })
          assertTrue(hover.value.contains("Formats the text."))
          assertTrue(hover.value.contains("@param input"))
          assertTrue(hover.value.contains("the raw input"))
          assertTrue(hover.value.contains("@return"))
          assertTrue(hover.value.contains("the formatted text"))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun hoverOnConstructorUsesConstructorSection() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/HoverConstructorDocumentation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          /**
           * A holder of names.
           *
           * @constructor Creates a holder for the given name.
           * @param name the display name
           */
          class HolderDoc(val name: String)

          fun useHolderDoc(): HolderDoc = HolderDoc("x")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val hover = requireNotNull(runAnalysis {
            runtime.hover(diskRequest(file, content, "HolderDoc(\"x\")"))
          })
          assertTrue(hover.value.contains("Creates a holder for the given name."))
          assertTrue(hover.value.contains("@param name"))
          assertTrue(hover.value.contains("the display name"))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun hoverOnOverrideInheritsBaseKdoc() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/HoverOverrideDocumentation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          interface SinkDoc {
            /** Accepts the value. */
            fun accept(value: Int)
          }

          class PrintingSinkDoc : SinkDoc {
            override fun accept(value: Int) {}
          }

          fun useSinkDoc(sink: SinkDoc) {
            sink.accept(1)
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val hover = requireNotNull(runAnalysis {
            runtime.hover(diskRequest(file, content, "accept(1)"))
          })
          assertTrue(hover.value.contains("Accepts the value."))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun hoverOnJavaClassShowsJavadoc() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/HoverJavaDocumentation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useJavaInteropDoc(): JavaInterop = JavaInterop()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val classOffset = content.indexOf(": JavaInterop")
          assertTrue(classOffset >= ": ".length)
          val hover = requireNotNull(runAnalysis {
            runtime.hover(diskRequest(file, content, "JavaInterop", offset = classOffset + ": ".length))
          })
          assertTrue(hover.value.contains("Interop helpers."))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun hoverOnJavaMethodShowsJavadoc() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/HoverJavaMethodDocumentation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useJavaInteropValue(): String = JavaInterop().value("x")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val hover = requireNotNull(runAnalysis {
            runtime.hover(diskRequest(file, content, "value(\"x\")"))
          })
          assertTrue(hover.value.contains("Returns the input unchanged."))
          assertTrue(hover.value.contains("\n\n---\n\n"))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun hoverOnJavaMemberWithoutJavadocKeepsDeclarationOnly() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/HoverJavaRawDocumentation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useJavaInteropRaw(): String = JavaInterop().raw("x")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val hover = requireNotNull(runAnalysis {
            runtime.hover(diskRequest(file, content, "raw(\"x\")"))
          })
          assertTrue(hover.value.endsWith("```"))
          assertFalse(hover.value.contains("---"))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun hoverOnJdkTypeKeepsEmptyDocumentation() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/HoverJdkDocumentation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useJdkList(): Int = java.util.ArrayList<String>().size
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val hover = requireNotNull(runAnalysis {
            runtime.hover(diskRequest(file, content, "ArrayList"))
          })
          assertTrue(hover.value.endsWith("```"))
          assertFalse(hover.value.contains("---"))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun hoverWithoutDocumentationKeepsDeclarationOnly() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/HoverEmptyDocumentation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun plainDoc(value: Int): Int = value + 1

          fun usePlainDoc(): Int = plainDoc(1)
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val hover = requireNotNull(runAnalysis {
            runtime.hover(diskRequest(file, content, "plainDoc(1)"))
          })
          assertTrue(hover.value.endsWith("```"))
          assertFalse(hover.value.contains("\n\n"))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }
}