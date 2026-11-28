package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Files
import org.jetbrains.kotlin.com.intellij.java.JavaBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiFileIoInspectionCapabilityTest {
  @Test
  fun publishesOfficialFileCreationAndTextIoWarningsAcrossEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiFileIoInspection.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app;\nclass JavaPsiFileIoInspection {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import java.io.File;
          import java.io.FileInputStream;
          import java.io.FileNotFoundException;
          import java.io.FileOutputStream;
          import java.io.IOException;
          import java.nio.charset.StandardCharsets;
          import java.nio.file.Files;
          import java.nio.file.Path;

          class JavaPsiFileIoInspection {
            FileInputStream openInput(String source) throws FileNotFoundException {
              return new FileInputStream(new File(source));
            }

            FileOutputStream openAppend(String destination) throws FileNotFoundException {
              return new FileOutputStream((new File(destination)), true);
            }

            FileInputStream openRelative(File parent, String child) throws FileNotFoundException {
              return new FileInputStream(new File(parent, child));
            }

            FileInputStream openExisting(File source) throws FileNotFoundException {
              return new FileInputStream(source);
            }

            @SuppressWarnings("RedundantFileCreation")
            FileInputStream suppressedOpen(String source) throws FileNotFoundException {
              return new FileInputStream(new File(source));
            }

            String readText(Path readPath) throws IOException {
              return new String(Files.readAllBytes(readPath), StandardCharsets.UTF_8);
            }

            void writeText(Path writePath, String text) throws IOException {
              Files.write(writePath, text.getBytes(StandardCharsets.ISO_8859_1));
            }

            String readWithDefaultCharset(Path path) throws IOException {
              return new String(Files.readAllBytes(path));
            }

            void writeBinary(Path path, byte[] data) throws IOException {
              Files.write(path, data);
            }

            @SuppressWarnings("ReadWriteStringCanBeUsed")
            String suppressedRead(Path path) throws IOException {
              return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
          }
      """.trimIndent()
      val fileCreationMessage = JavaBundle.message("inspection.redundant.file.creation.description")
          .replace("<code>#ref</code>", "'new File'").replace(" #loc", "")
      val input = Warning("RedundantFileCreation", fileCreationMessage, "new FileInputStream(", "new File")
      val append = input.copy(prefix = "new FileOutputStream((")
      val read = Warning(
          "ReadWriteStringCanBeUsed", JavaBundle.message("inspection.message.can.be.replaced.with.files.readstring"),
          "return new ", "String", "(Files.readAllBytes(readPath)",
      )
      val write = Warning(
          "ReadWriteStringCanBeUsed", JavaBundle.message("inspection.message.can.be.replaced.with.files.writestring"),
          "Files.", "write", "(writePath, text.getBytes(",
      )
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 2701L)
        withRuntime(workspace) { runtime ->
          fun diagnostics(version: Int, revision: Long) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }

          runAnalysis { runtime.onFileOpened(path) }
          assertWarnings(content, diagnostics(1, 2701L), input, append, read, write)

          val edited = content.replaceFirst("new FileInputStream(new File(source))", "new FileInputStream(source)")
              .replace(
                  "return new String(Files.readAllBytes(readPath), StandardCharsets.UTF_8);",
                  "byte[] data = Files.readAllBytes(readPath);\n    return new String(data, StandardCharsets.UTF_8);",
              )
          openDocument(path, edited, version = 2, revision = 2702L)
          runAnalysis { runtime.onFileContentChanged(path) }
          assertWarnings(edited, diagnostics(2, 2702L), append, write)
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  private fun assertWarnings(content: String, diagnostics: List<DiagnosticItem>, vararg expected: Warning) {
    assertEquals(emptyList<DiagnosticItem>(), diagnostics.filter { it.severity == DiagnosticSeverity.ERROR })
    val warnings = diagnostics.filter { it.code == "RedundantFileCreation" || it.code == "ReadWriteStringCanBeUsed" }
    val index = LineIndex.from(content)
    val items = expected.map { (code, message, prefix, text, suffix) ->
      val context = prefix + text + suffix
      val match = content.indexOf(context)
      check(match >= 0) { "Missing $context in fixture" }
      val start = match + prefix.length
      DiagnosticItem(
          message = message,
          code = code,
          range = Range(index.indexToPosition(start), index.indexToPosition(start + text.length)),
          source = "java-inspection",
          severity = DiagnosticSeverity.WARNING,
      )
    }
    assertEquals(items, warnings)
    assertTrue(warnings.all { it.extra.javaClass == Any::class.java })
  }

  private data class Warning(
      val code: String,
      val message: String,
      val prefix: String,
      val text: String,
      val suffix: String = "",
  )
}