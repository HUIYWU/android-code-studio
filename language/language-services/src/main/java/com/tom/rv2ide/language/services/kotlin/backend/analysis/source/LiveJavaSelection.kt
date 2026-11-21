package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiPackageStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiTryStatement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil

internal fun expandSelectionInJavaPsi(
    file: PsiJavaFile,
    selection: Range,
    cancelChecker: ICancelChecker,
): Range? {
  cancelChecker.abortIfCancelled()
  val text = file.text
  val index = LineIndex.from(text)
  val fileRange = rangeFor(file, index)
  if (selection == fileRange) return null

  val start = index.lineColumnToIndex(selection.start.line, selection.start.column)
  val end = index.lineColumnToIndex(selection.end.line, selection.end.column)
  cancelChecker.abortIfCancelled()
  val startElement = elementAt(file, start, text.length)
  val endElement = elementAt(file, if (end > start) end - 1 else end, text.length)
  cancelChecker.abortIfCancelled()
  val common = PsiTreeUtil.findCommonParent(startElement, endElement) ?: file
  cancelChecker.abortIfCancelled()
  PsiTreeUtil.getParentOfType(common, PsiMethod::class.java, true)?.let { method ->
    cancelChecker.abortIfCancelled()
    method.body?.let { body ->
      if (selection == rangeFor(body, index)) return rangeFor(method, index)
    }
  }

  cancelChecker.abortIfCancelled()
  PsiTreeUtil.getParentOfType(common, PsiTryStatement::class.java, true)?.let { statement ->
    val tryBlock = statement.tryBlock
    val finallyBlock = statement.finallyBlock
    if (tryBlock != null && selection == rangeFor(tryBlock, index) ||
        finallyBlock != null && selection == rangeFor(finallyBlock, index)) {
      return rangeFor(statement, index)
    }
  }

  when (common) {
    is PsiPackageStatement -> if (common.parent === file && selection == rangeFor(common, index)) {
      return fileRange
    }
    is PsiClass -> if (common.parent === file && selection == rangeFor(common, index)) {
      return fileRange
    }
  }

  var candidate: PsiElement? = common
  while (candidate != null) {
    cancelChecker.abortIfCancelled()
    val candidateRange = rangeFor(candidate, index)
    if (selection.isSmallerThan(candidateRange)) return candidateRange
    candidate = candidate.parent
  }
  return null
}

private fun elementAt(file: PsiJavaFile, offset: Int, textLength: Int): PsiElement {
  if (textLength == 0) return file
  val position = offset.coerceIn(0, textLength - 1)
  return file.findElementAt(position) ?: file
}

private fun rangeFor(element: PsiElement, index: LineIndex): Range {
  val range = element.textRange
  val start = index.indexToPosition(range.startOffset)
  val end = index.indexToPosition(range.endOffset)
  start.index = -1
  end.index = -1
  return Range(start, end)
}
