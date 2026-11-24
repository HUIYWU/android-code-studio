package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.diagnostics.JavaUnusedThrowsDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceList
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil

internal object JavaPsiUnusedThrowsGeneration {
  fun generate(
      file: PsiJavaFile,
      diagnostic: JavaUnusedThrowsDiagnostic,
      cancelChecker: ICancelChecker,
  ): JavaPsiTextEdit? {
    val content = file.text
    if (diagnostic.referenceStart < 0 || diagnostic.referenceEnd > content.length ||
        diagnostic.referenceEnd <= diagnostic.referenceStart || diagnostic.methodStart < 0 ||
        diagnostic.methodEnd > content.length || diagnostic.methodEnd <= diagnostic.methodStart ||
        diagnostic.ownerStart < 0 || diagnostic.ownerEnd > content.length ||
        diagnostic.ownerEnd <= diagnostic.ownerStart) return null
    val referenceLeaf = file.findElementAt(diagnostic.referenceStart) ?: return null
    val reference = PsiTreeUtil.getParentOfType(
        referenceLeaf, PsiJavaCodeReferenceElement::class.java, false,
    ) ?: return null
    if (reference.textRange.startOffset != diagnostic.referenceStart ||
        reference.textRange.endOffset != diagnostic.referenceEnd ||
        reference.text != diagnostic.referenceText) return null
    val throwsList = PsiTreeUtil.getParentOfType(reference, PsiReferenceList::class.java, false)
        ?: return null
    if (throwsList.role != PsiReferenceList.Role.THROWS_LIST) return null
    val method = PsiTreeUtil.getParentOfType(throwsList, PsiMethod::class.java, false) ?: return null
    val owner = method.containingClass ?: return null
    if (method.textRange.startOffset != diagnostic.methodStart ||
        method.textRange.endOffset != diagnostic.methodEnd ||
        owner.textRange.startOffset != diagnostic.ownerStart ||
        owner.textRange.endOffset != diagnostic.ownerEnd ||
        owner.qualifiedName != diagnostic.ownerQualifiedName ||
        method.name != diagnostic.methodName || method.isConstructor != diagnostic.constructor) return null
    val parameters = method.parameterList.parameters.map {
      TypeConversionUtil.erasure(it.type).canonicalText
    }
    if (parameters != diagnostic.erasedParameterTypes) return null
    val resolved = reference.resolve() as? PsiClass ?: return null
    if (resolved.qualifiedName != diagnostic.exceptionQualifiedName) return null
    val references = throwsList.referenceElements
    val index = references.indexOfFirst {
      it.textRange.startOffset == diagnostic.referenceStart &&
          it.textRange.endOffset == diagnostic.referenceEnd
    }
    if (index < 0) return null
    cancelChecker.abortIfCancelled()
    val range = removalRange(content, method, throwsList, references, index) ?: return null
    val lineIndex = LineIndex.from(content)
    val start = lineIndex.indexToPosition(range.first)
    val end = lineIndex.indexToPosition(range.second)
    return JavaPsiTextEdit(
        diagnostic.file.normalize(), diagnostic.documentVersion, diagnostic.documentRevision,
        start.line, start.column, end.line, end.column, "",
    )
  }

  private fun removalRange(
      content: String,
      method: PsiMethod,
      throwsList: PsiReferenceList,
      references: Array<PsiJavaCodeReferenceElement>,
      index: Int,
  ): Pair<Int, Int>? {
    val current = references[index].textRange
    if (references.size == 1) {
      val start = method.parameterList.textRange.endOffset
      if (throwsList.textRange.startOffset < start || current.startOffset < start) return null
      return start to current.endOffset
    }
    if (index == 0) {
      val next = references[1].textRange
      var end = current.endOffset
      while (end < next.startOffset && content[end].isWhitespace()) end++
      if (end >= next.startOffset || content[end] != ',') return null
      end++
      while (end < next.startOffset && content[end].isWhitespace()) end++
      return current.startOffset to end
    }
    val previous = references[index - 1].textRange
    var start = current.startOffset - 1
    while (start > previous.endOffset && content[start].isWhitespace()) start--
    if (start < previous.endOffset || content[start] != ',') return null
    return start to current.endOffset
  }
}
