package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.language.services.kotlin.backend.analysis.snippet.KotlinSnippetRepository
import com.tom.rv2ide.language.services.kotlin.backend.analysis.snippet.KotlinSnippetScope
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.snippets.ISnippet
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.preferences.utils.indentationString
import io.github.rosemoe.sora.text.TextUtils
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFile

private const val SNIPPET_COMPLETION_BUDGET = 20

internal fun snippetCompletionItems(
    prefix: String,
    file: KtFile,
    offset: Int,
    element: PsiElement?,
): List<CompletionItem> {
  if (prefix.isEmpty()) return emptyList()
  if (prefix.contains('.')) return emptyList()
  if (!atLineStart(file.text, offset)) return emptyList()
  val repository =
      runCatching { KotlinSnippetRepository.snippets }.getOrElse { return emptyList() }
  if (repository.isEmpty()) return emptyList()
  val snippets = ArrayList<ISnippet>(8)
  repository[KotlinSnippetScope.GLOBAL]?.let(snippets::addAll)
  repository[snippetScopeAt(element)]?.let(snippets::addAll)
  if (snippets.isEmpty()) return emptyList()
  val indent = spacesBeforeCursor(file.text, offset)
  val items = ArrayList<CompletionItem>(minOf(snippets.size, SNIPPET_COMPLETION_BUDGET))
  val seenPrefixes = HashSet<String>(snippets.size)
  for (snippet in snippets) {
    if (!seenPrefixes.add(snippet.prefix)) continue
    val matchLevel = CompletionItem.matchLevel(snippet.prefix, prefix)
    if (matchLevel == MatchLevel.NO_MATCH) continue
    items += snippetItem(snippet, matchLevel, prefix, indent)
    if (items.size >= SNIPPET_COMPLETION_BUDGET) break
  }
  return items
}

private fun snippetItem(
    snippet: ISnippet,
    matchLevel: MatchLevel,
    partial: String,
    indent: Int,
): CompletionItem {
  val indentation = indentationString(indent)
  val item =
      CompletionItem(
          ideLabel = snippet.prefix,
          detail = snippet.description,
          insertText =
              snippet.body
                  .joinToString(separator = "\n")
                  .replace("\t", indentationString)
                  .replace("\n", "\n$indentation"),
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

private fun atLineStart(text: String, offset: Int): Boolean {
  val safe = offset.coerceIn(0, text.length)
  if (safe == 0) return true
  val lineStart = text.lastIndexOf('\n', safe - 1).let { it + 1 }
  return text.substring(lineStart, safe).isBlank()
}

private fun spacesBeforeCursor(text: CharSequence, offset: Int): Int {
  var start = offset - 1
  while (start >= 0) {
    val char = text[start]
    if (char == '\n' || !char.isWhitespace()) break
    start--
  }
  return TextUtils.countLeadingSpaceCount(
      text.subSequence(start + 1, offset),
      EditorPreferences.tabSize,
  )
}