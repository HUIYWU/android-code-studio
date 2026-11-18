package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.language.services.java.providers.completion.KotlinSourceCompletion
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.lsp.models.ClassCompletionData
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.FieldCompletionData
import com.tom.rv2ide.lsp.models.MethodCompletionData
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.progress.ICancelChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiCompletionCapabilityTest {

  @Test
  fun kotlinMembersExposeJavaVisibleMembers() {
    KotlinAnalysisTestSupport.withWorkspace {
      val javaPath = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
      )
      val owner = "com.tom.rv2ide.testing.kotlinfixture.library.InteropOverloads"
      val facade = "com.tom.rv2ide.testing.kotlinfixture.library.LibraryApiKt"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import com.tom.rv2ide.testing.kotlinfixture.library.InteropOverloads;

          final class JavaInterop {
            String read() {
              InteropOverloads api = new InteropOverloads("label");
              return api.dec;
            }
          }
      """.trimIndent()
      openDocument(javaPath, content, version = 1, revision = 51L)
      KotlinAnalysisTestSupport.withRuntime(workspace) {
        val offset = content.indexOf("api.dec") + "api.dec".length
        val instanceMembers = KotlinAnalysisTestSupport.runAnalysis {
          JavaPsiInterop.kotlinMembers(javaPath, offset, owner, false, "", true, ICancelChecker.NOOP)
        }
        val instanceLabels = instanceMembers.map { it.ideLabel }
        assertEquals(
            "duplicate instance items: $instanceLabels",
            instanceMembers.size,
            instanceMembers.map { it.ideLabel to it.completionKind }.toSet().size,
        )
        assertTrue("labels=$instanceLabels", instanceLabels.containsAll(listOf("pick", "getLabel", "decorated")))
        assertFalse("labels=$instanceLabels", "label" in instanceLabels)
        assertFalse("labels=$instanceLabels", "hidden" in instanceLabels)
        assertFalse("labels=$instanceLabels", "InteropOverloads" in instanceLabels)
        assertFalse("labels=$instanceLabels", "TOKEN" in instanceLabels)

        val decorated = instanceMembers.item("decorated")
        assertEquals(CompletionItemKind.METHOD, decorated.completionKind)
        assertEquals("decorated(String, String)", decorated.detail)
        assertEquals("decorated($0)", decorated.insertText)
        assertEquals(InsertTextFormat.SNIPPET, decorated.insertTextFormat)
        val decoratedData = decorated.data as MethodCompletionData
        assertEquals("InteropOverloads", decoratedData.classInfo.simpleName)
        assertEquals(listOf("String", "String"), decoratedData.parameterTypes)
        assertEquals(listOf("java.lang.String", "java.lang.String"), decoratedData.erasedParameterTypes)
        assertEquals(0, decoratedData.plusOverloads)

        val getLabel = instanceMembers.item("getLabel")
        assertEquals("getLabel()", getLabel.detail)
        assertEquals("getLabel()\$0", getLabel.insertText)
        assertEquals(0, (getLabel.data as MethodCompletionData).parameterTypes.size)

        val pick = instanceMembers.item("pick")
        assertEquals(1, (pick.data as MethodCompletionData).plusOverloads)

        val staticMembers = KotlinAnalysisTestSupport.runAnalysis {
          JavaPsiInterop.kotlinMembers(javaPath, offset, owner, true, "", true, ICancelChecker.NOOP)
        }
        val staticLabels = staticMembers.map { it.ideLabel }
        assertEquals(
            "duplicate static items: $staticLabels",
            staticMembers.size,
            staticMembers.map { it.ideLabel to it.completionKind }.toSet().size,
        )
        assertTrue("labels=$staticLabels", staticLabels.containsAll(listOf("Companion", "Nested", "TOKEN")))
        assertFalse("labels=$staticLabels", "decorated" in staticLabels)
        assertFalse("labels=$staticLabels", "hidden" in staticLabels)

        val companionField = staticMembers.item("Companion", CompletionItemKind.FIELD)
        assertEquals("Companion", (companionField.data as FieldCompletionData).memberName)

        val tokenField = staticMembers.item("TOKEN", CompletionItemKind.FIELD)
        assertEquals("String", tokenField.detail)
        assertEquals("TOKEN", (tokenField.data as FieldCompletionData).memberName)

        val fieldLabels =
            staticMembers.filter { it.completionKind == CompletionItemKind.FIELD }.map { it.ideLabel }.toSet()
        assertEquals(setOf("Companion", "TOKEN"), fieldLabels)

        val nested = staticMembers.item("Nested")
        assertEquals(CompletionItemKind.CLASS, nested.completionKind)
        assertTrue((nested.data as ClassCompletionData).isNested)

        val facadeMembers = KotlinAnalysisTestSupport.runAnalysis {
          JavaPsiInterop.kotlinMembers(javaPath, offset, facade, true, "library", true, ICancelChecker.NOOP)
        }
        val facadeMethod =
            facadeMembers.singleOrNull()
                ?: throw AssertionError("Facade members=${facadeMembers.map { it.ideLabel }}")
        assertEquals("libraryExtension", facadeMethod.ideLabel)
        assertEquals("libraryExtension(String)", facadeMethod.detail)

        val javaMembers = KotlinAnalysisTestSupport.runAnalysis {
          JavaPsiInterop.kotlinMembers(javaPath, offset, "java.util.ArrayList", false, "", true, ICancelChecker.NOOP)
        }
        assertTrue(javaMembers.isEmpty())

        val innerClasses = KotlinAnalysisTestSupport.runAnalysis {
          JavaPsiInterop.kotlinInnerClasses(javaPath, offset, owner, "", ICancelChecker.NOOP)
        }
        val innerLabels = innerClasses.map { it.ideLabel }
        assertTrue("labels=$innerLabels", "Inner" in innerLabels)
        assertFalse("labels=$innerLabels", "Nested" in innerLabels)
        assertFalse("labels=$innerLabels", "Companion" in innerLabels)
        val inner = innerClasses.item("Inner")
        assertEquals(CompletionItemKind.CLASS, inner.completionKind)
        val innerData = inner.data as ClassCompletionData
        assertTrue(innerData.isNested)
        assertEquals("$owner.Inner", innerData.className)
      }
    }
  }

  @Test
  fun kotlinOwnerRecognitionIsPsiOnlyAndRespectsThePreference() {
    KotlinAnalysisTestSupport.withWorkspace {
      val javaPath = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
      )
      val content = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaInterop {}"
      openDocument(javaPath, content, version = 1, revision = 52L)
      KotlinAnalysisTestSupport.withRuntime(workspace) {
        val previous = JavaPreferences.isJavaKotlinRecognitionEnabled
        try {
          JavaPreferences.isJavaKotlinRecognitionEnabled = true
          val offset = (content.length - 1).coerceAtLeast(0)
          assertTrue(
              KotlinSourceCompletion.isKotlinOwner(
                  javaPath,
                  offset.toLong(),
                  "com.tom.rv2ide.testing.kotlinfixture.library.InteropOverloads",
                  ICancelChecker.NOOP,
              ),
          )
          assertFalse(
              KotlinSourceCompletion.isKotlinOwner(
                  javaPath,
                  offset.toLong(),
                  "java.util.ArrayList",
                  ICancelChecker.NOOP,
              ),
          )
          JavaPreferences.isJavaKotlinRecognitionEnabled = false
          assertFalse(
              KotlinSourceCompletion.isKotlinOwner(
                  javaPath,
                  offset.toLong(),
                  "com.tom.rv2ide.testing.kotlinfixture.library.InteropOverloads",
                  ICancelChecker.NOOP,
              ),
          )
        } finally {
          JavaPreferences.isJavaKotlinRecognitionEnabled = previous
        }
      }
      closeDocument(javaPath)
    }
  }

  @Test
  fun constructorInsertionAddsNewPrefixAndParameterHints() {
    val item = CompletionItem().apply {
      ideLabel = "Inner"
      completionKind = CompletionItemKind.CLASS
    }
    KotlinSourceCompletion.applyConstructorInsertion(listOf(item), "In", "new ")
    assertEquals("new Inner($0)", item.insertText)
    assertEquals(InsertTextFormat.SNIPPET, item.insertTextFormat)
    assertEquals(Command.TRIGGER_PARAMETER_HINTS, item.command?.command)
    assertEquals(2, item.snippetDescription!!.selectedLength)
  }

  @Test
  fun mergeKeepsJavacItemsAndAppendsMissingKotlinMembers() {
    val existing = mutableListOf<CompletionItem>().apply {
      add(CompletionItem().apply {
        ideLabel = "pick"
        completionKind = CompletionItemKind.METHOD
        detail = "javac"
      })
    }
    val duplicate = CompletionItem().apply {
      ideLabel = "pick"
      completionKind = CompletionItemKind.METHOD
      detail = "kotlin"
    }
    val decorated = CompletionItem().apply {
      ideLabel = "decorated"
      completionKind = CompletionItemKind.METHOD
    }
    KotlinSourceCompletion.merge(existing, listOf(duplicate, decorated))
    assertEquals(2, existing.size)
    assertEquals("javac", existing.item("pick").detail)
    assertSame(decorated, existing.item("decorated"))
  }

  private fun List<CompletionItem>.item(label: String): CompletionItem =
      firstOrNull { it.ideLabel == label }
          ?: throw AssertionError("Missing '$label' in ${map { it.ideLabel }}")

  private fun List<CompletionItem>.item(label: String, kind: CompletionItemKind): CompletionItem =
      firstOrNull { it.ideLabel == label && it.completionKind == kind }
          ?: throw AssertionError(
              "Missing $kind '$label' in ${map { "${it.ideLabel}:${it.completionKind}" }}",
          )
}