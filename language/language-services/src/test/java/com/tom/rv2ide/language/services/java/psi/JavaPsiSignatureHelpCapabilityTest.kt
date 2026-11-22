package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.interop.JavaPsiDefinitionTarget
import com.tom.rv2ide.language.services.java.interop.JavaPsiHoverTarget
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceResolver
import com.tom.rv2ide.language.services.java.providers.SignatureProvider
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.ModuleProject
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
class JavaPsiSignatureHelpCapabilityTest {
  @Test
  fun activeJavaCallsKeepOverloadsDocumentationAndArgumentPosition() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiSignatures.java",
      )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          class JavaPsiSignatures {
            /** Combines the supplied values. */
            static String combine(String value, int count) { return value; }
            static String combine(String value) { return value; }
            static String combine(int value, int count) { return ""; }
            static String nested(String left, String right) { return left; }
            static String join(String... values) { return ""; }
            void run() {
              combine(nested("a,b", "c"), 2);
              combine("a", );
              join("a", "b", "c");
              missing(1);
            }
          }
      """.trimIndent()
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiSignatures {}"
      Files.write(path, disk.toByteArray(Charsets.UTF_8))
      openDocument(path, content, version = 3, revision = 211L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis {
            runtime.onFileCreated(path)
            runtime.onFileOpened(path)
          }
          val tailOffset = content.indexOf("), 2") + "), ".length
          val request = params(path, content, tailOffset)
          val help = runAnalysis { SignatureProvider(ICancelChecker.NOOP).signatureHelp(request) }
          assertEquals(
              setOf("combine(String value, int count)", "combine(String value)", "combine(int value, int count)"),
              help.signatures.map { it.label }.toSet(),
          )
          assertEquals("combine(String value, int count)", active(help))
          assertEquals(1, help.activeParameter)
          assertTrue(help.signatures[help.activeSignature].documentation.value.contains("Combines the supplied values."))
          val nested = runAnalysis {
            SignatureProvider(ICancelChecker.NOOP).signatureHelp(
                params(path, content, content.indexOf("""c"), 2""")),
            )
          }
          assertEquals("nested(String left, String right)", active(nested))
          assertEquals(1, nested.activeParameter)
          val partial = runAnalysis {
            SignatureProvider(ICancelChecker.NOOP).signatureHelp(
                params(path, content, content.indexOf("""combine("a", """) + """combine("a", """.length),
            )
          }
          assertEquals("combine(String value, int count)", active(partial))
          assertEquals(1, partial.activeParameter)
          val vararg = runAnalysis {
            SignatureProvider(ICancelChecker.NOOP).signatureHelp(
                params(path, content, content.indexOf("""c");""")),
            )
          }
          assertEquals("join(String... values)", active(vararg))
          assertEquals(0, vararg.activeParameter)
          val noIndex = params(path, content, tailOffset).apply { position.index = -1 }
          assertEquals(1, runAnalysis { SignatureProvider(ICancelChecker.NOOP).signatureHelp(noIndex) }.activeParameter)
          request.documentRevision = 210L
          assertNull(runAnalysis { JavaPsiServiceInterop.signatureHelp(request) })
          request.documentRevision = 211L
          request.documentVersion = 2
          assertNull(runAnalysis { JavaPsiServiceInterop.signatureHelp(request) })
          request.documentVersion = 3
          request.content = disk
          assertNull(runAnalysis { JavaPsiServiceInterop.signatureHelp(request) })
          val unresolved = runAnalysis {
            SignatureProvider(ICancelChecker.NOOP).signatureHelp(
                params(path, content, content.indexOf("missing(") + "missing(".length),
            )
          }
          assertTrue(unresolved.signatures.isEmpty())
          var cancelled = false
          try {
            JavaPsiServiceInterop.signatureHelp(
                params(path, content, tailOffset).copy(cancelChecker = ICancelChecker.CANCELLED),
            )
          } catch (_: java.util.concurrent.CancellationException) {
            cancelled = true
          }
          assertTrue(cancelled)
          assertEquals(disk, String(Files.readAllBytes(path), Charsets.UTF_8))
        }
      } finally {
        closeDocument(path)
        Files.deleteIfExists(path)
      }
    }
  }

  @Test
  fun constructorsGenericsImportsAndAccessUseJavaPsiCandidates() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiSignatureCases.java",
      )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import static java.lang.Math.max;
          class SignatureBox<T> {
            SignatureBox(String label) {}
            SignatureBox(int count) {}
            private SignatureBox(boolean hidden) {}
            T echo(T value) { return value; }
          }
          class SignatureParent { void inherited(int value) {} }
          class SignatureChild extends SignatureParent {
            private void choose(boolean hidden) {}
            void choose(int value) {}
            static void choose(String text) {}
          }
          class SignatureEmpty {}
          class SignatureImplicitSuper extends SignatureEmpty {
            SignatureImplicitSuper() { super(); }
          }
          class SignatureOwner {
            private static class Hidden {}
          }
          class SignatureBase { SignatureBase(int value) {} }
          class SignatureDerived extends SignatureBase {
            SignatureDerived() { this(1); }
            SignatureDerived(int value) { super(value); }
          }
          class JavaPsiSignatureCases {
            void run(SignatureBox<String> box, SignatureChild child) {
              new SignatureBox<String>("label");
              box.echo("value");
              child.inherited(1);
              child.choose(1);
              SignatureChild.choose("value");
              max(1, 2);
              new SignatureEmpty();
              new SignatureOwner.Hidden();
            }
          }
      """.trimIndent()
      Files.write(path, content.toByteArray(Charsets.UTF_8))
      openDocument(path, content, version = 3, revision = 211L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis {
            runtime.onFileCreated(path)
            runtime.onFileOpened(path)
          }
          fun help(marker: String, after: Boolean = false): SignatureHelp {
            val offset = content.indexOf(marker) + if (after) marker.length else marker.indexOf('(') + 1
            return runAnalysis { SignatureProvider(ICancelChecker.NOOP).signatureHelp(params(path, content, offset)) }
          }
          val constructor = help("""new SignatureBox<String>("label")""", after = true)
          assertTrue(constructor.signatures.any { it.label == "SignatureBox(String label)" })
          assertTrue(constructor.signatures.any { it.label == "SignatureBox(int count)" })
          assertFalse(constructor.signatures.any { it.label.contains("boolean") })
          assertEquals("SignatureBox(String label)", active(constructor))
          assertEquals("echo(String value)", active(help("box.echo(")))
          assertEquals("inherited(int value)", active(help("child.inherited(")))
          assertFalse(help("child.choose(").signatures.any { it.label.contains("boolean") })
          assertEquals(listOf("choose(String text)"), help("SignatureChild.choose(").signatures.map { it.label })
          val imported = help("max(1, 2)")
          assertTrue(imported.signatures.isNotEmpty())
          assertTrue(active(imported).startsWith("max(int "))
          val implicit = help("new SignatureEmpty()", after = true)
          assertEquals(listOf("SignatureEmpty()"), implicit.signatures.map { it.label })
          assertEquals(0, implicit.activeParameter)
          assertEquals("SignatureEmpty()", active(help("super()")))
          assertTrue(help("new SignatureOwner.Hidden(").signatures.isEmpty())
          assertEquals("SignatureDerived(int value)", active(help("this(1)")))
          assertEquals("SignatureBase(int value)", active(help("super(value)")))
          assertEquals(content, String(Files.readAllBytes(path), Charsets.UTF_8))
        }
      } finally {
        closeDocument(path)
        Files.deleteIfExists(path)
      }
    }
  }

  @Test
  fun kotlinSignaturesStillUseSemanticLabelsThroughTheJavaProvider() {
    withWorkspace {
      val path = fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
      )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import com.tom.rv2ide.testing.kotlinfixture.library.InteropOverloads;
          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi;
          class JavaInterop {
            String call() { return new InteropOverloads("label").pick("value"); }
            String extension() { return new InteropOverloads("label").decorated("receiver", "suffix"); }
            LibraryApi empty() { return new LibraryApi(); }
            static String identity(String value) { return value; }
            String nested() { return identity(new LibraryApi().load("nested")); }
            String arguments() {
              return new InteropOverloads("label").decorated(identity("left"),identity("right"));
            }
          }
      """.trimIndent()
      val previousRecognition = JavaPreferences.isJavaKotlinRecognitionEnabled
      try {
        JavaPreferences.isJavaKotlinRecognitionEnabled = true
        openDocument(path, content, version = 3, revision = 211L)
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(path) }
          fun help(marker: String): SignatureHelp = runAnalysis {
            SignatureProvider(ICancelChecker.NOOP).signatureHelp(
                params(path, content, content.indexOf(marker) + marker.length),
            )
          }
          assertEquals("InteropOverloads(label: String)", active(help("new InteropOverloads(")))
          val overloads = help(".pick(")
          assertEquals(setOf("pick(value: String)", "pick(value: Int)"), overloads.signatures.map { it.label }.toSet())
          assertEquals("pick(value: String)", active(overloads))
          val extension = help(""".decorated("receiver", """)
          assertEquals("decorated(String, suffix: String)", active(extension))
          assertEquals(1, extension.activeParameter)
          assertEquals("LibraryApi()", active(help("new LibraryApi()")))
          val nested = help(""".load("nested")""")
          assertEquals("load(value: String)", active(nested))
          assertEquals(0, nested.activeParameter)
          val nextArgument = help(""".decorated(identity("left"),""")
          assertEquals("decorated(String, suffix: String)", active(nextArgument))
          assertEquals(1, nextArgument.activeParameter)
          val request = params(path, content, content.indexOf(".pick(") + ".pick(".length)
          val direct = requireNotNull(runAnalysis { JavaPsiServiceInterop.signatureHelp(request) })
          assertEquals("pick(value: String)", active(direct))
          request.documentRevision = 210L
          assertNull(runAnalysis { JavaPsiServiceInterop.signatureHelp(request) })
          JavaPreferences.isJavaKotlinRecognitionEnabled = false
          val javaView = help(".pick(")
          assertTrue(javaView.signatures.any { it.label == "pick(String value)" })
        }
      } finally {
        closeDocument(path)
        JavaPreferences.isJavaKotlinRecognitionEnabled = previousRecognition
      }
    }
  }

  @Test
  fun signatureHelpDiscardsResultsWhenTheResolverChangesDuringRendering() {
    val expected = SignatureHelp(emptyList(), -1, -1)
    val replacement = SignatureResolver { expected }
    val original = SignatureResolver {
      JavaPsiServiceInterop.register(replacement)
      expected
    }
    val request = params(Path.of("Signature.java"), "call()", 5)
    JavaPsiServiceInterop.register(original)
    try {
      assertNull(JavaPsiServiceInterop.signatureHelp(request))
      assertTrue(expected === JavaPsiServiceInterop.signatureHelp(request))
    } finally {
      JavaPsiServiceInterop.unregister(original)
      JavaPsiServiceInterop.unregister(replacement)
    }
  }

  private class SignatureResolver(
      private val render: () -> SignatureHelp,
  ) : JavaPsiServiceResolver {
    override fun expandSelection(params: ExpandSelectionParams): Range? = null
    override fun definition(params: DefinitionParams): JavaPsiDefinitionTarget? = null
    override fun references(params: ReferenceParams): ReferenceResult? = null
    override fun hover(params: DefinitionParams): JavaPsiHoverTarget? = null
    override fun signatureHelp(params: SignatureHelpParams): SignatureHelp = render()
    override fun complete(
        params: CompletionParams,
        module: ModuleProject?,
        matchAllLowerCase: Boolean,
    ): CompletionResult? = null
  }

  private fun params(path: Path, content: String, offset: Int): SignatureHelpParams =
      SignatureHelpParams(path, LineIndex.from(content).indexToPosition(offset), content, ICancelChecker.NOOP)
          .apply {
            documentVersion = 3
            documentRevision = 211L
          }

  private fun active(help: SignatureHelp): String {
    assertTrue("Expected an active signature: $help", help.activeSignature in help.signatures.indices)
    return help.signatures[help.activeSignature].label
  }
}
