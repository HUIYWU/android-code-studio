package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.diskRequest
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
  fun importPackageCompletionUsesWorkspacePackages() {
    verifyCompletion(
        "CompletionImportPackage.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            import com.tom.rv2ide.testing.kotlinfixture.libr
        """,
        "kotlinfixture.libr",
    ) { labels, items ->
      assertTrue(labels.contains("library"))
      val item = items.single { it.ideLabel == "library" }
      assertEquals(CompletionItemKind.MODULE, item.completionKind)
      assertEquals("library", item.insertText)
    }
  }

  @Test
  fun importClassCompletionUsesWorkspaceClassifiers() {
    verifyCompletion(
        "CompletionImportClass.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            import com.tom.rv2ide.testing.kotlinfixture.library.Lib
        """,
        "library.Lib",
    ) { labels, items ->
      assertTrue(labels.contains("LibraryApi"))
      val item = items.single { it.ideLabel == "LibraryApi" }
      assertEquals(CompletionItemKind.CLASS, item.completionKind)
      assertEquals("LibraryApi", item.insertText)
    }
  }

  @Test
  fun importCompletionIncludesBinaryClasspathClasses() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ImportBinaryUse.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import java.util.Arr
          import kotlin.collections.Abs

          fun useBinaryImports() {}
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val bootCaret = content.indexOf("java.util.Arr") + "java.util.Arr".length
          val bootHelp = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, bootCaret))
          })
          val bootLabels = bootHelp.items.map { it.ideLabel }
          assertTrue("labels=$bootLabels", bootLabels.contains("ArrayList"))
          val arrayList = bootHelp.items.single { it.ideLabel == "ArrayList" }
          assertEquals(CompletionItemKind.CLASS, arrayList.completionKind)
          assertEquals("java.util.ArrayList", arrayList.detail)
          assertEquals("ArrayList", arrayList.insertText)

          val libraryCaret = content.indexOf("kotlin.collections.Abs") + "kotlin.collections.Abs".length
          val libraryHelp = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, libraryCaret))
          })
          val libraryLabels = libraryHelp.items.map { it.ideLabel }
          assertTrue("labels=$libraryLabels", libraryLabels.contains("AbstractList"))
          val abstractList = libraryHelp.items.single { it.ideLabel == "AbstractList" }
          assertEquals(CompletionItemKind.CLASS, abstractList.completionKind)
          assertEquals("kotlin.collections.AbstractList", abstractList.detail)
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun classCompletionInExpressionUsesConstructorSnippet() {
    verifyCompletion(
        "CompletionConstructorExpression.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class ConstructorWidget(val name: String)

            fun useConstructorWidget(): ConstructorWidget {
              return Con
            }
        """,
        "return Con",
    ) { labels, items ->
      assertTrue("labels=$labels", labels.contains("ConstructorWidget"))
      val item = items.single { it.ideLabel == "ConstructorWidget" }
      assertEquals(CompletionItemKind.CLASS, item.completionKind)
      assertEquals("ConstructorWidget(\$0)", item.insertText)
      assertEquals(InsertTextFormat.SNIPPET, item.insertTextFormat)
      assertEquals(Command.TRIGGER_PARAMETER_HINTS, item.command?.command)
    }
  }

  @Test
  fun classCompletionInTypePositionKeepsPlainName() {
    verifyCompletion(
        "CompletionConstructorTypePosition.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class ConstructorTypeWidget(val name: String)

            fun useConstructorTypePosition(value: Con): Unit = Unit
        """,
        "value: Con",
    ) { labels, items ->
      assertTrue(labels.contains("ConstructorTypeWidget"))
      val item = items.single { it.ideLabel == "ConstructorTypeWidget" }
      assertEquals(CompletionItemKind.CLASS, item.completionKind)
      assertEquals("ConstructorTypeWidget", item.insertText)
      assertEquals(InsertTextFormat.PLAIN_TEXT, item.insertTextFormat)
      assertNull(item.command)
    }
  }

  @Test
  fun typeAliasCompletionKeepsTypeAliasDetail() {
    verifyCompletion(
        "CompletionTypeAlias.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            typealias Text = String

            fun useTypeAlias(): Te = ""
        """,
        "): Te",
    ) { labels, items ->
      assertTrue(labels.contains("Text"))
      val item = items.single { it.ideLabel == "Text" }
      assertEquals(CompletionItemKind.CLASS, item.completionKind)
      assertEquals("Text", item.insertText)
      assertTrue(item.detail.contains("typealias"))
    }
  }

  @Test
  fun annotationClassCompletionUsesAnnotationKind() {
    verifyCompletion(
        "CompletionAnnotation.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            annotation class Marker

            fun useAnnotation(value: Mar): Unit = Unit
        """,
        "value: Mar",
    ) { labels, items ->
      assertTrue(labels.contains("Marker"))
      val item = items.single { it.ideLabel == "Marker" }
      assertEquals(CompletionItemKind.ANNOTATION_TYPE, item.completionKind)
      assertEquals("Marker", item.insertText)
    }
  }

  @Test
  fun enumEntryCompletionUsesEnumMemberKind() {
    verifyCompletion(
        "CompletionEnumEntry.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            enum class Color { RED, GREEN }

            fun useColor(): Color = Color.R
        """,
        "Color.R",
    ) { labels, items ->
      assertTrue(labels.contains("RED"))
      assertFalse(labels.contains("GREEN"))
      val item = items.single { it.ideLabel == "RED" }
      assertEquals(CompletionItemKind.ENUM_MEMBER, item.completionKind)
      assertEquals("RED", item.insertText)
    }
  }

  @Test
  fun typeParameterCompletionUsesTypeParameterKind() {
    verifyCompletion(
        "CompletionTypeParameter.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            fun <Element> useElement(value: Element): Element = Ele
        """,
        "Ele",
    ) { labels, items ->
      assertTrue(labels.contains("Element"))
      val item = items.single { it.ideLabel == "Element" }
      assertEquals(CompletionItemKind.TYPE_PARAMETER, item.completionKind)
      assertEquals("Element", item.insertText)
    }
  }

  @Test
  fun unimportedClassifierAddsImportEdit() {
    withWorkspace {
      val extraDir = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/extra")
      val extraFile = extraDir.resolve("ImportCandidate.kt")
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedUse.kt")
      val extraContent = """
          package com.tom.rv2ide.testing.kotlinfixture.app.extra

          class UnimportedWidget
      """.trimIndent() + "\n"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useUnimported(): Unimp = TODO()
      """.trimIndent() + "\n"
      Files.createDirectories(extraDir)
      Files.write(extraFile, extraContent.toByteArray(Charsets.UTF_8))
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf(": Unimp") + ": Unimp".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          val item = help.items.single { it.ideLabel == "UnimportedWidget" }
          assertEquals(CompletionItemKind.CLASS, item.completionKind)
          val edit = requireNotNull(item.additionalTextEdits).single()
          assertTrue(edit.newText.contains("import com.tom.rv2ide.testing.kotlinfixture.app.extra.UnimportedWidget"))
        }
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(extraFile)
        Files.deleteIfExists(extraDir)
      }
    }
  }

  @Test
  fun unimportedBinaryClassAddsImportEdit() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedBinaryUse.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useBinaryType(): TreeM = TODO()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf(": TreeM") + ": TreeM".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          val labels = help.items.map { it.ideLabel }
          assertTrue("labels=$labels", labels.contains("TreeMap"))
          val item = help.items.single { it.ideLabel == "TreeMap" }
          assertEquals(CompletionItemKind.CLASS, item.completionKind)
          assertEquals("TreeMap", item.insertText)
          assertEquals("java.util.TreeMap", item.detail)
          assertTrue(
              requireNotNull(item.additionalTextEdits).single().newText
                  .contains("import java.util.TreeMap")
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun unimportedBinaryCallablesAddImportEdit() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedBinaryCallableUse.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useMathFunction(): Int = ab

          fun useMathProperty(): Double = PI
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val functionCaret = content.indexOf("= ab") + "= ab".length
          val functionHelp = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, functionCaret))
          })
          val functionLabels = functionHelp.items.map { it.ideLabel }
          assertTrue("labels=$functionLabels", functionLabels.contains("abs"))
          val absItem = functionHelp.items.single { it.ideLabel == "abs" }
          assertEquals(CompletionItemKind.FUNCTION, absItem.completionKind)
          assertEquals("abs(\$0)", absItem.insertText)
          assertEquals(InsertTextFormat.SNIPPET, absItem.insertTextFormat)
          assertEquals(Command.TRIGGER_PARAMETER_HINTS, absItem.command?.command)
          assertTrue(requireNotNull(absItem.snippetDescription).deleteSelected)
          assertEquals("kotlin.math.abs", absItem.detail)
          assertTrue(
              requireNotNull(absItem.additionalTextEdits).single().newText
                  .contains("import kotlin.math.abs")
          )

          val propertyCaret = content.indexOf("= PI") + "= PI".length
          val propertyHelp = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, propertyCaret))
          })
          val propertyLabels = propertyHelp.items.map { it.ideLabel }
          assertTrue("labels=$propertyLabels", propertyLabels.contains("PI"))
          val piItem = propertyHelp.items.single { it.ideLabel == "PI" }
          assertEquals(CompletionItemKind.PROPERTY, piItem.completionKind)
          assertEquals("PI", piItem.insertText)
          assertEquals(InsertTextFormat.PLAIN_TEXT, piItem.insertTextFormat)
          assertNull(piItem.command)
          assertEquals("kotlin.math.PI", piItem.detail)
          assertTrue(
              requireNotNull(piItem.additionalTextEdits).single().newText
                  .contains("import kotlin.math.PI")
          )
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun unimportedClassifierIgnoresAliasedSimpleName() {
    withWorkspace {
      val extraDir = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/extra")
      val extraFile = extraDir.resolve("AliasCandidate.kt")
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedAliasUse.kt")
      val extraContent = """
          package com.tom.rv2ide.testing.kotlinfixture.app.extra

          class AliasWidget
      """.trimIndent() + "\n"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import com.tom.rv2ide.testing.kotlinfixture.app.extra.AliasWidget as Widget

          fun useAliased(): Alias = TODO()
      """.trimIndent() + "\n"
      Files.createDirectories(extraDir)
      Files.write(extraFile, extraContent.toByteArray(Charsets.UTF_8))
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf(": Alias") + ": Alias".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          val item = help.items.single { it.ideLabel == "AliasWidget" }
          assertEquals(CompletionItemKind.CLASS, item.completionKind)
          val edit = requireNotNull(item.additionalTextEdits).single()
          assertTrue(edit.newText.contains("import com.tom.rv2ide.testing.kotlinfixture.app.extra.AliasWidget"))
        }
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(extraFile)
        Files.deleteIfExists(extraDir)
      }
    }
  }

  @Test
  fun unimportedStarImportSuppressesDuplicateImport() {
    withWorkspace {
      val extraDir = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/extra")
      val extraFile = extraDir.resolve("StarCandidate.kt")
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedStarUse.kt")
      val extraContent = """
          package com.tom.rv2ide.testing.kotlinfixture.app.extra

          class StarWidget
      """.trimIndent() + "\n"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import com.tom.rv2ide.testing.kotlinfixture.app.extra.*

          fun useStarImported(): Star = TODO()
      """.trimIndent() + "\n"
      Files.createDirectories(extraDir)
      Files.write(extraFile, extraContent.toByteArray(Charsets.UTF_8))
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf(": Star") + ": Star".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          val item = help.items.single { it.ideLabel == "StarWidget" }
          assertEquals(CompletionItemKind.CLASS, item.completionKind)
          assertTrue(item.additionalTextEdits.isNullOrEmpty())
        }
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(extraFile)
        Files.deleteIfExists(extraDir)
      }
    }
  }

  @Test
  fun unimportedClassifierSkipsPrivateTopLevel() {
    withWorkspace {
      val extraDir = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/extra")
      val extraFile = extraDir.resolve("HiddenCandidate.kt")
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedHiddenUse.kt")
      val extraContent = """
          package com.tom.rv2ide.testing.kotlinfixture.app.extra

          private class HiddenWidget

          class HiddenVisibleWidget
      """.trimIndent() + "\n"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useHidden(): Hidden = TODO()
      """.trimIndent() + "\n"
      Files.createDirectories(extraDir)
      Files.write(extraFile, extraContent.toByteArray(Charsets.UTF_8))
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf(": Hidden") + ": Hidden".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          assertFalse(help.items.any { it.ideLabel == "HiddenWidget" })
          val item = help.items.single { it.ideLabel == "HiddenVisibleWidget" }
          val edit = requireNotNull(item.additionalTextEdits).single()
          assertTrue(edit.newText.contains("import com.tom.rv2ide.testing.kotlinfixture.app.extra.HiddenVisibleWidget"))
        }
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(extraFile)
        Files.deleteIfExists(extraDir)
      }
    }
  }

  @Test
  fun unimportedClassifierInsertsSortedImport() {
    withWorkspace {
      val extraDir = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/extra")
      val extraFile = extraDir.resolve("SortedCandidate.kt")
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedSortedUse.kt")
      val extraContent = """
          package com.tom.rv2ide.testing.kotlinfixture.app.extra

          class SortedWidget
      """.trimIndent() + "\n"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import java.util.ArrayList
          import kotlin.math.max

          fun useSorted(): Sorted = TODO()
      """.trimIndent() + "\n"
      Files.createDirectories(extraDir)
      Files.write(extraFile, extraContent.toByteArray(Charsets.UTF_8))
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf(": Sorted") + ": Sorted".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          val item = help.items.single { it.ideLabel == "SortedWidget" }
          val edit = requireNotNull(item.additionalTextEdits).single()
          assertEquals("import com.tom.rv2ide.testing.kotlinfixture.app.extra.SortedWidget\n", edit.newText)
          assertEquals(2, edit.range.start.line)
        }
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(extraFile)
        Files.deleteIfExists(extraDir)
      }
    }
  }

  @Test
  fun unimportedCallablesAddImportEdit() {
    withWorkspace {
      val extraDir = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/extra")
      val extraFile = extraDir.resolve("CallableCandidate.kt")
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedCallableUse.kt")
      val extraContent = """
          package com.tom.rv2ide.testing.kotlinfixture.app.extra

          fun helperFunction(value: Int): Int = value

          val helperProperty: String = ""

          fun String.helperExtension(): String = this
      """.trimIndent() + "\n"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useCallables() {
            val value = helper
          }
      """.trimIndent() + "\n"
      Files.createDirectories(extraDir)
      Files.write(extraFile, extraContent.toByteArray(Charsets.UTF_8))
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf("= helper") + "= helper".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          val function = help.items.single { it.ideLabel == "helperFunction" }
          assertEquals(CompletionItemKind.FUNCTION, function.completionKind)
          assertEquals("helperFunction(\$0)", function.insertText)
          assertEquals(InsertTextFormat.SNIPPET, function.insertTextFormat)
          assertEquals(Command.TRIGGER_PARAMETER_HINTS, function.command?.command)
          assertTrue(requireNotNull(function.snippetDescription).deleteSelected)
          assertTrue(
              requireNotNull(function.additionalTextEdits).single().newText
                  .contains("import com.tom.rv2ide.testing.kotlinfixture.app.extra.helperFunction")
          )
          val property = help.items.single { it.ideLabel == "helperProperty" }
          assertEquals(CompletionItemKind.PROPERTY, property.completionKind)
          assertEquals("helperProperty", property.insertText)
          assertEquals(InsertTextFormat.PLAIN_TEXT, property.insertTextFormat)
          assertNull(property.command)
          assertTrue(
              requireNotNull(property.additionalTextEdits).single().newText
                  .contains("import com.tom.rv2ide.testing.kotlinfixture.app.extra.helperProperty")
          )
          assertFalse(help.items.any { it.ideLabel == "helperExtension" })
        }
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(extraFile)
        Files.deleteIfExists(extraDir)
      }
    }
  }

  @Test
  fun unimportedClassifierInsertsSortedImportInsideGroup() {
    withWorkspace {
      val extraDir = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/extra")
      val extraFile = extraDir.resolve("GroupedCandidate.kt")
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedGroupedUse.kt")
      val extraContent = """
          package com.tom.rv2ide.testing.kotlinfixture.app.extra

          class GroupedWidget
      """.trimIndent() + "\n"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import java.util.ArrayList
          import kotlin.math.max

          import com.tom.rv2ide.testing.kotlinfixture.app.other.StaticThing

          fun useGrouped(): Grouped = TODO()
      """.trimIndent() + "\n"
      Files.createDirectories(extraDir)
      Files.write(extraFile, extraContent.toByteArray(Charsets.UTF_8))
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf(": Grouped") + ": Grouped".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          val item = help.items.single { it.ideLabel == "GroupedWidget" }
          val edit = requireNotNull(item.additionalTextEdits).single()
          assertEquals("import com.tom.rv2ide.testing.kotlinfixture.app.extra.GroupedWidget\n", edit.newText)
          assertEquals(2, edit.range.start.line)
        }
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(extraFile)
        Files.deleteIfExists(extraDir)
      }
    }
  }

  @Test
  fun unimportedExtensionAddsImportEdit() {
    withWorkspace {
      val extraDir = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/extra")
      val extraFile = extraDir.resolve("ExtensionCandidate.kt")
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedExtensionUse.kt")
      val extraContent = """
          package com.tom.rv2ide.testing.kotlinfixture.app.extra

          fun String.addExclamation(): String = this + "!"

          val String.extLength: Int get() = length

          fun Int.plusOne(): Int = this + 1
      """.trimIndent() + "\n"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useUnimportedExtension() {
            "text".
          }
      """.trimIndent() + "\n"
      Files.createDirectories(extraDir)
      Files.write(extraFile, extraContent.toByteArray(Charsets.UTF_8))
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf("\"text\".") + "\"text\".".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          val function = help.items.single { it.ideLabel == "addExclamation" }
          assertEquals(CompletionItemKind.FUNCTION, function.completionKind)
          assertEquals("addExclamation(\$0)", function.insertText)
          assertEquals(InsertTextFormat.SNIPPET, function.insertTextFormat)
          assertEquals(Command.TRIGGER_PARAMETER_HINTS, function.command?.command)
          assertTrue(requireNotNull(function.snippetDescription).deleteSelected)
          assertTrue(
              requireNotNull(function.additionalTextEdits).single().newText
                  .contains("import com.tom.rv2ide.testing.kotlinfixture.app.extra.addExclamation")
          )
          val property = help.items.single { it.ideLabel == "extLength" }
          assertEquals(CompletionItemKind.PROPERTY, property.completionKind)
          assertEquals("extLength", property.insertText)
          assertEquals(InsertTextFormat.PLAIN_TEXT, property.insertTextFormat)
          assertNull(property.command)
          assertTrue(
              requireNotNull(property.additionalTextEdits).single().newText
                  .contains("import com.tom.rv2ide.testing.kotlinfixture.app.extra.extLength")
          )
          assertFalse(help.items.any { it.ideLabel == "plusOne" })
          assertTrue(help.items.any { it.ideLabel == "length" })
        }
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(extraFile)
        Files.deleteIfExists(extraDir)
      }
    }
  }

  @Test
  fun modifierKeywordCompletionBeforeFunction() {
    verifyCompletion(
        "CompletionModifierFunction.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class ModifierHolder {
              pri fun value(): String = ""
            }
        """,
        "pri",
    ) { labels, items ->
      assertTrue(labels.contains("private"))
      assertFalse(labels.contains("value"))
      val item = items.single { it.ideLabel == "private" }
      assertEquals(CompletionItemKind.KEYWORD, item.completionKind)
      assertEquals("private", item.insertText)
    }
  }

  @Test
  fun modifierKeywordCompletionBeforeClass() {
    verifyCompletion(
        "CompletionModifierClass.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            pri class ModifierClass
        """,
        "pri",
    ) { labels, items ->
      assertTrue(labels.contains("private"))
      val item = items.single { it.ideLabel == "private" }
      assertEquals(CompletionItemKind.KEYWORD, item.completionKind)
      assertEquals("private", item.insertText)
    }
  }

  @Test
  fun modifierKeywordsNotOfferedInExpressionPosition() {
    verifyCompletion(
        "CompletionModifierNegative.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            fun useModifierNegative() {
              val answer = pri
            }
        """,
        "pri",
    ) { labels, _ ->
      assertFalse(labels.contains("private"))
    }
  }

  @Test
  fun inheritedAndFakeOverrideMembersAppear() {
    verifyCompletion(
        "CompletionInheritedMembers.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            interface BaseMember {
              fun baseFn(): String
            }

            class DerivedMember : BaseMember {
              override fun baseFn(): String = ""
            }

            fun useDerived(d: DerivedMember) {
              d.
            }
        """,
        "d.",
    ) { labels, _ ->
      assertTrue(labels.contains("baseFn"))
    }
  }

  @Test
  fun memberExtensionViaImplicitReceiverAppears() {
    verifyCompletion(
        "CompletionMemberExtension.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class ExtTarget {
              val name: String = ""
            }

            class ExtensionHost {
              fun ExtTarget.hostExtFn(): String = ""
            }

            fun useExtensionHost(host: ExtensionHost, target: ExtTarget) {
              with(host) {
                target.
              }
            }
        """,
        "target.",
    ) { labels, _ ->
      assertTrue(labels.contains("hostExtFn"))
    }
  }

  @Test
  fun smartCastReceiverUsesNarrowedType() {
    verifyCompletion(
        "CompletionSmartCast.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            fun useSmartCast(value: Any) {
              if (value is String) {
                value.
              }
            }
        """,
        "value.",
    ) { labels, _ ->
      assertTrue(labels.contains("length"))
    }
  }

  @Test
  fun companionReceiverExposesCompanionMembers() {
    verifyCompletion(
        "CompletionCompanion.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            class CompanionHost {
              companion object {
                fun create(): String = ""
              }
            }

            fun useCompanion() {
              CompanionHost.
            }
        """,
        "CompanionHost.",
    ) { labels, _ ->
      assertTrue(labels.contains("create"))
    }
  }

  @Test
  fun objectReceiverExposesObjectMembers() {
    verifyCompletion(
        "CompletionObject.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            object SingletonHost {
              fun ping(): String = ""
            }

            fun useObject() {
              SingletonHost.
            }
        """,
        "SingletonHost.",
    ) { labels, _ ->
      assertTrue(labels.contains("ping"))
    }
  }

  @Test
  fun extensionFunctionTypeVariableAppearsForReceiver() {
    verifyCompletion(
        "CompletionExtensionFunctionValue.kt",
        """
            package com.tom.rv2ide.testing.kotlinfixture.app

            fun useExtensionFunctionValue() {
              val sum: Int.(Int) -> Int = { other -> this + other }
              val textSum: String.(String) -> String = { other -> this + other }
              5.
            }
        """,
        "5.",
    ) { labels, items ->
      assertTrue(labels.contains("sum"))
      assertFalse(labels.contains("textSum"))
      val item = items.single { it.ideLabel == "sum" }
      assertEquals("sum(\$0)", item.insertText)
      assertEquals(InsertTextFormat.SNIPPET, item.insertTextFormat)
      assertEquals(Command.TRIGGER_PARAMETER_HINTS, item.command?.command)
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
      assertEquals("String", item.overrideTypeText)
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

  @Test
  fun unimportedCandidateSurvivesSameLabelVisibleSymbol() {
    withWorkspace {
      val extraDir = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/extra")
      val extraFile = extraDir.resolve("CollisionCandidate.kt")
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/UnimportedCollisionUse.kt")
      val extraContent = """
          package com.tom.rv2ide.testing.kotlinfixture.app.extra

          class UnimportedWidget
      """.trimIndent() + "\n"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun UnimportedWidget(): String = ""

          fun useUnimportedCollision(): Unimp = TODO()
      """.trimIndent() + "\n"
      Files.createDirectories(extraDir)
      Files.write(extraFile, extraContent.toByteArray(Charsets.UTF_8))
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val caret = content.indexOf(": Unimp") + ": Unimp".length
          val help = requireNotNull(runAnalysis {
            runtime.complete(completionRequest(file, content, caret))
          })
          val importedClass =
              help.items.single {
                it.ideLabel == "UnimportedWidget" && it.completionKind == CompletionItemKind.CLASS
              }
          assertTrue(requireNotNull(importedClass.additionalTextEdits).isNotEmpty())
          assertTrue(
              help.items.any {
                it.ideLabel == "UnimportedWidget" &&
                    it.completionKind == CompletionItemKind.FUNCTION &&
                    it.additionalTextEdits.isNullOrEmpty()
              }
          )
        }
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(extraFile)
        Files.deleteIfExists(extraDir)
      }
    }
  }

  @Test
  fun completionAbortsWhenCancelledDuringEnumeration() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/CompletionCancellation.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun useCancelledScope() {
            val value = con
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val offset = content.indexOf("= con") + "= con".length
          val request = completionRequest(file, content, offset)
          val checker = CompletionCheckpointChecker(cancelAt = 3)
          val failure =
              try {
                runAnalysis { runtime.complete(request.copy(cancelChecker = checker)) }
                null
              } catch (error: Throwable) {
                error
              }
          assertNotNull(failure)
          var current: Throwable? = failure ?: error("expected cancellation")
          var cancelled = false
          while (current != null) {
            if (current is CancellationException) {
              cancelled = true
              break
            }
            val cause = current.cause
            current = if (cause === current) null else cause
          }
          assertTrue(cancelled)
          assertTrue(checker.checkpoints.get() >= 3)
        }
      } finally {
        Files.deleteIfExists(file)
      }
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

  private class CompletionCheckpointChecker(private val cancelAt: Int) : ICancelChecker {
    val checkpoints = AtomicInteger()
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
      cancelled.set(true)
    }

    override fun isCancelled(): Boolean = cancelled.get()

    override fun abortIfCancelled() {
      if (checkpoints.incrementAndGet() >= cancelAt) cancel()
      if (isCancelled()) throw CancellationException()
    }
  }
}