package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.edits.ClassImportEditHandler
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.ClassCompletionData
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.MethodCompletionData
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
class JavaPsiIdentifierCompletionCapabilityTest {
  @Test
  fun activeScopeIncludesVisibleVariablesMembersAndImports() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val disk = Files.readAllBytes(path).decodeToString()
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import java.util.List;
          import java.util.concurrent.*;
          import static java.lang.Math.PI;
          import static java.util.Collections.*;
          class ScopeBase<T> {
            protected T inheritedValue;
            private T hiddenValue;
            protected T gather(T value) { return value; }
            protected T gather(T value, int count) { return value; }
          }
          class JavaInterop extends ScopeBase<String> {
            private long tokenLocal;
            private int instanceOnly;
            private static int staticOnly;
            private static int pending = staticOn;
            { Object initial = instanceOn; }
            @Override protected String gather(String value) { return value; }
            void complete(Object tokenParameter) {
              int tokenLocal = 1;
              Object active = token;
              int tokenLater = 2;
              if (tokenParameter instanceof String patternText) {
                Object matched = patternTe;
              }
              Object outside = patternTe;
              Object inherited = inheritedVal;
              Object absent = hiddenVal;
              Object call = gat;
              Object existing = gat("value");
              Object imported = Lis;
              ConcurrentHashM concurrent;
              StringB builder;
              double pi = PI;
              Object viaStar = emptyLis;
              Object self = th;
              Object parent = sup;
              Object keyword = ret;
            }
            static void inStaticBody() {
              Object allowed = staticOn;
              Object forbidden = instanceOn;
              Object noSelf = th;
            }
          }
      """.trimIndent()
      openDocument(path, content, version = 1, revision = 61L)
      withRuntime(workspace) {
        val module = requireNotNull(workspace.findModuleForFile(path))
        fun items(marker: String) = requireNotNull(runAnalysis {
          JavaPsiServiceInterop.complete(params(path, content, marker), module, false)
        }) { marker }.items

        val locals = items("active = token")
        val local = locals.single { it.ideLabel == "tokenLocal" }
        assertEquals(CompletionItemKind.VARIABLE, local.completionKind)
        assertEquals(MatchLevel.CASE_SENSITIVE_PREFIX, local.matchLevel)
        assertEquals("int", local.overrideTypeText)
        assertEquals(CompletionItemKind.PROPERTY, locals.single { it.ideLabel == "tokenParameter" }.completionKind)
        assertFalse(locals.any { it.ideLabel == "tokenLater" })
        assertEquals("String", items("matched = patternTe").single { it.ideLabel == "patternText" }.overrideTypeText)
        assertFalse(items("outside = patternTe").any { it.ideLabel == "patternText" })
        assertEquals("String", items("inherited = inheritedVal").single { it.ideLabel == "inheritedValue" }.overrideTypeText)
        assertFalse(items("absent = hiddenVal").any { it.ideLabel == "hiddenValue" })

        val method = items("call = gat").single { it.ideLabel == "gather" }
        assertEquals("gather(String)", method.detail)
        assertEquals(1, (method.data as MethodCompletionData).plusOverloads)
        assertEquals("gather($0)", method.insertText)
        assertEquals(Command.TRIGGER_PARAMETER_HINTS, method.command?.command)
        assertEquals("gather", items("existing = gat").single { it.ideLabel == "gather" }.insertText)

        val list = items("imported = Lis").single { it.ideLabel == "List" }
        assertEquals("java.util.List", (list.data as ClassCompletionData).className)
        assertNull(list.additionalEditHandler)
        val concurrent = items("ConcurrentHashM").single { it.ideLabel == "ConcurrentHashMap" }
        assertEquals("java.util.concurrent.ConcurrentHashMap", (concurrent.data as ClassCompletionData).className)
        assertNull(concurrent.additionalEditHandler)
        assertNull(items("StringB").single { it.ideLabel == "StringBuilder" }.additionalEditHandler)
        assertTrue(items("pi = PI").any { it.ideLabel == "PI" })
        assertTrue(items("viaStar = emptyLis").any { it.ideLabel == "emptyList" })
        assertTrue(items("keyword = ret").any { it.ideLabel == "return" })
        assertTrue(items("self = th").any { it.ideLabel == "this" })
        assertTrue(items("parent = sup").any { it.ideLabel == "super" })
        assertFalse(items("noSelf = th").any { it.ideLabel == "this" })
        assertTrue(items("pending = staticOn").any { it.ideLabel == "staticOnly" })
        assertTrue(items("initial = instanceOn").any { it.ideLabel == "instanceOnly" })
        assertTrue(items("allowed = staticOn").any { it.ideLabel == "staticOnly" })
        assertFalse(items("forbidden = instanceOn").any { it.ideLabel == "instanceOnly" })
        assertEquals(disk, Files.readAllBytes(path).decodeToString())
      }
    }
  }

  @Test
  fun indexedTypesAndLegacyRoutesRespectTheRequestSnapshot() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val disk = Files.readAllBytes(path).decodeToString()
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import java.util.List;
          class JavaInterop {
            void complete(int n) {
              ScopeIndexed value;
              switch (n) { case ca: break; case Integer.MAX_VALUE: break; }
              String text = "literal";
            }
          }
          class OverrideParent { public Object copy(Object value) { return value; } }
          class OverrideChild extends OverrideParent {
          }
          class OverridePrefix extends OverrideParent { cop }
      """.trimIndent()
      openDocument(path, content, version = 1, revision = 61L)
      withRuntime(workspace) {
        val module = requireNotNull(workspace.findModuleForFile(path))
        val name = "fixture.index.ScopeIndexedCandidate"
        module.compileClasspathClasses.append(name)
        val request = params(path, content, "ScopeIndexed")
        val candidate = requireNotNull(runAnalysis { JavaPsiServiceInterop.complete(request, module, false) })
            .items.single { it.ideLabel == "ScopeIndexedCandidate" }
        assertEquals(name, (candidate.data as ClassCompletionData).className)
        assertTrue(candidate.additionalEditHandler is ClassImportEditHandler)

        for (marker in listOf("case ca", "\"literal")) {
          assertNull(marker, runAnalysis { JavaPsiServiceInterop.complete(params(path, content, marker), module, false) })
        }
        val emptyBody = requireNotNull(runAnalysis {
          JavaPsiServiceInterop.complete(params(path, content, "class OverrideChild extends OverrideParent {"), module, false)
        })
        val copy = emptyBody.items.single { it.ideLabel == "copy" }
        assertEquals(CompletionItemKind.METHOD, copy.completionKind)
        assertEquals(0, (copy.data as MethodCompletionData).plusOverloads)
        assertTrue(copy.insertText.contains("super.copy(value)"))
        val prefixBody = requireNotNull(runAnalysis {
          JavaPsiServiceInterop.complete(params(path, content, "class OverridePrefix extends OverrideParent { cop"), module, false)
        })
        assertTrue(prefixBody.items.any { it.ideLabel == "copy" })
        val qualifiedLabel = params(path, content, "Integer.MAX_V")
        val members = requireNotNull(runAnalysis { JavaPsiServiceInterop.complete(qualifiedLabel, module, false) })
        assertTrue(members.items.any { it.ideLabel == "MAX_VALUE" })
        val stale = params(path, content, "ScopeIndexed").apply { documentRevision = 60L }
        assertNull(runAnalysis { JavaPsiServiceInterop.complete(stale, module, false) })
        val mismatched = params(path, content, "ScopeIndexed").apply { this.content = disk }
        assertTrue(runAnalysis { JavaPsiServiceInterop.complete(mismatched, module, false) }?.items?.isEmpty() == true)
        val cancelled = params(path, content, "ScopeIndexed", ICancelChecker.CANCELLED)
        val error = assertThrows(CompletionException::class.java) {
          runAnalysis { JavaPsiServiceInterop.complete(cancelled, module, false) }
        }
        assertTrue(error.cause is CancellationException)
        assertEquals(disk, Files.readAllBytes(path).decodeToString())
      }
    }
  }

  private fun params(path: Path, markedContent: String, marker: String, checker: ICancelChecker = ICancelChecker.NOOP): CompletionParams {
    val index = markedContent.indexOf(marker)
    require(index >= 0) { marker }
    val offset = index + marker.length
    val prefix = markedContent.substring(0, offset).takeLastWhile { Character.isJavaIdentifierPart(it) }
    return CompletionParams(LineIndex.from(markedContent).indexToPosition(offset), path, checker).apply {
      content = markedContent
      this.prefix = prefix
      documentVersion = 1
      documentRevision = 61L
    }
  }
}