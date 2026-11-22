package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiCaseLabelElementList
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor
import org.jetbrains.kotlin.com.intellij.psi.PsiSwitchLabelStatementBase
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil

internal object JavaPsiSwitchCompletion {
  fun complete(
      file: PsiJavaFile,
      offset: Int,
      recognizeKotlin: Boolean,
      cancelChecker: ICancelChecker,
  ): CompletionResult? {
    if (offset !in 1..file.textLength) return null
    val leaf = file.findElementAt(offset - 1) ?: return null
    val token = (if (leaf is PsiWhiteSpace) PsiTreeUtil.prevCodeLeaf(leaf) else leaf) as? PsiJavaToken
        ?: return null

    val prefix: String
    val label = when (token.tokenType) {
      JavaTokenType.IDENTIFIER -> {
        if (leaf !== token) return null
        val reference = token.parent as? PsiReferenceExpression ?: return null
        if (reference.referenceNameElement !== token || reference.qualifierExpression != null) return null
        val list = reference.parent as? PsiCaseLabelElementList ?: return null
        prefix = token.text.substring(0, offset - token.textRange.startOffset)
        list.parent as? PsiSwitchLabelStatementBase ?: return null
      }
      JavaTokenType.CASE_KEYWORD -> {
        if (leaf !is PsiWhiteSpace) return null
        prefix = ""
        token.parent as? PsiSwitchLabelStatementBase ?: return null
      }
      JavaTokenType.COMMA -> {
        val list = token.parent as? PsiCaseLabelElementList ?: return null
        prefix = ""
        list.parent as? PsiSwitchLabelStatementBase ?: return null
      }
      else -> return null
    }

    cancelChecker.abortIfCancelled()
    val selectorType = label.enclosingSwitchBlock?.expression?.type as? PsiClassType ?: return null
    val enumClass = selectorType.resolve() ?: return null
    if (!enumClass.isEnum) return null
    if (!recognizeKotlin && !JavaPsiCompletionItems.isJavaOwner(enumClass)) return CompletionResult.EMPTY

    val items = ArrayList<CompletionItem>()
    for (field in enumClass.fields) {
      cancelChecker.abortIfCancelled()
      val constant = field as? PsiEnumConstant ?: continue
      val match = CompletionItem.matchLevel(constant.name, prefix)
      if (match == MatchLevel.NO_MATCH) continue
      items += JavaPsiCompletionItems.field(constant, enumClass, PsiSubstitutor.EMPTY, match)
    }
    return CompletionResult(items)
  }
}
