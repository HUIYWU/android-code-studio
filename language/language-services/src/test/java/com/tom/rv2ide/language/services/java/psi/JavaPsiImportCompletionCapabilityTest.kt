package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.ClassCompletionData
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MethodCompletionData
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiImportCompletionCapabilityTest {
  @Test
  fun completesPackageSegmentsAndFiltersTypeMembersInTheActiveFile() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val disk = Files.readAllBytes(path).decodeToString()
      val pkg = "com.tom.rv2ide.testing.kotlinfixture.app"
      val content = """
          package $pkg;
          import  ;
          import st;
          import static  ;
          import jav;
          import java.uti;
          import java.util.Li;
          import java. /* separator */ util . Ma;
          import $pkg.ImportOwner.;
          import static $pkg.ImportOwner.;
          import static $pkg.ImportOwner.pi;
          import $pkg.ImportOwner.Nested.Le;
          import static $pkg.ImportMode.RE;
          import fixture.imports.DemoCan;
          class JavaInterop {}
          enum ImportMode { READ, WRITE }
          interface ImportContract {
            static void interfaceOnly() {}
          }
          class ImportParent {
            static int INHERITED;
            static void pick(int value) {}
          }
          class ImportOwner extends ImportParent implements ImportContract {
            static int TOKEN;
            private static int HIDDEN;
            int instanceField;
            static void pick(String value) {}
            static void pick(int value) {}
            void instanceMethod() {}
            class Inner {}
            private static class PrivateNested {}
            static class Nested { static class Leaf {} }
          }
      """.trimIndent()
      openDocument(path, content, version = 1, revision = 71L)
      withRuntime(workspace) {
        val module = requireNotNull(workspace.findModuleForFile(path))
        module.compileClasspathClasses.append("fixture.imports.DemoCandidate")
        fun items(marker: String) = requireNotNull(runAnalysis {
          JavaPsiServiceInterop.complete(params(path, content, marker), module, false)
        }) { marker }.items

        assertTrue(items("import  ").any { it.ideLabel == "static" })
        assertFalse(items("import static  ").any { it.ideLabel == "static" })
        assertEquals(CompletionItemKind.KEYWORD, items("import st").single { it.ideLabel == "static" }.completionKind)
        assertEquals("java", items("import jav").single { it.ideLabel == "java" }.insertText)
        val util = items("import java.uti").single { it.ideLabel == "util" }
        assertEquals(CompletionItemKind.MODULE, util.completionKind)
        assertEquals("util", util.insertText)
        val list = items("import java.util.Li").single { it.ideLabel == "List" }
        assertEquals("java.util.List", (list.data as ClassCompletionData).className)
        assertEquals("List", list.insertText)
        assertNull(list.additionalEditHandler)
        assertTrue(items("util . Ma").any { it.ideLabel == "Map" })
        val indexed = items("fixture.imports.DemoCan").single { it.ideLabel == "DemoCandidate" }
        assertEquals("fixture.imports.DemoCandidate", (indexed.data as ClassCompletionData).className)
        assertNull(indexed.additionalEditHandler)

        val nested = items("import $pkg.ImportOwner.")
        assertEquals(setOf("Inner", "Nested"), nested.map { it.ideLabel }.toSet())
        val leaf = items("ImportOwner.Nested.Le").single { it.ideLabel == "Leaf" }
        assertEquals("$pkg.ImportOwner\$Nested\$Leaf", (leaf.data as ClassCompletionData).flatName)
        val statics = items("import static $pkg.ImportOwner.").map { it.ideLabel }.toSet()
        assertEquals(setOf("TOKEN", "INHERITED", "pick", "Nested"), statics)
        val pick = items("import static $pkg.ImportOwner.pi").single { it.ideLabel == "pick" }
        assertEquals(1, (pick.data as MethodCompletionData).plusOverloads)
        assertEquals("pick", pick.insertText)
        assertEquals(InsertTextFormat.PLAIN_TEXT, pick.insertTextFormat)
        assertNull(pick.command)
        val constant = items("ImportMode.RE").single { it.ideLabel == "READ" }
        assertEquals(CompletionItemKind.ENUM_MEMBER, constant.completionKind)
        assertEquals("READ", constant.insertText)
        assertEquals(disk, Files.readAllBytes(path).decodeToString())
      }
    }
  }

  @Test
  fun kotlinImportsHonorRecognitionAndTheActiveDocumentStamp() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val pkg = "com.tom.rv2ide.testing.kotlinfixture.library"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import $pkg.InteropO;
          import static $pkg.InteropOverloads.TO;
          import static $pkg.LibraryApiKt.libraryEx;
          import static $pkg.InteropOverloads.Nes;
          class JavaInterop {}
      """.trimIndent()
      val previous = JavaPreferences.isJavaKotlinRecognitionEnabled
      try {
        JavaPreferences.isJavaKotlinRecognitionEnabled = true
        openDocument(path, content, version = 1, revision = 71L)
        withRuntime(workspace) {
          val module = requireNotNull(workspace.findModuleForFile(path))
          fun complete(request: CompletionParams) = runAnalysis { JavaPsiServiceInterop.complete(request, module, false) }

          val type = requireNotNull(complete(params(path, content, "import $pkg.InteropO")))
              .items.single { it.ideLabel == "InteropOverloads" }
          assertEquals("$pkg.InteropOverloads", (type.data as ClassCompletionData).className)
          val request = params(path, content, "InteropOverloads.TO")
          assertTrue(requireNotNull(complete(request)).items.any { it.ideLabel == "TOKEN" })
          val method = requireNotNull(complete(params(path, content, "LibraryApiKt.libraryEx")))
              .items.single { it.ideLabel == "libraryExtension" }
          assertEquals("libraryExtension", method.insertText)
          val nested = requireNotNull(complete(params(path, content, "InteropOverloads.Nes")))
              .items.single { it.ideLabel == "Nested" }
          assertEquals("$pkg.InteropOverloads\$Nested", (nested.data as ClassCompletionData).flatName)

          JavaPreferences.isJavaKotlinRecognitionEnabled = false
          assertTrue(requireNotNull(complete(request)).items.isEmpty())
          val stale = params(path, content, "InteropOverloads.TO").apply { documentRevision = 70L }
          assertNull(complete(stale))
          val mismatched = params(path, content, "InteropOverloads.TO").apply { this.content = "" }
          assertTrue(requireNotNull(complete(mismatched)).items.isEmpty())
          val cancelled = params(path, content, "InteropOverloads.TO", ICancelChecker.CANCELLED)
          val error = assertThrows(CompletionException::class.java) { complete(cancelled) }
          assertTrue(error.cause is CancellationException)
        }
      } finally {
        JavaPreferences.isJavaKotlinRecognitionEnabled = previous
      }
    }
  }

  private fun params(
      path: Path,
      text: String,
      marker: String,
      cancelChecker: ICancelChecker = ICancelChecker.NOOP,
  ): CompletionParams {
    val start = text.indexOf(marker)
    require(start >= 0) { marker }
    val offset = start + marker.length
    return CompletionParams(LineIndex.from(text).indexToPosition(offset), path, cancelChecker).apply {
      content = text
      prefix = text.substring(0, offset).takeLastWhile { it == '.' || Character.isJavaIdentifierPart(it) }
      documentVersion = 1
      documentRevision = 71L
    }
  }
}