package com.tom.rv2ide.language.services.java.interop

import com.tom.rv2ide.progress.ICancelChecker

import java.nio.file.Path
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile

interface JavaPsiFiles {
  fun file(path: Path): PsiJavaFile?

  fun forEach(
      include: (Path, String) -> Boolean,
      action: (Path, PsiJavaFile) -> Unit,
  ): Boolean
}

interface JavaPsiProjectAccess {
  fun sourcePath(file: PsiFile): Path?

  fun isProjectSource(path: Path): Boolean

  fun sourceFilesFor(file: Path): List<Path>

  fun nestedTypeNames(
      file: Path,
      documentVersion: Int,
      documentRevision: Long,
      simpleNames: Set<String>,
      cancelChecker: ICancelChecker,
  ): Map<String, List<String>>?

  fun staticMemberOwnerNames(
      file: Path,
      documentVersion: Int,
      documentRevision: Long,
      memberNames: Set<String>,
      cancelChecker: ICancelChecker,
  ): Map<String, List<String>>?

  fun <T> withJavaSourceFiles(
      requestFile: Path,
      requestedVersion: Int,
      requestedRevision: Long,
      sourceFiles: Collection<Path>,
      cancelChecker: ICancelChecker,
      action: (JavaPsiFiles) -> T?,
  ): T?

  fun <T> withCurrentJavaFile(
      file: Path,
      requestedVersion: Int,
      requestedRevision: Long,
      cancelChecker: ICancelChecker,
      action: (PsiJavaFile) -> T?,
  ): T?
}
