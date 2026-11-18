package com.tom.rv2ide.language.services.java.providers

import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.language.services.java.providers.definition.KotlinSourceNavigation
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path

/**
 * Java-side signature help for Kotlin callables.
 *
 * The shared Analysis PSI project supplies parameter labels and overload candidates, so Kotlin
 * parameter names and types stay identical to the Kotlin service instead of showing the JVM ABI
 * signature from stubs. Constructors are answered from their Kotlin declaration too, and extension
 * receivers are shown as the leading argument that Java passes. A null result means the callee is
 * not a Kotlin callable this bridge can represent (for example a suspend function whose Java
 * arguments include an implicit continuation), so the javac-backed provider keeps answering it.
 */
object KotlinSourceSignature {

  @JvmStatic
  fun find(
      javaFile: Path,
      zeroBasedLine: Int,
      zeroBasedColumn: Int,
      cancelChecker: ICancelChecker,
  ): SignatureHelp? {
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) return null
    if (zeroBasedLine < 0 || zeroBasedColumn < 0) return null
    return JavaPsiInterop.kotlinSignature(
        javaFile,
        KotlinSourceNavigation.documentOffset(javaFile, zeroBasedLine, zeroBasedColumn),
        cancelChecker,
    )
  }
}
