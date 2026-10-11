@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
)

package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.lsp.models.LineIndex
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.compositeScope
import org.jetbrains.kotlin.analysis.api.components.scopeContext
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassifierSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import java.nio.file.Path

internal fun symbolName(symbol: KaSymbol): String? = symbol.name?.asString()

internal fun KaSession.completionItemsFor(prefix: String, element: org.jetbrains.kotlin.psi.KtElement): List<CompletionItem> {
  val scope = element.containingKtFile.scopeContext(element).compositeScope()
  val symbols = sequence<KaSymbol> {
    yieldAll(scope.callables { it.asString().startsWith(prefix, ignoreCase = true) })
    yieldAll(scope.classifiers { it.asString().startsWith(prefix, ignoreCase = true) })
  }
  return symbols
      .mapNotNull { completionItemFor(it, prefix) }
      .distinctBy { it.ideLabel }
      .take(50)
      .toList()

}

internal fun KaSession.completionItemFor(symbol: KaSymbol, prefix: String): CompletionItem? {
  val name = symbolName(symbol) ?: return null
  val kind = when (symbol) {
    is KaClassSymbol -> when (symbol.classKind) {
      KaClassKind.INTERFACE -> CompletionItemKind.INTERFACE
      KaClassKind.ENUM_CLASS -> CompletionItemKind.ENUM
      KaClassKind.ANNOTATION_CLASS -> CompletionItemKind.ANNOTATION_TYPE
      else -> CompletionItemKind.CLASS
    }
    is KaConstructorSymbol -> CompletionItemKind.CONSTRUCTOR
    is KaNamedFunctionSymbol -> CompletionItemKind.FUNCTION
    is KaPropertySymbol -> CompletionItemKind.PROPERTY
    is KaClassifierSymbol -> CompletionItemKind.CLASS
    else -> CompletionItemKind.NONE
  }
  val detail = when (symbol) {
    is KaNamedFunctionSymbol -> symbol.valueParameters.joinToString(", ", prefix = "$name(", postfix = ")") {
      "${it.name.asString()}: ${it.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)}"
    }
    is KaCallableSymbol -> symbol.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
    is KaDeclarationSymbol -> runCatching { symbol.render(org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource.WITH_SHORT_NAMES) }.getOrDefault(name)
    else -> name
  }
  val match = when {
    name == prefix -> MatchLevel.CASE_SENSITIVE_EQUAL
    name.startsWith(prefix) -> MatchLevel.CASE_SENSITIVE_PREFIX
    name.lowercase().startsWith(prefix.lowercase()) -> MatchLevel.CASE_INSENSITIVE_PREFIX
    else -> MatchLevel.NO_MATCH
  }
  return CompletionItem(
      ideLabel = name,
      detail = detail,
      insertText = if (symbol is KaNamedFunctionSymbol && symbol.valueParameters.isNotEmpty()) "$name(\$0)" else name,
      insertTextFormat = if (symbol is KaNamedFunctionSymbol && symbol.valueParameters.isNotEmpty()) InsertTextFormat.SNIPPET else InsertTextFormat.PLAIN_TEXT,
      sortText = name,
      command = null,
      completionKind = kind,
      matchLevel = match,
      additionalTextEdits = emptyList(),
      data = null,
  )
}

internal fun locationForElement(path: Path, file: KtFile, element: PsiElement): Location? {
  val range = runCatching { element.textRange }.getOrNull() ?: return null
  val text = runCatching { file.text }.getOrNull() ?: return null
  val start = range.startOffset.coerceIn(0, text.length)
  val end = range.endOffset.coerceIn(start, text.length)
  val index = LineIndex.from(text)
  return Location(path, Range(index.indexToPosition(start), index.indexToPosition(end)))
}