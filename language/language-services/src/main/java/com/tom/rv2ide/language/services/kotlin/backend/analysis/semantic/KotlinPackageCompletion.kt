package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.progress.ICancelChecker

private const val PACKAGE_COMPLETION_BUDGET = 50

internal fun packageCompletionItems(
    prefix: String,
    metadataIndex: KotlinSourceMetadataIndex?,
    currentModulePath: String?,
    cancelChecker: ICancelChecker = ICancelChecker.NOOP,
): List<CompletionItem> {
  if (metadataIndex == null) return emptyList()
  val normalized = prefix.trim()
  val parentPackage = normalized.substringBeforeLast('.', "")
  val segment = normalized.substringAfterLast('.')
  val items = ArrayList<CompletionItem>(16)

  metadataIndex
      .subpackageNames(parentPackage)
      .asSequence()
      .filter { it.startsWith(segment, ignoreCase = true) }
      .sorted()
      .forEach { name ->
        cancelChecker.abortIfCancelled()
        if (items.size >= PACKAGE_COMPLETION_BUDGET) return@forEach
        items += packageItem(name, parentPackage, segment, CompletionItemKind.MODULE)
      }

  metadataIndex
      .filesForPackage(parentPackage)
      .asSequence()
      .mapNotNull(metadataIndex::metadata)
      .flatMap { metadata ->
        metadata.topLevelClassifierNames.asSequence()
            .filter { metadata.isClassifierImportable(it, currentModulePath) }
      }
      .distinct()
      .filter { it.startsWith(segment, ignoreCase = true) }
      .sorted()
      .forEach { name ->
        cancelChecker.abortIfCancelled()
        if (items.size >= PACKAGE_COMPLETION_BUDGET) return@forEach
        items += packageItem(name, parentPackage, segment, CompletionItemKind.CLASS)
      }

  return items.distinctBy { it.ideLabel to it.completionKind }.take(PACKAGE_COMPLETION_BUDGET)
}

private fun packageItem(
    name: String,
    parentPackage: String,
    segment: String,
    kind: CompletionItemKind,
): CompletionItem =
    CompletionItem(
        ideLabel = name,
        detail = if (parentPackage.isEmpty()) name else "$parentPackage.$name",
        insertText = name,
        insertTextFormat = InsertTextFormat.PLAIN_TEXT,
        sortText = name,
        command = null,
        completionKind = kind,
        matchLevel =
            when {
              name == segment -> MatchLevel.CASE_SENSITIVE_EQUAL
              name.startsWith(segment) -> MatchLevel.CASE_SENSITIVE_PREFIX
              name.lowercase().startsWith(segment.lowercase()) -> MatchLevel.CASE_INSENSITIVE_PREFIX
              else -> MatchLevel.NO_MATCH
            },
        additionalTextEdits = emptyList(),
        data = null,
    )