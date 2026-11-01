package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtProperty

private val MODIFIER_KEYWORDS =
    listOf(
        "public",
        "private",
        "protected",
        "internal",
        "open",
        "final",
        "abstract",
        "sealed",
        "override",
        "data",
        "inline",
        "noinline",
        "crossinline",
        "suspend",
        "tailrec",
        "operator",
        "infix",
        "external",
        "const",
        "lateinit",
        "vararg",
        "reified",
        "value",
        "annotation",
        "companion",
        "inner",
        "enum",
        "expect",
        "actual",
    )

private val DECLARATION_STARTERS =
    listOf("fun", "val", "var", "class", "interface", "object", "constructor", "typealias")

private val TOP_LEVEL_MODIFIERS =
    listOf(
        "public",
        "private",
        "internal",
        "open",
        "final",
        "abstract",
        "sealed",
        "data",
        "enum",
        "annotation",
        "value",
        "const",
        "lateinit",
        "suspend",
        "inline",
        "operator",
        "infix",
        "tailrec",
        "fun",
        "external",
        "expect",
        "actual",
    )

private val MEMBER_MODIFIERS =
    TOP_LEVEL_MODIFIERS + listOf("protected", "inner", "companion", "override")

private val STATEMENT_MODIFIERS =
    listOf(
        "public",
        "private",
        "protected",
        "internal",
        "open",
        "final",
        "abstract",
        "data",
        "lateinit",
        "suspend",
        "inline",
        "operator",
        "infix",
        "tailrec",
    )

private val TOP_LEVEL_DECLARATIONS =
    listOf("fun", "val", "var", "class", "interface", "object", "typealias")

private val MEMBER_DECLARATIONS =
    TOP_LEVEL_DECLARATIONS + listOf("constructor", "init")

private val STATEMENT_DECLARATIONS = listOf("fun", "val", "var", "class")

private val STATEMENT_KEYWORDS =
    listOf("return", "if", "for", "while", "when", "try", "throw", "break", "continue", "do")

private val EXPRESSION_KEYWORDS = listOf("this", "super", "null", "true", "false")

private val AFTER_BLOCK_KEYWORDS = listOf("else", "catch", "finally")

private val INFIX_KEYWORDS = listOf("is", "in")

private val TYPE_ARGUMENT_KEYWORDS = listOf("in", "out")

private val DELEGATION_KEYWORDS = listOf("by")

private val WHERE_KEYWORDS = listOf("where")

private val EXPRESSION_PREFIX_CHARS =
    setOf('=', '(', '[', ',', ':', '+', '-', '*', '/', '%', '&', '|', '!', '?', '<', '>')

private val EXPRESSION_PREFIX_WORDS =
    setOf("return", "throw", "in", "is", "as", "by", "where", "when", "if", "else", "while", "for", "do", "try", "catch")

private val DELEGATION_PATTERN = Regex("""\b(val|var)\s+\w+(\s*:\s*[^=]+)?\s*$""")

private val WHITESPACE = Regex("\\s+")

private val KEYWORD_NAMES: Set<String> =
    (
        MEMBER_DECLARATIONS +
            STATEMENT_KEYWORDS +
            EXPRESSION_KEYWORDS +
            AFTER_BLOCK_KEYWORDS +
            INFIX_KEYWORDS +
            TYPE_ARGUMENT_KEYWORDS +
            DELEGATION_KEYWORDS +
            WHERE_KEYWORDS)
        .toSet()

private enum class KeywordSlot {
  TOP_LEVEL,
  MEMBER,
  STATEMENT,
}

private class KeywordContext(val slot: KeywordSlot, val bodyStart: Int)

internal fun keywordCompletionItems(
    prefix: String,
    file: KtFile,
    offset: Int,
    element: PsiElement?,
    typePosition: Boolean,
): List<CompletionItem>? {
  val context = keywordContext(element)
  if (prefix.isEmpty() || context == null) {
    KlsLogs.warn("keyword_completion_skip reason=empty_prefix_or_no_slot prefix='{}'", prefix)
    return null
  }
  val slot = context.slot
  val text = file.text
  val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
  if (lineStart > offset) return null
  // A single-line body keeps its opening brace on the caret line, so the analysis must start after
  // that brace instead of at the line start.
  val from = if (context.bodyStart in lineStart..offset) context.bodyStart else lineStart
  val before = text.substring(from, offset).trimEnd()
  val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
  val after = text.substring(offset, lineEnd).trimStart()
  val beforeWords = before.split(WHITESPACE).filter { it.isNotEmpty() }
  val prefixWord = beforeWords.lastOrNull().orEmpty()
  val completed = before.removeSuffix(prefixWord).trimEnd()
  val previousChar = completed.lastOrNull()
  val previousWord =
      if (completed.isEmpty()) null
      else completed.split(WHITESPACE).lastOrNull { it.isNotEmpty() }
  val leadingWords = beforeWords.dropLast(1)
  val declarationPosition = leadingWords.all { it in MODIFIER_KEYWORDS }
  val expressionStart =
      previousChar == null && slot == KeywordSlot.STATEMENT ||
          previousChar?.let { it in EXPRESSION_PREFIX_CHARS } == true ||
          previousWord?.let { it in EXPRESSION_PREFIX_WORDS } == true
  val statementStart =
      declarationPosition && previousChar == null && slot == KeywordSlot.STATEMENT
  val afterBlock = previousChar == '}'
  val infixPosition =
      previousChar != null &&
          (previousChar.isLetterOrDigit() ||
              previousChar == '_' ||
              previousChar == ')' ||
              previousChar == ']' ||
              previousChar == '"' ||
              previousChar == '\'') &&
          !declarationPosition
  val delegation = DELEGATION_PATTERN.matches(completed)
  val whereClause = isWherePosition(element, offset)
  val typeArgument = typePosition && (previousChar == '<' || previousChar == ',')
  KlsLogs.warn(
      "keyword_completion_probe prefix='{}' slot={} decl={} expr={} afterBlock={} infix={} delegate={} where={} typeArg={} before='{}' after='{}'",
      prefix,
      slot,
      declarationPosition,
      expressionStart,
      afterBlock,
      infixPosition,
      delegation,
      whereClause,
      typeArgument,
      before.take(40),
      after.take(40),
  )
  // A declaration starter typed after the caret only narrows the modifiers. The text after the
  // caret never gates the candidates: in single-line bodies it holds a closing brace or a comment.
  val starter = DECLARATION_STARTERS.firstOrNull { keyword ->
    after.startsWith(keyword) &&
        (after.length == keyword.length || !after[keyword.length].isLetterOrDigit())
  }
  val candidates = LinkedHashSet<String>()
  val afterStartsIdentifier =
      after.isNotEmpty() && (after[0].isLetter() || after[0] == '_')
  if (declarationPosition) {
    when {
      starter != null -> candidates += modifiersFor(starter).filter { it in allowedModifiers(slot) }
      afterStartsIdentifier -> candidates += allowedModifiers(slot)
      else -> {
        candidates += declarationsFor(slot)
        candidates += allowedModifiers(slot)
      }
    }
    if (statementStart) candidates += STATEMENT_KEYWORDS
  }
  if (expressionStart) {
    val receiverContext = hasReceiverContext(element)
    candidates += EXPRESSION_KEYWORDS.filter { it != "this" && it != "super" || receiverContext }
  }
  if (afterBlock) candidates += AFTER_BLOCK_KEYWORDS
  if (infixPosition) candidates += INFIX_KEYWORDS
  if (typeArgument) candidates += TYPE_ARGUMENT_KEYWORDS
  if (delegation) candidates += DELEGATION_KEYWORDS
  if (whereClause) candidates += WHERE_KEYWORDS
  val existing = leadingWords.toSet()
  val items =
      candidates
          .filterNot { it in existing }
          .filter { it.startsWith(prefix, ignoreCase = true) }
          .map { name ->
            CompletionItem(
                ideLabel = name,
                detail = if (name in KEYWORD_NAMES) "keyword" else "modifier",
                insertText = name,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = name,
                command = null,
                completionKind = CompletionItemKind.KEYWORD,
                matchLevel =
                    when {
                      name == prefix -> MatchLevel.CASE_SENSITIVE_EQUAL
                      name.startsWith(prefix) -> MatchLevel.CASE_SENSITIVE_PREFIX
                      else -> MatchLevel.CASE_INSENSITIVE_PREFIX
                    },
                additionalTextEdits = emptyList(),
                data = null,
            )
          }
  KlsLogs.warn(
      "keyword_completion_items prefix='{}' slot={} starter='{}' count={}",
      prefix,
      slot,
      starter,
      items.size,
  )
  return items.takeIf { it.isNotEmpty() }
}

private fun keywordContext(element: PsiElement?): KeywordContext? {
  var node: PsiElement? = element
  while (node != null && node !is PsiFile) {
    when (node) {
      is KtBlockExpression -> return KeywordContext(KeywordSlot.STATEMENT, bodyStart(node))
      is KtClassBody -> return KeywordContext(KeywordSlot.MEMBER, bodyStart(node))
    }
    node = node.parent
  }
  return if (element?.containingFile is KtFile) KeywordContext(KeywordSlot.TOP_LEVEL, 0) else null
}

private fun bodyStart(node: PsiElement): Int {
  val brace = node.node?.findChildByType(KtTokens.LBRACE)
  return if (brace != null) brace.startOffset + 1 else node.textRange?.startOffset ?: 0
}

private fun hasReceiverContext(element: PsiElement?): Boolean {
  var node: PsiElement? = element
  while (node != null && node !is PsiFile) {
    if (node is KtClassOrObject) return true
    if (node is KtCallableDeclaration && node.receiverTypeReference != null) return true
    node = node.parent
  }
  return false
}

/** `where` only follows the type parameter and value parameter lists of a declaration. */
private fun isWherePosition(element: PsiElement?, offset: Int): Boolean {
  var node: PsiElement? = element
  while (node != null && node !is PsiFile) {
    when (node) {
      is KtFunction -> {
        if (node.typeParameterList == null) return false
        val paramsEnd = node.valueParameterList?.textRange?.endOffset ?: return false
        if (offset < paramsEnd) return false
        val body = node.bodyExpression
        return body == null || (body.textRange?.startOffset ?: Int.MAX_VALUE) >= offset
      }
      is KtClassOrObject -> {
        if (node.typeParameterList == null) return false
        val headerEnd =
            node.primaryConstructor?.textRange?.endOffset
                ?: node.typeParameterList?.textRange?.endOffset
                ?: return false
        if (offset < headerEnd) return false
        val body = node.body
        return body == null || (body.textRange?.startOffset ?: Int.MAX_VALUE) >= offset
      }
      is KtProperty -> return false
      else -> Unit
    }
    node = node.parent
  }
  return false
}

private fun declarationsFor(slot: KeywordSlot): List<String> =
    when (slot) {
      KeywordSlot.TOP_LEVEL -> TOP_LEVEL_DECLARATIONS
      KeywordSlot.MEMBER -> MEMBER_DECLARATIONS
      KeywordSlot.STATEMENT -> STATEMENT_DECLARATIONS
    }

private fun allowedModifiers(slot: KeywordSlot): List<String> =
    when (slot) {
      KeywordSlot.TOP_LEVEL -> TOP_LEVEL_MODIFIERS
      KeywordSlot.MEMBER -> MEMBER_MODIFIERS
      KeywordSlot.STATEMENT -> STATEMENT_MODIFIERS
    }

private fun modifiersFor(starter: String): List<String> {
  val visibility = listOf("public", "private", "protected", "internal")
  val modality = listOf("open", "final", "abstract")
  return when (starter) {
    "fun" ->
        visibility +
            modality +
            listOf(
                "override",
                "sealed",
                "inline",
                "suspend",
                "tailrec",
                "operator",
                "infix",
                "external",
                "expect",
                "actual",
            )
    "val" ->
        visibility +
            modality +
            listOf("override", "sealed", "const", "value", "expect", "actual")
    "var" ->
        visibility +
            modality +
            listOf("override", "sealed", "lateinit", "expect", "actual")
    "class" ->
        visibility +
            modality +
            listOf(
                "sealed",
                "data",
                "inner",
                "value",
                "expect",
                "actual",
                "external",
            )
    "interface" -> visibility + listOf("sealed", "fun", "expect", "actual", "external")
    "object" ->
        visibility + listOf("data", "companion", "expect", "actual", "external")
    "constructor" -> visibility
    "typealias" -> visibility
    else -> visibility + modality + listOf("override", "expect", "actual")
  }
}