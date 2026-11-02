package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.language.services.kotlin.backend.analysis.binary.JvmCallableKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.binary.JvmClassIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.binary.JvmClassKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceExtensionKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.ownerModulePathOf
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtFile

private const val UNIMPORTED_COMPLETION_BUDGET = 50

private val DEFAULT_IMPORTED_PACKAGES =
    setOf(
        "java.lang",
        "kotlin",
        "kotlin.annotation",
        "kotlin.collections",
        "kotlin.comparisons",
        "kotlin.io",
        "kotlin.ranges",
        "kotlin.sequences",
        "kotlin.text",
        "kotlin.jvm",
    )

private enum class UnimportedDeclarationKind {
  CLASS,
  INTERFACE,
  ENUM,
  ANNOTATION,
  FUNCTION,
  PROPERTY,
}

private data class UnimportedCandidate(
    val name: String,
    val fqName: String,
    val kind: UnimportedDeclarationKind,
)

internal fun unimportedCompletionItems(
    prefix: String,
    file: KtFile,
    metadataIndex: KotlinSourceMetadataIndex?,
    classCallSnippet: Boolean,
    classIndex: JvmClassIndex? = null,
    binarySources: Collection<Path> = emptyList(),
    cancelChecker: ICancelChecker = ICancelChecker.NOOP,
): List<CompletionItem> {
  if (metadataIndex == null) return emptyList()
  val segment = prefix.takeIf { it.isNotEmpty() && !it.contains('.') } ?: return emptyList()
  val currentPackage = file.packageFqName.asString()
  val currentModulePath = metadataIndex.ownerModulePathOf(file)
  val importedNames = importedSimpleNames(file)
  val candidates = mutableListOf<UnimportedCandidate>()

  fun add(
      name: String,
      packageName: String,
      kind: UnimportedDeclarationKind,
      importable: Boolean,
  ) {
    if (!importable) return
    if (!name.startsWith(segment, ignoreCase = true)) return
    if (name in importedNames) return
    candidates +=
        UnimportedCandidate(
            name = name,
            fqName = if (packageName.isEmpty()) name else "$packageName.$name",
            kind = kind,
        )
  }

  for (path in metadataIndex.paths()) {
    cancelChecker.abortIfCancelled()
    val metadata = metadataIndex.metadata(path) ?: continue
    if (metadata.packageName == currentPackage) continue
    val packageName = metadata.packageName
    for (name in metadata.topLevelClassifierNames) {
      add(name, packageName, UnimportedDeclarationKind.CLASS, metadata.isClassifierImportable(name, currentModulePath))
    }
    if (classCallSnippet) {
      for (name in metadata.topLevelImportableFunctionNames) {
        add(name, packageName, UnimportedDeclarationKind.FUNCTION, metadata.isCallableImportable(name, currentModulePath))
      }
      for (name in metadata.topLevelImportablePropertyNames) {
        add(name, packageName, UnimportedDeclarationKind.PROPERTY, metadata.isCallableImportable(name, currentModulePath))
      }
    }
  }
  if (classIndex != null && binarySources.isNotEmpty()) {
    cancelChecker.abortIfCancelled()
    for (entry in
        classIndex.classesBySimpleNamePrefix(segment, binarySources, UNIMPORTED_COMPLETION_BUDGET)) {
      if (entry.packageName in DEFAULT_IMPORTED_PACKAGES) continue
      if (entry.simpleName in importedNames) continue
      candidates +=
          UnimportedCandidate(
              name = entry.simpleName,
              fqName =
                  if (entry.packageName.isEmpty()) entry.simpleName
                  else "${entry.packageName}.${entry.simpleName}",
              kind = entry.kind.toUnimportedKind(),
          )
    }
  }
  if (classIndex != null && binarySources.isNotEmpty() && classCallSnippet) {
    cancelChecker.abortIfCancelled()
    for (callable in
        classIndex.callablesBySimpleNamePrefix(segment, binarySources, UNIMPORTED_COMPLETION_BUDGET)) {
      if (callable.packageName in DEFAULT_IMPORTED_PACKAGES) continue
      if (callable.name in importedNames) continue
      candidates +=
          UnimportedCandidate(
              name = callable.name,
              fqName =
                  if (callable.packageName.isEmpty()) callable.name
                  else "${callable.packageName}.${callable.name}",
              kind =
                  when (callable.kind) {
                    JvmCallableKind.FUNCTION -> UnimportedDeclarationKind.FUNCTION
                    JvmCallableKind.PROPERTY -> UnimportedDeclarationKind.PROPERTY
                  },
          )
    }
  }
  if (candidates.isEmpty()) return emptyList()
  val layout = importLayout(file)
  return candidates
      .distinctBy { it.fqName to it.kind }
      .sortedWith(compareBy({ it.kind.ordinal }, { it.fqName }))
      .take(UNIMPORTED_COMPLETION_BUDGET)
      .map { unimportedItem(segment, it, layout, classCallSnippet) }
}

private fun importedSimpleNames(file: KtFile): Set<String> =
    file.importDirectives
        .filterNot { it.isAllUnder }
        .mapNotNull { it.aliasName ?: it.importedFqName?.shortName()?.asString() }
        .toHashSet()

internal fun unimportedExtensionCompletionItems(
    prefix: String,
    receiverTypeName: String,
    file: KtFile,
    metadataIndex: KotlinSourceMetadataIndex?,
    cancelChecker: ICancelChecker = ICancelChecker.NOOP,
): List<CompletionItem> {
  if (metadataIndex == null) return emptyList()
  if (prefix.contains('.')) return emptyList()
  val currentPackage = file.packageFqName.asString()
  val currentModulePath = metadataIndex.ownerModulePathOf(file)
  val importedNames = importedSimpleNames(file)
  val candidates = mutableListOf<UnimportedCandidate>()
  for (path in metadataIndex.paths()) {
    cancelChecker.abortIfCancelled()
    val metadata = metadataIndex.metadata(path) ?: continue
    if (metadata.packageName == currentPackage) continue
    val packageName = metadata.packageName
    for (extension in metadata.topLevelExtensions) {
      if (extension.receiverType != receiverTypeName) continue
      if (!extension.name.startsWith(prefix, ignoreCase = true)) continue
      if (extension.name in importedNames) continue
      if (!metadata.isExtensionImportable(extension, currentModulePath)) continue
      candidates +=
          UnimportedCandidate(
              name = extension.name,
              fqName = if (packageName.isEmpty()) extension.name else "$packageName.${extension.name}",
              kind =
                  when (extension.kind) {
                    KotlinSourceExtensionKind.FUNCTION -> UnimportedDeclarationKind.FUNCTION
                    KotlinSourceExtensionKind.PROPERTY -> UnimportedDeclarationKind.PROPERTY
                  },
          )
    }
  }
  if (candidates.isEmpty()) return emptyList()
  val layout = importLayout(file)
  return candidates
      .distinctBy { it.fqName to it.kind }
      .sortedWith(compareBy({ it.kind.ordinal }, { it.fqName }))
      .take(UNIMPORTED_COMPLETION_BUDGET)
      .map { unimportedItem(prefix, it, layout, true) }
}

private data class ImportLayout(
    val text: String,
    val index: LineIndex,
    val imports: List<Pair<String, Int>>,
    val fallbackOffset: Int,
)

private fun importLayout(file: KtFile): ImportLayout {
  val text = file.text
  val imports =
      file.importDirectives.mapNotNull { directive ->
        val fqName = directive.importedFqName?.asString() ?: return@mapNotNull null
        if (directive.isAllUnder) return@mapNotNull null
        fqName to directive.textRange.startOffset
      }
  val anchor =
      file.importDirectives.lastOrNull()?.textRange?.endOffset
          ?: file.packageDirective?.textRange?.endOffset
          ?: file.fileAnnotationList?.textRange?.endOffset
  val fallbackOffset =
      if (anchor == null) {
        0
      } else {
        val newline = text.indexOf('\n', anchor.coerceIn(0, text.length))
        if (newline < 0) text.length else newline
      }
  return ImportLayout(text, LineIndex.from(text), imports, fallbackOffset)
}

private fun importEdit(layout: ImportLayout, fqName: String): TextEdit {
  val greater = layout.imports.firstOrNull { it.first > fqName }
  if (greater != null) {
    val offset = lineStartOffset(layout.text, greater.second)
    val position = layout.index.indexToPosition(offset)
    return TextEdit(Range(position, position), "import $fqName\n")
  }
  val text = layout.text
  val offset = layout.fallbackOffset
  val atLineStart = offset == 0 || text[offset - 1] == '\n'
  val importText =
      buildString {
        if (!atLineStart) append('\n')
        append("import ").append(fqName)
        if (atLineStart || offset == text.length) append('\n')
      }
  val position = layout.index.indexToPosition(offset)
  return TextEdit(Range(position, position), importText)
}

private fun lineStartOffset(text: String, offset: Int): Int {
  if (offset <= 0) return 0
  val newline = text.lastIndexOf('\n', offset - 1)
  return if (newline < 0) 0 else newline + 1
}

private fun JvmClassKind.toUnimportedKind(): UnimportedDeclarationKind =
    when (this) {
      JvmClassKind.CLASS -> UnimportedDeclarationKind.CLASS
      JvmClassKind.INTERFACE -> UnimportedDeclarationKind.INTERFACE
      JvmClassKind.ENUM -> UnimportedDeclarationKind.ENUM
      JvmClassKind.ANNOTATION -> UnimportedDeclarationKind.ANNOTATION
    }

private fun unimportedItem(
    segment: String,
    candidate: UnimportedCandidate,
    layout: ImportLayout,
    classCallSnippet: Boolean,
): CompletionItem {
  val edit = importEdit(layout, candidate.fqName)
  val callSnippet =
      candidate.kind == UnimportedDeclarationKind.FUNCTION ||
          (candidate.kind == UnimportedDeclarationKind.CLASS && classCallSnippet)
  val item =
      CompletionItem(
          ideLabel = candidate.name,
          detail = candidate.fqName,
          insertText = if (callSnippet) "${candidate.name}(\$0)" else candidate.name,
          insertTextFormat = if (callSnippet) InsertTextFormat.SNIPPET else InsertTextFormat.PLAIN_TEXT,
          sortText = candidate.name,
          command = if (callSnippet) Command("Trigger Parameter Hints", Command.TRIGGER_PARAMETER_HINTS) else null,
          completionKind =
              when (candidate.kind) {
                UnimportedDeclarationKind.CLASS -> CompletionItemKind.CLASS
                UnimportedDeclarationKind.INTERFACE -> CompletionItemKind.INTERFACE
                UnimportedDeclarationKind.ENUM -> CompletionItemKind.ENUM
                UnimportedDeclarationKind.ANNOTATION -> CompletionItemKind.ANNOTATION_TYPE
                UnimportedDeclarationKind.FUNCTION -> CompletionItemKind.FUNCTION
                UnimportedDeclarationKind.PROPERTY -> CompletionItemKind.PROPERTY
              },
          matchLevel =
              when {
                candidate.name == segment -> MatchLevel.CASE_SENSITIVE_EQUAL
                candidate.name.startsWith(segment) -> MatchLevel.CASE_SENSITIVE_PREFIX
                candidate.name.lowercase().startsWith(segment.lowercase()) -> MatchLevel.CASE_INSENSITIVE_PREFIX
                else -> MatchLevel.NO_MATCH
              },
          additionalTextEdits = listOf(edit),
          data = null,
      )
  if (callSnippet) {
    item.snippetDescription = describeSnippet(prefix = segment, allowCommandExecution = true)
  }
  return item
}