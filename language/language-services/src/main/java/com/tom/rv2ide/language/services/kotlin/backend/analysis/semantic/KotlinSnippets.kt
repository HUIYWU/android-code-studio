package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.language.services.kotlin.backend.analysis.snippet.KotlinSnippetRepository
import com.tom.rv2ide.language.services.kotlin.backend.analysis.snippet.KotlinSnippetScope
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.snippets.ISnippet
import com.tom.rv2ide.preferences.utils.indentationString
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFile

private const val SNIPPET_COMPLETION_BUDGET = 20

internal fun snippetCompletionItems(
    file: KtFile,
    offset: Int,
    element: PsiElement?,
): List<CompletionItem> {
  val text = file.text
  val caret = offset.coerceIn(0, text.length)
  val start = declarationWordStart(text, caret) ?: return emptyList()
  val prefix = text.substring(start, caret)
  val repository =
      runCatching { KotlinSnippetRepository.snippets }.getOrElse { return emptyList() }
  val snippets = ArrayList<ISnippet>(8)
  repository[KotlinSnippetScope.GLOBAL]?.let(snippets::addAll)
  repository[snippetScopeAt(element)]?.let(snippets::addAll)
  if (snippets.isEmpty()) return emptyList()
  val items = ArrayList<CompletionItem>(minOf(snippets.size, SNIPPET_COMPLETION_BUDGET))
  val seenPrefixes = HashSet<String>(snippets.size)
  for (snippet in snippets) {
    if (!seenPrefixes.add(snippet.prefix)) continue
    val matchLevel = CompletionItem.matchLevel(snippet.prefix, prefix)
    if (matchLevel == MatchLevel.NO_MATCH) continue
    items += snippetItem(snippet, matchLevel, prefix)
    if (items.size >= SNIPPET_COMPLETION_BUDGET) break
  }
  return items
}

private fun snippetItem(
    snippet: ISnippet,
    matchLevel: MatchLevel,
    partial: String,
): CompletionItem {
  val item =
      CompletionItem(
          ideLabel = snippet.prefix,
          detail = snippet.description,
          insertText =
              snippet.body.joinToString(separator = "\n").replace("\t", indentationString),
          insertTextFormat = InsertTextFormat.SNIPPET,
          sortText = "00000${snippet.prefix}",
          command = null,
          completionKind = CompletionItemKind.SNIPPET,
          matchLevel = matchLevel,
          additionalTextEdits = emptyList(),
          data = null,
      )
  item.snippetDescription = describeSnippet(prefix = partial)
  return item
}

private fun snippetScopeAt(element: PsiElement?): KotlinSnippetScope {
  var node = element
  while (node != null && node !is PsiFile) {
    when (node) {
      is KtClassBody -> return KotlinSnippetScope.MEMBER
      is KtBlockExpression -> return KotlinSnippetScope.LOCAL
      is KtFile -> return KotlinSnippetScope.TOP_LEVEL
    }
    node = node.parent
  }
  return KotlinSnippetScope.TOP_LEVEL
}

private fun declarationWordStart(text: String, offset: Int): Int? {
  val safe = offset.coerceIn(0, text.length)
  val lineStart = if (safe == 0) 0 else text.lastIndexOf('\n', safe - 1) + 1
  var start = lineStart
  while (start < safe && (text[start] == ' ' || text[start] == '\t')) start++
  // The prefix must be a single identifier that begins right after the line indentation, so
  // declarations are not offered in expression positions such as `val x = cla`.
  for (index in start until safe) {
    val char = text[index]
    if (!char.isLetterOrDigit() && char != '_') return null
  }
  return start
}