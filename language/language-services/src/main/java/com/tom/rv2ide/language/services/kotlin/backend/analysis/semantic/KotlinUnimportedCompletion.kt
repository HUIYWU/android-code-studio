package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.models.Range
import org.jetbrains.kotlin.psi.KtFile

private const val UNIMPORTED_COMPLETION_BUDGET = 50

internal fun unimportedClassifierCompletionItems(
    prefix: String,
    file: KtFile,
    metadataIndex: KotlinSourceMetadataIndex?,
    classCallSnippet: Boolean,
): List<CompletionItem> {
  if (metadataIndex == null) return emptyList()
  val segment = prefix.takeIf { it.isNotEmpty() && !it.contains('.') } ?: return emptyList()
  val currentPackage = file.packageFqName.asString()
  val importedNames =
      file.importDirectives
          .mapNotNull { it.importedFqName?.shortName()?.asString() }
          .toHashSet()
  val candidates = mutableListOf<Pair<String, String>>()
  for (path in metadataIndex.paths()) {
    val metadata = metadataIndex.metadata(path) ?: continue
    if (metadata.packageName == currentPackage) continue
    val packageName = metadata.packageName
    for (name in metadata.topLevelClassifierNames) {
      if (!name.startsWith(segment, ignoreCase = true)) continue
      if (name in importedNames) continue
      candidates += name to if (packageName.isEmpty()) name else "$packageName.$name"
    }
  }
  if (candidates.isEmpty()) return emptyList()
  val insertion = importInsertion(file)
  return candidates
      .distinctBy { it.second }
      .sortedBy { it.second }
      .take(UNIMPORTED_COMPLETION_BUDGET)
      .map { (name, fqName) ->
        unimportedItem(name, segment, fqName, insertion, classCallSnippet)
      }
}

private data class ImportInsertion(val offset: Int, val text: String)

private fun importInsertion(file: KtFile): ImportInsertion {
  val text = file.text
  val anchor =
      file.importDirectives.lastOrNull()?.textRange?.endOffset
          ?: file.packageDirective?.textRange?.endOffset
          ?: file.fileAnnotationList?.textRange?.endOffset
  val offset =
      if (anchor == null) {
        0
      } else {
        val newline = text.indexOf('\n', anchor.coerceIn(0, text.length))
        if (newline < 0) text.length else newline
      }
  return ImportInsertion(offset, text)
}

private fun importEditText(text: String, offset: Int, fqName: String): String {
  val atLineStart = offset == 0 || text[offset - 1] == '\n'
  return buildString {
    if (!atLineStart) append('\n')
    append("import ").append(fqName)
    if (atLineStart || offset == text.length) append('\n')
  }
}

private fun unimportedItem(
    name: String,
    segment: String,
    fqName: String,
    insertion: ImportInsertion,
    classCallSnippet: Boolean,
): CompletionItem {
  val index = LineIndex.from(insertion.text)
  val importText = importEditText(insertion.text, insertion.offset, fqName)
  val position = index.indexToPosition(insertion.offset)
  val edit = TextEdit(Range(position, position), importText)
  val callLike = classCallSnippet
  val item =
      CompletionItem(
          ideLabel = name,
          detail = fqName,
          insertText = if (callLike) "$name(\$0)" else name,
          insertTextFormat = if (callLike) InsertTextFormat.SNIPPET else InsertTextFormat.PLAIN_TEXT,
          sortText = name,
          command = if (callLike) Command("Trigger Parameter Hints", Command.TRIGGER_PARAMETER_HINTS) else null,
          completionKind = CompletionItemKind.CLASS,
          matchLevel =
              when {
                name == segment -> MatchLevel.CASE_SENSITIVE_EQUAL
                name.startsWith(segment) -> MatchLevel.CASE_SENSITIVE_PREFIX
                name.lowercase().startsWith(segment.lowercase()) -> MatchLevel.CASE_INSENSITIVE_PREFIX
                else -> MatchLevel.NO_MATCH
              },
          additionalTextEdits = listOf(edit),
          data = null,
      )
  if (callLike) {
    item.snippetDescription = describeSnippet(prefix = segment, allowCommandExecution = true)
  }
  return item
}