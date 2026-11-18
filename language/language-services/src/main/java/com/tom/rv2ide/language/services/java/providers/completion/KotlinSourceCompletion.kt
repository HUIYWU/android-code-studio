package com.tom.rv2ide.language.services.java.providers.completion

import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.language.services.java.edits.BaseJavaEditHandler
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path

/**
 * Java-side completion supplement for Kotlin source classes.
 *
 * javac answers Java symbols from its own model. For a Kotlin target the shared PSI answer is the
 * authority while Kotlin recognition is enabled. Otherwise the supplement only adds Java-visible
 * members that javac did not return.
 */
object KotlinSourceCompletion {

  @JvmStatic
  fun isKotlinOwner(
      javaFile: Path,
      cursor: Long,
      ownerQualifiedName: String?,
      cancelChecker: ICancelChecker,
  ): Boolean {
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) return false
    if (ownerQualifiedName.isNullOrEmpty()) return false
    val offset = cursor.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return JavaPsiInterop.kotlinOwner(javaFile, offset, ownerQualifiedName, cancelChecker)
  }

  @JvmStatic
  fun find(
      javaFile: Path,
      cursor: Long,
      ownerQualifiedName: String?,
      staticContext: Boolean,
      prefix: String,
      addParentheses: Boolean,
      cancelChecker: ICancelChecker,
  ): List<CompletionItem> {
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) return emptyList()
    if (ownerQualifiedName.isNullOrEmpty()) return emptyList()
    val offset = cursor.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return JavaPsiInterop.kotlinMembers(
        javaFile,
        offset,
        ownerQualifiedName,
        staticContext,
        prefix,
        addParentheses,
        cancelChecker,
    )
  }

  @JvmStatic
  fun findInnerClasses(
      javaFile: Path,
      cursor: Long,
      ownerQualifiedName: String?,
      partial: String,
      insertionPrefix: String,
      addParentheses: Boolean,
      cancelChecker: ICancelChecker,
  ): List<CompletionItem> {
    if (!JavaPreferences.isJavaKotlinRecognitionEnabled) return emptyList()
    if (ownerQualifiedName.isNullOrEmpty()) return emptyList()
    val offset = cursor.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val items =
        JavaPsiInterop.kotlinInnerClasses(javaFile, offset, ownerQualifiedName, partial, cancelChecker)
    if (addParentheses) {
      applyConstructorInsertion(items, partial, insertionPrefix)
    }
    return items
  }

  internal fun applyConstructorInsertion(
      items: List<CompletionItem>,
      partial: String,
      insertionPrefix: String,
  ) {
    for (item in items) {
      item.insertText = insertionPrefix + item.ideLabel + "(\$0)"
      item.insertTextFormat = InsertTextFormat.SNIPPET
      item.command = Command("Trigger Parameter Hints", Command.TRIGGER_PARAMETER_HINTS)
      item.snippetDescription = describeSnippet(partial, allowCommandExecution = true)
    }
  }

  /** Appends [supplements] that are not present yet, keeping the existing javac item. */
  internal fun merge(existing: MutableList<CompletionItem>, supplements: List<CompletionItem>) {
    if (supplements.isEmpty()) return
    val keys = existing.mapTo(HashSet()) { key(it) }
    for (item in supplements) {
      if (!keys.add(key(item))) continue
      item.editHandler = BaseJavaEditHandler()
      existing.add(item)
    }
  }

  private fun key(item: CompletionItem): String = "${item.ideLabel}\u0000${item.completionKind.name}"
}