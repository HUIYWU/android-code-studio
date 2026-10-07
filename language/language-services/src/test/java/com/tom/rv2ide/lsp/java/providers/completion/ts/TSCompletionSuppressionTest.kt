package com.tom.rv2ide.lsp.java.providers.completion.ts

import com.itsaky.androidide.treesitter.TreeSitter
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TSCompletionSuppressionTest {
  companion object {
    @Volatile private var nativeLoadAttempted = false
    @Volatile private var nativeLoadFailure: Throwable? = null

    @Synchronized
    private fun loadNativeLibraries(): Throwable? {
      if (!nativeLoadAttempted) {
        nativeLoadAttempted = true
        nativeLoadFailure =
            try {
              TreeSitter.loadLibrary()
              System.loadLibrary("tree-sitter-java")
              null
            } catch (error: Throwable) {
              error
            }
      }
      return nativeLoadFailure
    }
  }

  @Test
  fun suppressesCommentContext() {
    val code = "class A { void test() { // hello\n int value = 1; } }"
    assertEquals(
        TSCompletionSuppressionReason.COMMENT,
        classify(code, code.indexOf("hello")),
    )
  }

  @Test
  fun suppressesStringLiteralContext() {
    val code = "class A { void test() { String s = \"abc\"; } }"
    assertEquals(
        TSCompletionSuppressionReason.STRING_LITERAL,
        classify(code, code.indexOf("abc")),
    )
  }

  @Test
  fun suppressesCharacterLiteralContext() {
    val code = "class A { char value = 'x'; }"
    assertEquals(
        TSCompletionSuppressionReason.CHARACTER_LITERAL,
        classify(code, code.indexOf("x")),
    )
  }

  @Test
  fun ordinaryJavaCodeDoesNotSuppressCompletion() {
    val code = "class A { void test() { int value = 1; } }"
    assertEquals(
        TSCompletionSuppressionReason.NONE,
        classify(code, code.indexOf("value")),
    )
  }

  private fun classify(code: String, offset: Int): TSCompletionSuppressionReason {
    val line = code.substring(0, offset).count { it == '\n' }
    val lastLineBreak = code.lastIndexOf('\n', offset - 1)
    val column = offset - lastLineBreak - 1
    val loadFailure = loadNativeLibraries()
    assertNull(
        "Unable to explicitly load Tree-sitter core/Java native libraries: " +
            nativeFailureDescription(loadFailure),
        loadFailure,
    )
    return TSCompletionSuppression.classify(
        Paths.get("/tmp/A.java"), code, offset.toLong(), line, column)
  }

  private fun nativeFailureDescription(error: Throwable?): String {
    if (error == null) return "none"
    return error.javaClass.name + ": " + (error.message ?: error.toString())
  }
}
