package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.ClassCompletionData
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MethodCompletionData
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiCompletionCapabilityTest {
  @Test
  fun javaMemberCompletionUsesActiveReceiverAndSubstitutesInheritedTypes() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val disk = Files.readAllBytes(path).decodeToString()
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import java.util.List;
          class CompletionParent<T> {
            public T value;
            public T copy(T value) { return value; }
            public T copy(T value, int count) { return value; }
            public static void make() {}
            private void hidden() {}
            public class Inner {}
            private class Invisible {}
            public static class Nested {}
          }
          final class JavaInterop extends CompletionParent<String> {
            @Override public String copy(String value) { return value; }
            <T extends CompletionParent<String>> void complete(JavaInterop receiver, int[] values, T bounded) {
              receiver.;
              receiver.co("existing");
              CompletionParent.mak;
              CompletionParent.Nes;
              Object nested = new CompletionParent.Nes();
              receiver.new;
              receiver.new ;
              receiver.new In;
              values.len;
              bounded.val;
              Object nonMember;
              java.util.function.Function<String, String> function = receiver::copy;
              java.util.function.Function<String, String> partialReference = receiver::co;
              java.util.function.Function<String, String> staticReference = String::valu;
              java.util.function.BiFunction<String, Integer, String> unboundReference = String::sub;
              java.util.function.Supplier<StringBuilder> constructorReference = StringBuilder::ne;
              java.util.function.IntFunction<String[]> arrayConstructorReference = String[]::ne;
            }
          }
          class ProtectedCompletion extends java.util.ArrayList<String> {
            void complete(java.util.ArrayList<String> list) {
              super.removeRan;
              list.removeRan;
            }
          }
      """.trimIndent()
      openDocument(path, content, version = 1, revision = 51L)
      withRuntime(workspace) {
        val instance = complete(path, content, "receiver.")
        val copy = instance.items.single { it.ideLabel == "copy" }
        val method = copy.data as MethodCompletionData
        assertEquals("copy(String)", copy.detail)
        assertEquals("String", copy.overrideTypeText)
        assertEquals(listOf("java.lang.String"), method.erasedParameterTypes)
        assertEquals(1, method.plusOverloads)
        assertEquals("copy($0)", copy.insertText)
        assertEquals(Command.TRIGGER_PARAMETER_HINTS, copy.command?.command)
        assertEquals("String", instance.items.single { it.ideLabel == "value" }.overrideTypeText)
        assertFalse(instance.items.any { it.ideLabel in setOf("hidden", "Inner", "Nested", "make") })
        assertEquals("copy", complete(path, content, "receiver.co").items.single { it.ideLabel == "copy" }.insertText)
        assertEquals("make()\$0", complete(path, content, "CompletionParent.mak").items.single { it.ideLabel == "make" }.insertText)
        assertTrue(complete(path, content, "CompletionParent.Nes").items.any { it.ideLabel == "Nested" })
        val nested = complete(path, content, "new CompletionParent.Nes").items.single { it.ideLabel == "Nested" }
        assertEquals("Nested", nested.insertText)
        val inner = complete(path, content, "receiver.new").items.single()
        assertEquals("Inner", inner.ideLabel)
        assertEquals("new Inner($0)", inner.insertText)
        assertEquals(3, inner.snippetDescription?.selectedLength)
        assertEquals("Inner($0)", complete(path, content, "receiver.new ").items.single().insertText)
        assertEquals("Inner($0)", complete(path, content, "receiver.new In").items.single().insertText)
        assertEquals("length", complete(path, content, "values.len").items.single().ideLabel)
        assertEquals("String", complete(path, content, "bounded.val").items.single { it.ideLabel == "value" }.overrideTypeText)
        assertTrue(complete(path, content, "super.removeRan").items.any { it.ideLabel == "removeRange" })
        assertFalse(complete(path, content, "list.removeRan").items.any { it.ideLabel == "removeRange" })
        val emptyReference = complete(path, content, "receiver::")
        assertTrue(emptyReference.items.any { it.ideLabel == "copy" })
        assertFalse(emptyReference.items.any { it.ideLabel == "make" })
        val instanceReference = complete(path, content, "receiver::co")
        val referenceCopy = instanceReference.items.single { it.ideLabel == "copy" }
        assertEquals("copy", referenceCopy.insertText)
        assertEquals(1, (referenceCopy.data as MethodCompletionData).plusOverloads)
        assertFalse(instanceReference.items.any { it.ideLabel == "make" })
        val staticReference = complete(path, content, "String::valu")
        assertTrue(staticReference.items.any { it.ideLabel == "valueOf" })
        assertTrue(complete(path, content, "String::sub").items.any { it.ideLabel == "substring" })
        assertEquals("new", complete(path, content, "StringBuilder::ne").items.single().ideLabel)
        assertEquals("new", complete(path, content, "String[]::ne").items.single().ideLabel)
        assertNull("nonMember", runAnalysis {
          JavaPsiServiceInterop.complete(params(path, content, "nonMember"), null, false)
        })
        val stale = params(path, content, "receiver.").apply { documentRevision = 50L }
        assertNull(runAnalysis { JavaPsiServiceInterop.complete(stale, null, false) })
        val staleReference = params(path, content, "receiver::co").apply { documentRevision = 50L }
        assertNull(runAnalysis { JavaPsiServiceInterop.complete(staleReference, null, false) })
        val mismatched = params(path, content, "receiver.").apply { this.content = disk }
        assertTrue(runAnalysis { JavaPsiServiceInterop.complete(mismatched, null, false) }?.items?.isEmpty() == true)
        assertEquals(disk, Files.readAllBytes(path).decodeToString())
      }
    }
  }

  @Test
  fun kotlinLightClassesUseTheSameJavaCompletionEntry() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import com.tom.rv2ide.testing.kotlinfixture.library.InteropOverloads;
          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApiKt;
          final class JavaInterop {
            void complete(InteropOverloads api) {
              api.;
              api.dec;
              InteropOverloads.;
              api.new In;
              LibraryApiKt.library;
            }
          }
      """.trimIndent()
      openDocument(path, content, version = 1, revision = 51L)
      withRuntime(workspace) {
        val instance = complete(path, content, "api.").items
        assertTrue(instance.map { it.ideLabel }.containsAll(listOf("pick", "getLabel", "decorated")))
        assertFalse(instance.any { it.ideLabel in setOf("label", "hidden", "InteropOverloads", "TOKEN") })
        assertEquals("getLabel()\$0", instance.single { it.ideLabel == "getLabel" }.insertText)
        assertEquals(1, (instance.single { it.ideLabel == "pick" }.data as MethodCompletionData).plusOverloads)
        val decorated = complete(path, content, "api.dec").items.single { it.ideLabel == "decorated" }
        assertEquals("decorated(String, String)", decorated.detail)
        assertEquals("decorated($0)", decorated.insertText)
        val statics = complete(path, content, "InteropOverloads.").items
        assertTrue(statics.map { it.ideLabel }.containsAll(listOf("Companion", "Nested", "TOKEN")))
        assertFalse(statics.any { it.ideLabel == "decorated" })
        val inner = complete(path, content, "api.new In").items.single()
        assertEquals("Inner($0)", inner.insertText)
        assertEquals("com.tom.rv2ide.testing.kotlinfixture.library.InteropOverloads\$Inner",
            (inner.data as ClassCompletionData).flatName)
        val facade = complete(path, content, "LibraryApiKt.library").items.single { it.ideLabel == "libraryExtension" }
        assertEquals("libraryExtension(String)", facade.detail)
      }
    }
  }

  private fun complete(path: Path, content: String, marker: String): CompletionResult =
      requireNotNull(runAnalysis { JavaPsiServiceInterop.complete(params(path, content, marker), null, false) }) { marker }

  private fun params(path: Path, content: String, marker: String): CompletionParams {
    val start = content.indexOf(marker)
    require(start >= 0) { marker }
    val offset = start + marker.length
    return CompletionParams(LineIndex.from(content).indexToPosition(offset), path, ICancelChecker.NOOP).apply {
      this.content = content
      prefix = marker
      documentVersion = 1
      documentRevision = 51L
    }
  }
}