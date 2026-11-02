package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.language.services.kotlin.backend.analysis.binary.JvmClassIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.binary.JvmClassKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.source.JavaSourceIndex
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path

private const val PACKAGE_COMPLETION_BUDGET = 50

internal fun packageCompletionItems(
    prefix: String,
    metadataIndex: KotlinSourceMetadataIndex?,
    currentModulePath: String?,
    classIndex: JvmClassIndex? = null,
    binarySources: Collection<Path> = emptyList(),
    javaIndex: JavaSourceIndex? = null,
    cancelChecker: ICancelChecker = ICancelChecker.NOOP,
): List<CompletionItem> {
  if (metadataIndex == null &&
      (classIndex == null || binarySources.isEmpty()) &&
      javaIndex == null) {
    return emptyList()
  }
  val normalized = prefix.trim()
  val parentPackage = normalized.substringBeforeLast('.', "")
  val segment = normalized.substringAfterLast('.')
  val items = ArrayList<CompletionItem>(32)

  if (metadataIndex != null) {
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
  }

  if (classIndex != null && binarySources.isNotEmpty()) {
    classIndex
        .subpackages(parentPackage, binarySources)
        .asSequence()
        .filter { it.startsWith(segment, ignoreCase = true) }
        .sorted()
        .forEach { name ->
          cancelChecker.abortIfCancelled()
          if (items.size >= PACKAGE_COMPLETION_BUDGET) return@forEach
          items += packageItem(name, parentPackage, segment, CompletionItemKind.MODULE)
        }

    classIndex
        .classesInPackage(parentPackage, binarySources)
        .asSequence()
        .filter { it.simpleName.startsWith(segment, ignoreCase = true) }
        .sortedBy { it.simpleName }
        .forEach { entry ->
          cancelChecker.abortIfCancelled()
          if (items.size >= PACKAGE_COMPLETION_BUDGET) return@forEach
          items += packageItem(entry.simpleName, parentPackage, segment, classItemKind(entry.kind))
        }
  }

  if (javaIndex != null) {
    javaIndex
        .classesInPackage(currentModulePath, parentPackage)
        .asSequence()
        .filter { it.simpleName.startsWith(segment, ignoreCase = true) }
        .sortedBy { it.simpleName }
        .forEach { entry ->
          cancelChecker.abortIfCancelled()
          if (items.size >= PACKAGE_COMPLETION_BUDGET) return@forEach
          items += packageItem(entry.simpleName, parentPackage, segment, classItemKind(entry.kind))
        }
  }

  return items.distinctBy { it.ideLabel to it.completionKind }.take(PACKAGE_COMPLETION_BUDGET)
}

private fun classItemKind(kind: JvmClassKind): CompletionItemKind =
    when (kind) {
      JvmClassKind.INTERFACE -> CompletionItemKind.INTERFACE
      JvmClassKind.ENUM -> CompletionItemKind.ENUM
      JvmClassKind.ANNOTATION -> CompletionItemKind.ANNOTATION_TYPE
      JvmClassKind.CLASS -> CompletionItemKind.CLASS
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