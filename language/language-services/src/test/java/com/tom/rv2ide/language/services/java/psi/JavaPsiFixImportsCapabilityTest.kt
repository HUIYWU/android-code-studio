package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiFixImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.ModuleProject
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
class JavaPsiFixImportsCapabilityTest {
  @Test
  fun batchImportsTypesAnnotationsThrowsNestedQualifiersAndStaticMembers() {
    val ownerName = "fixture.fiximports.ImportApi"
    val api = """
        package fixture.fiximports;
        public class ImportApi {
          public static final int FIX_LIMIT = 4;
          public static int fixLength(String value) { return value.length(); }
          public static <T> T fixIdentity(T value) { return value; }
          public static class Node { public static class Leaf {} }
          public static class Other {}
          public @interface Marker {}
        }
    """.trimIndent()
    val content = """
        package fixture.consumer;
        import java.util.List; // keep-list
        import static java.lang.Math.abs; // keep-abs
        @Marker class FixImportsSubject {
          List<Date> dates;
          TimerTask task = new TimerTask() { public void run() {} };
          ImportApi.Node.Leaf qualified;
          ImportApi.Other other;
          Leaf direct;
          int limit = FIX_LIMIT;
          int qualifiedCall() { return ImportApi.fixLength("qualified"); }
          java.util.function.ToIntFunction<String> reference = ImportApi::fixLength;
          String identity() { return fixIdentity("x"); }
          int length() throws IOException { return fixLength("x") + abs(-1); }
        }
    """.trimIndent()
    withCase(content, mapOf(ownerName to api)) {
      val choices = requireNotNull(choices())
      val types = choices.types.associateBy { it.simpleName }
      assertEquals(choices.types.size, types.size)
      assertEquals(setOf("Date", "ImportApi", "Leaf", "Marker", "IOException", "TimerTask"), types.keys)
      assertEquals(setOf("java.sql.Date", "java.util.Date"), types.getValue("Date").qualifiedNames.toSet())
      val selected = mapOf(
          "Date" to "java.util.Date",
          "ImportApi" to ownerName,
          "Leaf" to "$ownerName.Node.Leaf",
          "Marker" to "$ownerName.Marker",
          "IOException" to "java.io.IOException",
          "TimerTask" to "java.util.TimerTask",
      )
      selected.forEach { (name, qualifiedName) ->
        assertTrue(name, qualifiedName in types.getValue(name).qualifiedNames)
      }
      val members = choices.staticMembers.associateBy { it.simpleName }
      assertEquals(setOf("FIX_LIMIT", "fixIdentity", "fixLength"), members.keys)
      assertFalse(members.getValue("FIX_LIMIT").isMethod)
      assertTrue(members.getValue("fixIdentity").isMethod)
      members.values.forEach { assertTrue(ownerName in it.qualifiedNames) }
      val edits = requireNotNull(generate(
          types.values.associate { it.key to selected.getValue(it.simpleName) },
          members.values.associate { it.key to ownerName },
      ))
      assertTrue(edits.isNotEmpty())
      assertTrue(edits.all { it.file == path && it.documentVersion == version && it.documentRevision == revision })
      val fixed = apply(content, edits)
      selected.values.forEach { assertTrue(fixed.contains("import $it;")) }
      members.keys.forEach { assertTrue(fixed.contains("import static $ownerName.$it;")) }
      assertTrue(fixed.contains("import java.util.List; // keep-list"))
      assertTrue(fixed.contains("import static java.lang.Math.abs; // keep-abs"))
      assertTrue(fixed.contains("ImportApi.Node.Leaf qualified;"))
       assertTrue(fixed.contains("new TimerTask()"))
      update(fixed)
      assertNoErrors()
      val resolved = requireNotNull(choices())
      assertTrue(resolved.types.isEmpty() && resolved.staticMembers.isEmpty())
      assertEquals(disk, Files.readAllBytes(path).decodeToString())
    }
  }

  @Test
  fun staticOwnerMustResolveEveryCallAndGenerationRechecksTheSelection() {
    val complete = "fixture.fiximports.Complete"
    val partial = "fixture.fiximports.Partial"
    val sources = mapOf(
        complete to """
            package fixture.fiximports;
            public class Complete {
              public static int fixPick = 2;
              public static int fixPick(String value) { return 1; }
              public static int fixPick(int value) { return value; }
            }
        """.trimIndent(),
        partial to """
            package fixture.fiximports;
            public class Partial {
              public static int fixPick(String value) { return 1; }
            }
        """.trimIndent(),
    )
    withCase("package fixture.consumer; class FixImportsSubject { int x = fixPick + fixPick(1) + fixPick(\"x\"); }", sources) {
      val choices = requireNotNull(choices()).staticMembers.filter { it.simpleName == "fixPick" }
      assertEquals(2, choices.size)
      assertEquals(2, choices.map { it.key }.toSet().size)
      choices.forEach { assertEquals(listOf(complete), it.qualifiedNames) }
      val choice = choices.single { it.isMethod }
      assertNull(generate(emptyMap(), mapOf(choice.key to partial)))
      val edits = requireNotNull(generate(emptyMap(), choices.associate { it.key to complete }))
      val fixed = apply(content, edits)
      assertEquals(1, fixed.lineSequence().count { it == "import static fixture.fiximports.Complete.fixPick;" })
      update(fixed)
      assertNoErrors()
    }
  }

  @Test
  fun doesNotStaticImportAFieldThatConflictsWithATypeQualifier() {
    val nout = "com.z.loa.util.nstutil.Nout"
    val nstf = "com.z.loa.util.Nstf"
    val sources = mapOf(
        nout to """
          package com.z.loa.util.nstutil;
          public class Nout {
            public static int Nstf;
          }
        """.trimIndent(),
        nstf to """
          package com.z.loa.util;
          public class Nstf {
            public static final String ID = "nstf";
          }
        """.trimIndent(),
    )
    withCase("package com.z.loa; class SPL { void plR() { System.out.println(Nstf.ID); } }", sources) {
      val choices = requireNotNull(choices())
      val type = choices.types.single { it.simpleName == "Nstf" }
      assertEquals(listOf(nstf), type.qualifiedNames)
      assertTrue(choices.staticMembers.none { it.simpleName == "Nstf" })
      val edits = requireNotNull(generate(mapOf(type.key to nstf)))
      val fixed = apply(content, edits)
      assertTrue(fixed.contains("import $nstf;"))
      assertFalse(fixed.contains("import static $nout.Nstf;"))
      update(fixed)
      assertNoErrors()
    }
  }

  @Test
  fun importsBinaryNestedTypesAndStaticMethods() {
    withCase("""
        package fixture.consumer;
        class FixImportsSubject {
          Entry<String, String> direct;
          Map.Entry<String, String> qualified;
          double independent = sin(0.0);
          double value = sin(PI);
        }
    """.trimIndent()) {
      val choices = requireNotNull(choices())
      val types = choices.types.associateBy { it.simpleName }
      assertTrue("java.util.Map.Entry" in types.getValue("Entry").qualifiedNames)
      assertTrue("java.util.Map" in types.getValue("Map").qualifiedNames)
      val methods = choices.staticMembers.associateBy { it.simpleName }
      assertTrue("java.lang.Math" in methods.getValue("sin").qualifiedNames)
      assertTrue("java.lang.Math" in methods.getValue("PI").qualifiedNames)
      assertNull(generate(staticNames = mapOf(methods.getValue("sin").key to "java.lang.Math")))
      val edits = requireNotNull(generate(
          mapOf(types.getValue("Entry").key to "java.util.Map.Entry", types.getValue("Map").key to "java.util.Map"),
          methods.values.associate { it.key to "java.lang.Math" },
      ))
      assertNoErrorsAfter(edits)
    }
  }

  @Test
  fun importsInheritedStaticMembersThroughAnAccessibleSubclass() {
    val owner = "fixture.fiximports.PublicApi"
    val source = """
        package fixture.fiximports;
        class HiddenBase {
          public static final int FIX_INHERITED = 3;
          public static int fixInherited() { return 1; }
        }
        public class PublicApi extends HiddenBase {}
    """.trimIndent()
    withCase("package fixture.consumer; class FixImportsSubject { int x = FIX_INHERITED + fixInherited(); }", mapOf(owner to source)) {
      val choices = requireNotNull(choices()).staticMembers
      assertEquals(setOf("FIX_INHERITED", "fixInherited"), choices.map { it.simpleName }.toSet())
      choices.forEach { assertTrue(owner in it.qualifiedNames) }
      val edits = requireNotNull(generate(staticNames = choices.associate { it.key to owner }))
      assertNoErrorsAfter(edits)
    }
  }

  @Test
  fun excludesInaccessibleTypesInvalidSuffixesInstanceMembersWritesAndImports() {
    val sources = mapOf(
        "fixture.fiximports.InvalidApi" to """
            package fixture.fiximports;
            public class InvalidApi {
              public static int FIX_WRITE;
              private static int fixDenied() { return 1; }
              public int fixInstance() { return 1; }
              public static int fixWrong(int value) { return value; }
              public static class Node {}
              private static class PrivateLeaf {}
            }
        """.trimIndent(),
        "fixture.fiximports.Hidden" to "package fixture.fiximports; class Hidden {}",
        "fixture.fiximports.NotAnnotation" to "package fixture.fiximports; public class NotAnnotation {}",
    )
    val content = """
        package fixture.consumer;
        import missing.NotThere;
        @NotAnnotation class FixImportsSubject {
          Date date;
          Hidden hidden;
          PrivateLeaf privateLeaf;
          InvalidApi.Node.Missing badSuffix;
          java.util.Missing fullyQualified;
          void use() {
            FIX_WRITE = 1;
            fixDenied();
            fixInstance();
            fixWrong(true);
            new Object().fixMissing();
          }
        }
    """.trimIndent()
    withCase(content, sources) {
      val choices = requireNotNull(choices())
      assertEquals(listOf("Date"), choices.types.map { it.simpleName })
      assertTrue(choices.staticMembers.isEmpty())
      assertNull(generate(mapOf("InvalidApi" to "fixture.fiximports.InvalidApi")))
      assertNull(generate(mapOf("Date" to "java.util.List")))
      assertTrue(requireNotNull(generate()).isEmpty())
    }
  }

  @Test
  fun rejectsStaleSnapshotsAndCancellationAndRefreshesStaticOwnersFromOverlay() {
    val owner = "fixture.fiximports.RefreshApi"
    val source = "package fixture.fiximports; public class RefreshApi { public static int fixRefresh() { return 1; } }"
    withCase("package fixture.consumer; class FixImportsSubject { int x = fixRefresh(); }", mapOf(owner to source)) {
      assertEquals(1, diagnostics().count { it.source == "java-codeserver" && it.code == "call.ambiguous.no.match" })
      val choice = requireNotNull(choices()).staticMembers.single { it.simpleName == "fixRefresh" }
      assertEquals(listOf(owner), choice.qualifiedNames)
      assertNull(choices(requestVersion = version - 1))
      assertNull(choices(requestRevision = revision - 1))
      assertNull(generate(staticNames = mapOf(choice.key to owner), requestVersion = version - 1))
      assertNull(generate(staticNames = mapOf(choice.key to owner), requestRevision = revision - 1))
      for (operation in listOf<() -> Any?>(
          { choices(checker = ICancelChecker.CANCELLED) },
          { generate(staticNames = mapOf(choice.key to owner), checker = ICancelChecker.CANCELLED) },
      )) {
        val failure = assertThrows(CompletionException::class.java) { operation() }
        assertTrue(failure.cause is CancellationException)
      }
      updateSource(owner, source.replace("fixRefresh", "fixRenamed"))
      assertTrue(requireNotNull(choices()).staticMembers.none { it.simpleName == "fixRefresh" })
      assertNull(generate(staticNames = mapOf(choice.key to owner)))
      updateSource(owner, source)
      assertTrue(owner in requireNotNull(choices()).staticMembers.single { it.simpleName == "fixRefresh" }.qualifiedNames)
      val oldVersion = version
      val oldRevision = revision
      update(content + "\n")
      assertNull(generate(staticNames = mapOf(choice.key to owner), requestVersion = oldVersion, requestRevision = oldRevision))
      assertNoErrorsAfter(requireNotNull(generate(staticNames = mapOf(choice.key to owner))))
      assertEquals(disk, Files.readAllBytes(path).decodeToString())
    }
  }

  @Test
  fun preservesCommentsHeadersAndImportGroupsWithLfCrLfAndCr() {
    val headers = listOf(
        "/* file-header */\npackage fixture.consumer; // package-comment\n\nimport java.lang.Deprecated; // import-comment\n",
        "/* file-header */\npackage fixture.consumer; // package-comment\n\nimport static java.lang.Math.abs; // import-comment\n",
        "/* file-header */\npackage fixture.consumer; // package-comment\n",
        "/* file-header */\n",
    )
    withCase("package fixture.consumer; class FixImportsSubject {}") {
      for (newline in listOf("\n", "\r\n", "\r")) {
        for (header in headers) {
          val before = (header + "/** class-doc */\nclass FixImportsSubject { Date date; double pi = PI; }").replace("\n", newline)
          update(before)
          val choices = requireNotNull(choices())
          val date = choices.types.single { it.simpleName == "Date" }
          val pi = choices.staticMembers.single { it.simpleName == "PI" }
          assertTrue("java.lang.Math" in pi.qualifiedNames)
          val edits = requireNotNull(generate(mapOf(date.key to "java.util.Date"), mapOf(pi.key to "java.lang.Math")))
          assertEquals(edits.size, edits.map { it.startLine to it.startColumn }.toSet().size)
          val fixed = apply(before, edits)
          assertTrue(fixed.startsWith("/* file-header */$newline"))
          assertTrue(fixed.contains("/** class-doc */${newline}class FixImportsSubject"))
          assertTrue(fixed.contains("import java.util.Date;$newline"))
          assertTrue(fixed.contains("import static java.lang.Math.PI;$newline"))
          if (header.contains("// package-comment")) assertTrue(fixed.contains("package fixture.consumer; // package-comment$newline"))
          if (header.contains("// import-comment")) assertTrue(fixed.contains("; // import-comment$newline"))
          val withoutNewlines = fixed.replace(newline, "")
          assertFalse(withoutNewlines.contains('\r') || withoutNewlines.contains('\n'))
          assertNoErrorsAfter(edits)
        }
      }
    }
  }

  private fun withCase(content: String, sources: Map<String, String> = emptyMap(), action: Case.() -> Unit) {
    withWorkspace {
      val path = fixturePath("app/src/main/java/fixture/consumer/FixImportsSubject.java")
      val sourcePaths = sources.keys.associateWith { name -> fixturePath("app/src/main/java/${name.replace('.', '/')}.java") }
      val disk = "package fixture.consumer; class FixImportsSubject {}"
      val files = sourcePaths.map { (name, sourcePath) -> sourcePath to sources.getValue(name) }.toMap() + (path to disk)
      val previous = files.keys.associateWith { if (Files.exists(it)) Files.readAllBytes(it) else null }
      try {
        files.forEach { (file, text) ->
          Files.createDirectories(file.parent)
          Files.write(file, text.toByteArray())
          openDocument(file, if (file == path) content else text, version = 1, revision = 3001L)
        }
        withRuntime(workspace) { runtime ->
          runAnalysis {
            files.keys.forEach(runtime::onFileCreated)
            files.keys.forEach(runtime::onFileOpened)
          }
          val module = requireNotNull(workspace.findModuleForFile(path))
          sourcePaths.values.forEach { sourcePath ->
            val sourceRoot = requireNotNull(module.findSourceRoot(sourcePath.toFile()))
            module.compileJavaSourceClasses.append(sourcePath, sourceRoot)
          }
          module.bumpSourceIndexVersion()
          Case(path, module, disk, content, sourcePaths) { file, text, version, revision ->
            openDocument(file, text, version, revision)
            runAnalysis { runtime.onFileContentChanged(file) }
          }.action()
        }
      } finally {
        files.keys.forEach { closeDocument(it) }
        previous.forEach { (file, bytes) ->
          if (bytes == null) Files.deleteIfExists(file) else Files.write(file, bytes)
        }
      }
    }
  }

  private class Case(
      val path: Path,
      val module: ModuleProject,
      val disk: String,
      var content: String,
      val sourcePaths: Map<String, Path>,
      val replaceDocument: (Path, String, Int, Long) -> Unit,
  ) {
    var version = 1
    var revision = 3001L
    private var sourceVersion = 1

    fun choices(
        checker: ICancelChecker = ICancelChecker.NOOP,
        requestVersion: Int = version,
        requestRevision: Long = revision,
    ) = runAnalysis {
      JavaPsiServiceInterop.fixImports(JavaPsiFixImportsParams(path, requestVersion, requestRevision, module, checker))
    }

    fun generate(
        types: Map<String, String> = emptyMap(),
        staticNames: Map<String, String> = emptyMap(),
        checker: ICancelChecker = ICancelChecker.NOOP,
        requestVersion: Int = version,
        requestRevision: Long = revision,
    ) = runAnalysis {
      JavaPsiServiceInterop.generateImports(
          JavaPsiGenerateImportsParams(path, requestVersion, requestRevision, types, staticNames, checker),
      )
    }

    fun update(text: String) {
      version++
      revision++
      content = text
      replaceDocument(path, text, version, revision)
    }

    fun updateSource(name: String, text: String) {
      sourceVersion++
      replaceDocument(sourcePaths.getValue(name), text, sourceVersion, 4000L + sourceVersion)
    }

    fun assertNoErrorsAfter(edits: List<JavaPsiTextEdit>) {
      update(apply(content, edits))
      assertNoErrors()
    }

    fun diagnostics() = runAnalysis {
      when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
        is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
        JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
        is JavaDiagnosticsInterop.Result.Failure -> throw result.error
      }
    }

    fun assertNoErrors() {
      val errors = diagnostics().filter { it.severity == DiagnosticSeverity.ERROR }
      assertTrue(errors.toString(), errors.isEmpty())
    }
  }

  companion object {
    private fun apply(content: String, edits: List<JavaPsiTextEdit>): String {
      val index = LineIndex.from(content)
      return edits.sortedWith(compareByDescending<JavaPsiTextEdit> { it.startLine }.thenByDescending { it.startColumn })
          .fold(content) { current, edit ->
            current.replaceRange(
                index.lineColumnToIndex(edit.startLine, edit.startColumn),
                index.lineColumnToIndex(edit.endLine, edit.endColumn),
                edit.newText,
            )
          }
    }
  }
}
