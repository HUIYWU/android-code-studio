package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.FieldCompletionData
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiSwitchCompletionCapabilityTest {
  @Test
  fun enumLabelsUseActiveSelectorAndPreserveFallbackRoutes() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val disk = Files.readAllBytes(path).decodeToString()
      val orderedConstants = List(CompletionResult.MAX_ITEMS + 1) { "OPTION_${it.toString().padStart(2, '0')}" }
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          enum CompletionMode {
            RED, GREEN, BLUE;
            static final int EXTRA = 1;
          }
          enum LargeMode { ${orderedConstants.asReversed().joinToString(", ")} }
          class JavaInterop {
            void complete(CompletionMode mode, int number, String value) {
              switch (mode) { case GR: break; case RED, BL: break; default: break; }
              switch (mode) { case  : break; case RED,  : break; }
              switch (mode) { case ZZ: break; case CompletionMode.BLUE: break; case (GR): break; }
              switch (number) { case num: break; }
              switch (value) { case tex: break; }
              switch (mode) { case
              }
            }
            int expression(CompletionMode mode, int answerValue) {
              return switch (mode) { case BL -> 2; case RED -> answerValue; default -> 0; };
            }
            void large(LargeMode mode) {
              switch (mode) { case OPTION_: break; }
            }
          }
      """.trimIndent()
      openDocument(path, content, version = 1, revision = 81L)
      withRuntime(workspace) {
        val module = requireNotNull(workspace.findModuleForFile(path))
        fun complete(marker: String) = runAnalysis {
          JavaPsiServiceInterop.complete(params(path, content, marker), module, false)
        }
        fun items(marker: String) = requireNotNull(complete(marker)) { marker }.items

        val green = items("case GR").single()
        assertEquals("GREEN", green.ideLabel)
        assertEquals("GREEN", green.insertText)
        assertEquals(CompletionItemKind.ENUM_MEMBER, green.completionKind)
        assertEquals("com.tom.rv2ide.testing.kotlinfixture.app.CompletionMode",
            (green.data as FieldCompletionData).classInfo.className)
        assertEquals(listOf("BLUE"), items("case RED, BL").map { it.ideLabel })
        val names = setOf("RED", "GREEN", "BLUE")
        assertEquals(names, items("case  ").map { it.ideLabel }.toSet())
        assertEquals(names, items("case RED,  ").map { it.ideLabel }.toSet())
        assertEquals(names, items("case\n").map { it.ideLabel }.toSet())
        assertEquals(listOf("BLUE"), items("case BL").map { it.ideLabel })
        assertTrue(items("case RED -> answerV").any { it.ideLabel == "answerValue" })
        assertTrue(items("case CompletionMode.BL").any { it.ideLabel == "BLUE" })
        assertTrue(items("case ZZ").isEmpty())
        val large = requireNotNull(complete("case OPTION_"))
        assertEquals(orderedConstants.take(CompletionResult.MAX_ITEMS), large.items.map { it.ideLabel })
        assertTrue(large.isIncomplete)
        for (marker in listOf("case num", "case tex", "case (GR", "default:")) {
          assertNull(marker, complete(marker))
        }
        val stale = params(path, content, "case GR").apply { documentRevision = 80L }
        assertNull(runAnalysis { JavaPsiServiceInterop.complete(stale, module, false) })
        assertEquals(disk, Files.readAllBytes(path).decodeToString())
      }
    }
  }

  private fun params(path: Path, text: String, marker: String): CompletionParams {
    val start = text.indexOf(marker)
    require(start >= 0) { marker }
    val offset = start + marker.length
    val prefix = text.substring(0, offset).takeLastWhile { Character.isJavaIdentifierPart(it) }
    return CompletionParams(LineIndex.from(text).indexToPosition(offset), path, ICancelChecker.NOOP).apply {
      content = text
      this.prefix = prefix
      documentVersion = 1
      documentRevision = 81L
    }
  }
}