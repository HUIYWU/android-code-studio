package com.tom.rv2ide.language.services.java.providers.definition

import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.language.services.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path
import jdkx.lang.model.element.Element

/**
 * Single entry point for Java-to-Kotlin definition lookup.
 *
 * The shared Analysis PSI project is the only recognition authority. When the PSI lookup misses,
 * this entry point returns no Kotlin definition; there is no javac-element or text-level fallback.
 */
object KotlinSourceNavigation {

  @JvmStatic
  fun findResolvedSymbol(
      compiler: JavaCompilerService,
      javaFile: Path,
      zeroBasedLine: Int,
      zeroBasedColumn: Int,
      element: Element?,
      cancelChecker: ICancelChecker,
  ): Location? {
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) return null
    resolveInPsi(javaFile, zeroBasedLine, zeroBasedColumn, cancelChecker)?.let { return it }
    // PSI is the only authority; a miss must not fall back to javac-element or text scanning.
    return null
  }

  @JvmStatic
  fun findFallback(
      compiler: JavaCompilerService,
      javaFile: Path,
      zeroBasedLine: Int,
      zeroBasedColumn: Int,
      cancelChecker: ICancelChecker,
  ): List<Location> {
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) return emptyList()
    resolveInPsi(javaFile, zeroBasedLine, zeroBasedColumn, cancelChecker)?.let { return listOf(it) }
    // PSI is the only authority; a miss must not fall back to text scanning.
    return emptyList()
  }

  private fun resolveInPsi(
      javaFile: Path,
      zeroBasedLine: Int,
      zeroBasedColumn: Int,
      cancelChecker: ICancelChecker,
  ): Location? {
    if (zeroBasedLine < 0 || zeroBasedColumn < 0) return null
    val offset = documentOffset(javaFile, zeroBasedLine, zeroBasedColumn)
    return JavaPsiInterop.kotlinDefinition(javaFile, offset, cancelChecker)
  }

  internal fun documentOffset(javaFile: Path, zeroBasedLine: Int, zeroBasedColumn: Int): Int =
      LineIndex.from(FileManager.getDocumentContents(javaFile))
          .lineColumnToIndex(zeroBasedLine, zeroBasedColumn)
}
