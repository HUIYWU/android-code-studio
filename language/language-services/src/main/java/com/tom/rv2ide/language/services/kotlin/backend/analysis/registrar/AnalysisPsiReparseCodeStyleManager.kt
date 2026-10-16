package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.ChangedRangesInfo
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.Indent
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable

internal class AnalysisPsiReparseCodeStyleManager(
    private val project: Project,
) : CodeStyleManager() {
  override fun getProject(): Project = project

  override fun reformat(element: PsiElement): PsiElement = unsupported()

  override fun reformat(element: PsiElement, canChangeWhiteSpacesOnly: Boolean): PsiElement = unsupported()

  override fun reformatRange(element: PsiElement, startOffset: Int, endOffset: Int): PsiElement = unsupported()

  override fun reformatRange(
      element: PsiElement,
      startOffset: Int,
      endOffset: Int,
      canChangeWhiteSpacesOnly: Boolean,
  ): PsiElement = unsupported()

  override fun reformatText(file: PsiFile, startOffset: Int, endOffset: Int) = unsupported<Unit>()

  override fun reformatText(file: PsiFile, ranges: Collection<out TextRange>) = unsupported<Unit>()

  override fun reformatTextWithContext(file: PsiFile, ranges: ChangedRangesInfo) = unsupported<Unit>()

  override fun reformatChanges(file: PsiFile, ranges: ChangedRangesInfo) = unsupported<Unit>()

  override fun reformatTextWithContext(file: PsiFile, ranges: Collection<out TextRange>) = unsupported<Unit>()

  override fun adjustLineIndent(file: PsiFile, rangeToAdjust: TextRange) = unsupported<Unit>()

  override fun adjustLineIndent(file: PsiFile, offset: Int): Int = unsupported()

  override fun adjustLineIndent(document: Document, offset: Int): Int = unsupported()

  override fun isLineToBeIndented(file: PsiFile, offset: Int): Boolean = unsupported()

  override fun getLineIndent(file: PsiFile, offset: Int): String? = unsupported()

  override fun getLineIndent(document: Document, offset: Int): String? = unsupported()

  override fun getIndent(text: String, fileType: FileType): Indent = unsupported()

  override fun fillIndent(indent: Indent, fileType: FileType): String = unsupported()

  override fun zeroIndent(): Indent = unsupported()

  override fun reformatNewlyAddedElement(block: ASTNode, addedElement: ASTNode) = unsupported<Unit>()

  override fun isSequentialProcessingAllowed(): Boolean = unsupported()

  override fun performActionWithFormatterDisabled(action: Runnable) {
    action.run()
  }

  override fun <T : Throwable> performActionWithFormatterDisabled(action: ThrowableRunnable<T>) {
    action.run()
  }

  override fun <T> performActionWithFormatterDisabled(computation: Computable<T>): T = computation.compute()

  private fun <T> unsupported(): T = throw UnsupportedOperationException("Code formatting is unavailable in standalone analysis")
}
