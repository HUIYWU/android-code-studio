package com.tom.rv2ide.language.services.java.providers

import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path

/**
 * Single entry point for Java-to-Kotlin reference lookup.
 *
 * The returned result already merges Kotlin usages with Java source references. Java elements that
 * do not map to a Kotlin source declaration keep using the javac provider.
 */
object KotlinSourceReferences {

  @JvmStatic
  fun find(
      javaFile: Path,
      zeroBasedLine: Int,
      zeroBasedColumn: Int,
      includeDeclaration: Boolean,
      cancelChecker: ICancelChecker,
  ): ReferenceResult? {
    if (zeroBasedLine < 0 || zeroBasedColumn < 0) return null
    val offset =
        LineIndex.from(FileManager.getDocumentContents(javaFile))
            .lineColumnToIndex(zeroBasedLine, zeroBasedColumn)
    return JavaPsiInterop.kotlinReferences(javaFile, offset, includeDeclaration, cancelChecker)
  }
}
