package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.diskRequest
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisSignatureHelpTest {

  @Test
  fun constructorCallUsesClassNameAndParameterLabels() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/SignatureConstructor.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class Widget(val name: String)

          fun useWidget(): Widget = Widget("w")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val callOffset = content.indexOf("Widget(\"w\")")
          assertTrue(callOffset >= 0)
          val help = requireNotNull(runAnalysis {
            runtime.signatureHelp(diskRequest(file, content, "Widget", offset = callOffset))
          })
          val signature = help.signatures.single()
          assertEquals("Widget(name: String)", signature.label)
          assertEquals("name: String", signature.parameters.single().label)
          assertEquals(0, help.activeParameter)
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun varargParameterLabelUsesElementType() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/SignatureVararg.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun joinAll(vararg parts: String): String = parts.joinToString("")

          fun useJoin(): String = joinAll("a", "b")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val callOffset = content.indexOf("\"b\"")
          assertTrue(callOffset >= 0)
          val help = requireNotNull(runAnalysis {
            runtime.signatureHelp(diskRequest(file, content, "joinAll", offset = callOffset))
          })
          val signature = help.signatures.single()
          assertEquals("joinAll(vararg parts: String)", signature.label)
          assertEquals("vararg parts: String", signature.parameters.single().label)
          assertEquals(0, help.activeParameter)
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun namedArgumentMapsToDeclaredParameterIndex() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/SignatureNamedArgument.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun target(alpha: String, beta: String): String = alpha + beta

          fun useTarget(): String = target(beta = "b", alpha = "a")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val argumentOffset = content.indexOf("beta = ")
          assertTrue(argumentOffset >= 0)
          val help = requireNotNull(runAnalysis {
            runtime.signatureHelp(diskRequest(file, content, "beta", offset = argumentOffset))
          })
          val signature = help.signatures.single()
          assertEquals(listOf("alpha: String", "beta: String"), signature.parameters.map { it.label })
          assertEquals(0, help.activeSignature)
          assertEquals(1, help.activeParameter)
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun activeSignatureFollowsArgumentPositionForOverloads() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/SignatureBestCandidate.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun pick(value: String): String = value
          fun pick(value: String, suffix: String): String = value + suffix

          fun usePick(): String = pick("a", )
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val callOffset = content.indexOf("pick(\"a\",") + "pick(\"a\",".length
          assertTrue(callOffset >= "pick(\"a\",".length)
          val help = requireNotNull(runAnalysis {
            runtime.signatureHelp(diskRequest(file, content, "pick", offset = callOffset))
          })
          val labels = help.signatures.map { it.label }
          assertTrue(labels.contains("pick(value: String)"))
          assertTrue(labels.contains("pick(value: String, suffix: String)"))
          assertTrue(help.activeSignature in help.signatures.indices)
          assertEquals(
              "pick(value: String, suffix: String)",
              help.signatures[help.activeSignature].label,
          )
          assertEquals(1, help.activeParameter)
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun extensionReceiverPrefixesSignatureLabel() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/SignatureExtension.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun String.shout(): String = uppercase()

          fun useShout(): String = "x".shout()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val callOffset = content.lastIndexOf("shout()")
          assertTrue(callOffset >= 0)
          val help = requireNotNull(runAnalysis {
            runtime.signatureHelp(diskRequest(file, content, "shout", offset = callOffset))
          })
          val signature = help.signatures.single()
          assertEquals("String.shout()", signature.label)
          assertTrue(signature.parameters.isEmpty())
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun unresolvedCallProducesEmptySignatureHelp() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/SignatureUnresolved.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useMissing(): String = missingFunction("a")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val callOffset = content.indexOf("missingFunction(")
          assertTrue(callOffset >= 0)
          val help = runAnalysis {
            runtime.signatureHelp(diskRequest(file, content, "missingFunction", offset = callOffset))
          }
          assertNotNull(help)
          assertTrue(requireNotNull(help).signatures.isEmpty())
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun lambdaBodyProducesEmptySignatureHelp() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/SignatureLambdaBody.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun invokeWith(block: () -> Unit) {}

          fun useLambda() {
            invokeWith {
              val value = 1
            }
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val bodyOffset = content.indexOf("val value") + "val ".length
          assertTrue(bodyOffset >= "val ".length)
          val help = runAnalysis {
            runtime.signatureHelp(diskRequest(file, content, "value", offset = bodyOffset))
          }
          assertNotNull(help)
          assertTrue(requireNotNull(help).signatures.isEmpty())
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }
}
