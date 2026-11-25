package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.diagnostics.JavaUncheckedWarningDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayInitializerMemberValue
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression

internal object JavaPsiUncheckedWarningGeneration {
  fun generate(
      file: PsiJavaFile,
      diagnostic: JavaUncheckedWarningDiagnostic,
      cancelChecker: ICancelChecker,
  ): JavaPsiTextEdit? {
    cancelChecker.abortIfCancelled()
    val content = file.text
    if (diagnostic.ownerStart < 0 || diagnostic.ownerEnd > content.length ||
        diagnostic.ownerEnd <= diagnostic.ownerStart || diagnostic.highlightStart < 0 ||
        diagnostic.highlightEnd > content.length || diagnostic.highlightEnd <= diagnostic.highlightStart) {
      return null
    }
    val leaf = file.findElementAt(diagnostic.ownerStart) ?: return null
    val owner = ownerAt(leaf, diagnostic.ownerKind) ?: return null
    val ownerRange = owner.textRange
    if (ownerRange.startOffset != diagnostic.ownerStart || ownerRange.endOffset != diagnostic.ownerEnd ||
        diagnostic.highlightStart < ownerRange.startOffset || diagnostic.highlightEnd > ownerRange.endOffset) {
      return null
    }
    val modifierOwner = owner as? PsiModifierListOwner ?: return null
    val suppression = modifierOwner.modifierList?.annotations.orEmpty().firstOrNull { annotation ->
      annotation.qualifiedName == "java.lang.SuppressWarnings" ||
          annotation.nameReferenceElement?.text == "SuppressWarnings"
    }
    if (suppression != null) {
      return extendSuppression(file, diagnostic, suppression, cancelChecker)
    }
    val insertionOffset = modifierOwner.modifierList?.textRange?.startOffset ?: ownerRange.startOffset
    val index = LineIndex.from(content)
    val position = index.indexToPosition(insertionOffset)
    val newText = if (diagnostic.ownerKind == JavaUncheckedWarningDiagnostic.OwnerKind.PARAMETER ||
        diagnostic.ownerKind == JavaUncheckedWarningDiagnostic.OwnerKind.LOCAL_VARIABLE) {
      "@SuppressWarnings(\"unchecked\") "
    } else {
      val lineStart = content.lastIndexOf('\n', insertionOffset - 1).let { if (it < 0) 0 else it + 1 }
      val indent = content.substring(lineStart, insertionOffset).takeWhile { it == ' ' || it == '\t' }
      val newline = if (content.contains("\r\n")) "\r\n" else "\n"
      "@SuppressWarnings(\"unchecked\")$newline$indent"
    }
    return JavaPsiTextEdit(
        diagnostic.file.normalize(), diagnostic.documentVersion, diagnostic.documentRevision,
        position.line, position.column, position.line, position.column, newText,
    )
  }

  private fun extendSuppression(
      file: PsiJavaFile,
      diagnostic: JavaUncheckedWarningDiagnostic,
      annotation: PsiAnnotation,
      cancelChecker: ICancelChecker,
  ): JavaPsiTextEdit? {
    cancelChecker.abortIfCancelled()
    val attributes = annotation.parameterList.attributes
    if (attributes.isEmpty()) {
      val offset = annotation.parameterList.textRange.startOffset + 1
      return edit(file, diagnostic, offset, offset, "\"unchecked\"")
    }
    if (attributes.size != 1) return null
    val value = attributes[0].value ?: return null
    if (value is PsiArrayInitializerMemberValue) {
      if (value.initializers.any { (it as? PsiLiteralExpression)?.value == "unchecked" }) return null
      val offset = value.textRange.endOffset - 1
      val text = if (value.initializers.isEmpty()) "\"unchecked\"" else ", \"unchecked\""
      return edit(file, diagnostic, offset, offset, text)
    }
    val literal = value as? PsiLiteralExpression ?: return null
    val stringValue = literal.value as? String ?: return null
    if (stringValue == "unchecked") return null
    val escaped = stringValue.replace("\\", "\\\\").replace("\"", "\\\"")
    return edit(
        file, diagnostic, literal.textRange.startOffset, literal.textRange.endOffset,
        "{\"$escaped\", \"unchecked\"}",
    )
  }

  private fun edit(
      file: PsiJavaFile,
      diagnostic: JavaUncheckedWarningDiagnostic,
      start: Int,
      end: Int,
      text: String,
  ): JavaPsiTextEdit {
    val index = LineIndex.from(file.text)
    val begin = index.indexToPosition(start)
    val finish = index.indexToPosition(end)
    return JavaPsiTextEdit(
        diagnostic.file.normalize(), diagnostic.documentVersion, diagnostic.documentRevision,
        begin.line, begin.column, finish.line, finish.column, text,
    )
  }

  private fun ownerAt(
      leaf: PsiElement,
      kind: JavaUncheckedWarningDiagnostic.OwnerKind,
  ): PsiElement? {
    var current: PsiElement? = leaf
    while (current != null) {
      val matches = when (kind) {
        JavaUncheckedWarningDiagnostic.OwnerKind.CLASS -> current is PsiClass
        JavaUncheckedWarningDiagnostic.OwnerKind.METHOD,
        JavaUncheckedWarningDiagnostic.OwnerKind.CONSTRUCTOR ->
          current is PsiMethod && (kind != JavaUncheckedWarningDiagnostic.OwnerKind.CONSTRUCTOR || current.isConstructor)
        JavaUncheckedWarningDiagnostic.OwnerKind.FIELD -> current is PsiField
        JavaUncheckedWarningDiagnostic.OwnerKind.PARAMETER -> current is PsiParameter
        JavaUncheckedWarningDiagnostic.OwnerKind.LOCAL_VARIABLE -> current is PsiLocalVariable
      }
      if (matches) return current
      current = current.parent
    }
    return null
  }
}