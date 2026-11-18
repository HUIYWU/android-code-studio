package com.tom.rv2ide.language.services.java.providers

import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.language.services.java.providers.definition.KotlinSourceNavigation
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path

/**
 * Java-side hover content for Kotlin declarations.
 *
 * The shared Analysis PSI project renders the Kotlin signature and KDoc, which keeps hover
 * consistent with Kotlin navigation and with unsaved Kotlin edits. A null result means the symbol
 * is not a Kotlin declaration, so the javac-backed hover keeps answering it.
 */
object KotlinSourceHover {

  @JvmStatic
  fun find(
      javaFile: Path,
      zeroBasedLine: Int,
      zeroBasedColumn: Int,
      cancelChecker: ICancelChecker,
  ): MarkupContent? {
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) return null
    if (zeroBasedLine < 0 || zeroBasedColumn < 0) return null
    return JavaPsiInterop.kotlinHover(
        javaFile,
        KotlinSourceNavigation.documentOffset(javaFile, zeroBasedLine, zeroBasedColumn),
        cancelChecker,
    )
  }
}
