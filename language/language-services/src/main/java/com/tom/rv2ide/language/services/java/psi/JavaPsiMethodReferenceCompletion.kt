package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceUtil
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiClassImplUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil

internal object JavaPsiMethodReferenceCompletion {
  fun complete(
      file: PsiJavaFile,
      offset: Int,
      recognizeKotlin: Boolean,
      cancelChecker: ICancelChecker,
  ): CompletionResult? {
    val context = contextAt(file, offset) ?: return null
    cancelChecker.abortIfCancelled()
    val reference = context.reference
    val qualifierType = PsiMethodReferenceUtil.getQualifierType(reference)
    val qualifierResult = PsiMethodReferenceUtil.getQualifierResolveResult(reference)
    val typeQualifier = qualifierResult.isReferenceTypeQualified
    if (qualifierType is PsiArrayType) {
      val items = ArrayList<CompletionItem>(1)
      if (typeQualifier) JavaPsiCompletionItems.keyword(items, "new", context.prefix)
      return CompletionResult(items)
    }
    val owner = qualifierResult.containingClass ?: return CompletionResult.EMPTY
    if (reference.isConstructor) {
      if (!typeQualifier || qualifierType !is PsiClassType) return CompletionResult.EMPTY
      if (!recognizeKotlin && !JavaPsiCompletionItems.isJavaOwner(owner)) return CompletionResult.EMPTY
      val items = ArrayList<CompletionItem>()
      JavaPsiCompletionItems.keyword(items, "new", context.prefix)
      return CompletionResult(items)
    }
    if (!recognizeKotlin && !JavaPsiCompletionItems.isJavaOwner(owner)) return CompletionResult.EMPTY
    val ownerSubstitutor = qualifierResult.substitutor

    val groups = LinkedHashMap<String, JavaPsiCompletionItems.MethodGroup>()
    for (method in PsiClassImplUtil.getAllMethods(owner)) {
      cancelChecker.abortIfCancelled()
      if (method.isConstructor) continue
      val declaringClass = method.containingClass ?: continue
      val isStatic = method.hasModifierProperty(PsiModifier.STATIC)
      if (!typeQualifier && isStatic) continue
      if (typeQualifier && isStatic && declaringClass.isInterface && declaringClass != owner) continue
      if (!recognizeKotlin && !JavaPsiCompletionItems.isJavaOwner(declaringClass)) continue
      val match = groups[method.name]?.match ?: CompletionItem.matchLevel(method.name, context.prefix)
      if (match == MatchLevel.NO_MATCH || !PsiUtil.isAccessible(method, reference, owner)) continue
      val substitutor = TypeConversionUtil.getSuperClassSubstitutor(
          declaringClass, owner, ownerSubstitutor,
      )
      val group = groups[method.name]
      if (group == null) {
        groups[method.name] = JavaPsiCompletionItems.MethodGroup(method, declaringClass, substitutor, match).apply {
          add(method, substitutor)
        }
      } else {
        group.add(method, substitutor)
      }
    }

    val items = ArrayList<CompletionItem>(groups.size + if (typeQualifier) 1 else 0)
    for (group in groups.values) {
      cancelChecker.abortIfCancelled()
      items += group.item(context.prefix, addParentheses = false)
    }
    if (typeQualifier) JavaPsiCompletionItems.keyword(items, "new", context.prefix)
    return CompletionResult(items)
  }

  private fun contextAt(file: PsiJavaFile, offset: Int): Context? {
    if (offset !in 1..file.textLength) return null
    val leaf = file.findElementAt(offset - 1) ?: return null
    val place = if (leaf is PsiWhiteSpace) PsiTreeUtil.prevCodeLeaf(leaf) else leaf
    val reference = PsiTreeUtil.getParentOfType(place, PsiMethodReferenceExpression::class.java, false) ?: return null
    val doubleColon = PsiTreeUtil.findChildrenOfType(reference, PsiJavaToken::class.java)
        .firstOrNull { it.tokenType == JavaTokenType.DOUBLE_COLON } ?: return null
    if (offset < doubleColon.textRange.endOffset || offset > reference.textRange.endOffset) return null

    val nameElement = reference.referenceNameElement
    val prefix = when {
      nameElement is PsiIdentifier &&
          offset in nameElement.textRange.startOffset..nameElement.textRange.endOffset ->
        nameElement.text.substring(0, offset - nameElement.textRange.startOffset)
      nameElement is PsiKeyword && nameElement.text == "new" &&
          offset in nameElement.textRange.startOffset..nameElement.textRange.endOffset ->
        nameElement.text.substring(0, offset - nameElement.textRange.startOffset)
      offset >= doubleColon.textRange.endOffset &&
          reference.containingFile.text.substring(doubleColon.textRange.endOffset, offset).all(Char::isWhitespace) -> ""
      else -> return null
    }
    return Context(reference, prefix)
  }

  private data class Context(val reference: PsiMethodReferenceExpression, val prefix: String)
}
