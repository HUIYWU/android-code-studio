package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithInitializer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeArgumentList
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.prevLeaf
import org.jetbrains.kotlin.psi.psiUtil.siblings

private const val MAX_PROBE_TEXT = 16_384

private const val MAX_PROBE_CANDIDATES = 12

private val ALL_KEYWORDS: List<KtKeywordToken> =
    (KtTokens.KEYWORDS.types.asList() + KtTokens.SOFT_KEYWORDS.types.asList())
        .filterIsInstance<KtKeywordToken>()
        .distinctBy { it.value }
        .sortedBy { it.value }

/**
 * Keyword completion follows the IntelliJ strategy: candidates come from the lexer keyword tables,
 * are filtered by the typed prefix first, and each remaining candidate is accepted only if the real
 * parser accepts it in the reconstructed context. Reconstructing the context instead of classifying
 * positions keeps parameter lists, type parameter lists, expression bodies and statement positions on
 * one code path.
 */
internal fun keywordCompletionItems(
    prefix: String,
    file: KtFile,
    offset: Int,
    element: PsiElement?,
): List<CompletionItem>? {
  // A dot prefix means receiver completion; keyword items would replace the receiver text.
  if (prefix.indexOf('.') >= 0) return null
  val wordPrefix = identifierPrefix(file.text, offset)
  if (wordPrefix.isEmpty()) return null
  val matched = ALL_KEYWORDS.filter { it.value.startsWith(wordPrefix, ignoreCase = true) }
  if (matched.isEmpty()) return null
  val startedAt = System.nanoTime()
  val accepted = filterByParsing(file, offset, element, matched)
  KlsLogs.warn(
      "keyword_completion_probe prefix='{}' matched={} accepted={} elapsedUs={}",
      wordPrefix,
      matched.map { it.value },
      accepted.map { it.value },
      (System.nanoTime() - startedAt) / 1_000,
  )
  if (accepted.isEmpty()) return null
  return accepted.map(::keywordItem)
}

private fun filterByParsing(
    file: KtFile,
    offset: Int,
    element: PsiElement?,
    candidates: List<KtKeywordToken>,
): List<KtKeywordToken> {
  val probed = candidates.take(MAX_PROBE_CANDIDATES)
  val remaining = candidates.drop(probed.size)
  val context =
      runCatching { probeContextText(file, offset, element) }.getOrNull() ?: return candidates
  if (context.length > MAX_PROBE_TEXT) return candidates
  val factory =
      runCatching { KtPsiFactory(file.project, markGenerated = true, eventSystemEnabled = false) }
          .getOrNull() ?: return candidates
  val accepted =
      probed.filter { token ->
        // Parsing failures must not hide a keyword: only a successful parse that rejects the
        // position removes the candidate.
        runCatching { keywordAccepted(factory, context, token) }.getOrDefault(true)
      }
  return accepted + remaining
}

/**
 * Builds the text that ends right before the caret, using the closest structural anchor and keeping
 * only the signature of the preceding siblings (bodies collapse to `{}`).
 */
private fun probeContextText(file: KtFile, offset: Int, element: PsiElement?): String {
  val leaf = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: element
  var node: PsiElement? = leaf
  while (node != null && node !is PsiFile) {
    when (node) {
      is KtBlockExpression -> return "fun foo() { " + node.textBefore(leaf)
      is KtClassBody -> return "class X { " + node.textBefore(leaf)
      is KtDeclarationWithInitializer -> {
        val initializer = node.initializer
        if (initializer != null && initializer.offsetInRange(offset)) {
          return "val v = " + initializer.textBefore(leaf)
        }
      }
      is KtParameter -> {
        val defaultValue = node.defaultValue
        if (defaultValue != null && defaultValue.offsetInRange(offset)) {
          return "val v = " + defaultValue.textBefore(leaf)
        }
      }
      is KtTypeReference -> return typeReferencePrefix(node) + node.textBefore(leaf)
      is KtDeclaration -> {
        val builder = StringBuilder()
        buildReducedContextBefore(builder, leaf ?: node, node)
        val skeleton =
            when (node.parent) {
              is KtClassOrObject ->
                  if (node is KtPrimaryConstructor) "class X " else "class X { "
              else -> ""
            }
        return skeleton + builder
      }
    }
    node = node.parent
  }
  return ""
}

private fun keywordAccepted(
    factory: KtPsiFactory,
    context: String,
    token: KtKeywordToken,
): Boolean {
  val probeFile = factory.createFile("KeywordProbe.kt", context + token.value)
  val elementAtCaret = probeFile.findElementAt(context.length) ?: return true
  val elementType = elementAtCaret.node?.elementType
  if (elementType == null || !elementType.matchesKeyword(token)) return false
  if (elementAtCaret.getNonStrictParentOfType<PsiErrorElement>() != null) return false
  return !hasErrorElementBefore(elementAtCaret)
}

private fun IElementType.matchesKeyword(token: KtKeywordToken): Boolean =
    when (this) {
      token -> true
      KtTokens.NOT_IN -> token == KtTokens.IN_KEYWORD
      KtTokens.NOT_IS -> token == KtTokens.IS_KEYWORD
      else -> false
    }

private fun hasErrorElementBefore(token: PsiElement): Boolean {
  var leaf = token.prevLeaf()
  while (leaf != null) {
    if (leaf !is PsiWhiteSpace && leaf !is PsiComment) {
      if (leaf.parentsWithSelf.any { it is PsiErrorElement }) return true
      if (leaf.textLength != 0) return false
    }
    leaf = leaf.prevLeaf()
  }
  return false
}

private fun buildReducedContextBefore(
    builder: StringBuilder,
    position: PsiElement,
    scope: PsiElement?,
) {
  if (position === scope) return
  if (position is PsiFile) return
  val parent = position.parent ?: return
  buildReducedContextBefore(builder, parent, scope)
  val previousDeclaration =
      position.siblings(forward = false, withItself = false).firstOrNull { it is KtDeclaration }
  var child = parent.firstChild
  while (child != null && child !== position) {
    if (child is KtDeclaration) {
      if (child === previousDeclaration) builder.appendReducedText(child)
    } else {
      builder.append(child.text)
    }
    child = child.nextSibling
  }
}

private fun StringBuilder.appendReducedText(element: PsiElement) {
  val firstChild = element.firstChild
  if (firstChild == null) {
    append(element.text)
    return
  }
  var child: PsiElement? = firstChild
  while (child != null) {
    when (child) {
      is KtBlockExpression, is KtClassBody -> append("{}")
      else -> appendReducedText(child)
    }
    child = child.nextSibling
  }
}

private fun typeReferencePrefix(typeReference: KtTypeReference): String {
  val projection = typeReference.parent as? KtTypeProjection
  val argumentList = projection?.parent as? KtTypeArgumentList
  val userType = argumentList?.parent as? KtUserType
  return if (userType?.parent is KtTypeReference) "fun foo(x: X<" else "fun foo(x: "
}

private fun PsiElement.textBefore(position: PsiElement?): String {
  val content = text ?: return ""
  val start = textRange?.startOffset ?: 0
  val cut = ((position?.textRange?.startOffset ?: start) - start).coerceIn(0, content.length)
  return content.substring(0, cut)
}

private fun PsiElement.offsetInRange(offset: Int): Boolean {
  val range = textRange ?: return false
  return offset >= range.startOffset && offset <= range.endOffset
}

private fun identifierPrefix(text: CharSequence, offset: Int): String {
  var index = offset.coerceIn(0, text.length)
  while (index > 0) {
    val c = text[index - 1]
    if (!(c.isLetterOrDigit() || c == '_')) break
    index--
  }
  return text.substring(index, offset.coerceIn(0, text.length))
}

private fun keywordItem(token: KtKeywordToken): CompletionItem {
  val name = token.value
  return CompletionItem(
      ideLabel = name,
      detail = "keyword",
      insertText = name,
      insertTextFormat = InsertTextFormat.PLAIN_TEXT,
      sortText = name,
      command = null,
      completionKind = CompletionItemKind.KEYWORD,
      matchLevel = MatchLevel.CASE_SENSITIVE_PREFIX,
      additionalTextEdits = emptyList(),
      data = null,
  )
}