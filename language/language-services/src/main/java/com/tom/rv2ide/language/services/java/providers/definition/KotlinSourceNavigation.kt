package com.tom.rv2ide.language.services.java.providers.definition

import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path

/**
 * Single entry point for Java-to-Kotlin definition lookup.
 *
 * The shared Analysis PSI project is the only recognition authority. When the PSI lookup misses,
 * this entry point returns no Kotlin definition; there is no javac-element or text-level fallback.
 */
object KotlinSourceNavigation {

  @JvmStatic
  fun findTarget(params: DefinitionParams, cancelChecker: ICancelChecker): List<Location> {
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) return emptyList()
    val position = params.position
    if (position.line < 0 || position.column < 0) return emptyList()
    return JavaPsiInterop.kotlinDefinition(
        params.file,
        documentOffset(params.file, position.line, position.column),
        cancelChecker,
    )?.let(::listOf) ?: emptyList()
  }

  internal fun documentOffset(javaFile: Path, zeroBasedLine: Int, zeroBasedColumn: Int): Int =
      LineIndex.from(FileManager.getDocumentContents(javaFile))
          .lineColumnToIndex(zeroBasedLine, zeroBasedColumn)
}
