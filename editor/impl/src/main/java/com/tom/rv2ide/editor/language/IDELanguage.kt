/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.editor.language

import android.os.Bundle
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.editor.ui.IDECompletionPublisher
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.lsp.api.CompletionCancellation
import com.tom.rv2ide.progress.ICancelChecker
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Base class for language implementations in the IDE.
 *
 * @author Akash Yadav
 */
abstract class IDELanguage : Language {

  private var formatter: Formatter? = null

  protected open val languageServer: ILanguageServer?
    get() = null

  open fun getTabSize(): Int {
    return EditorPreferences.tabSize
  }

  @Throws(CompletionCancelledException::class)
  override fun requireAutoComplete(
      content: ContentReference,
      position: CharPosition,
      publisher: CompletionPublisher,
      extraArguments: Bundle,
  ) {
    val cancelChecker = CompletionCancelChecker(publisher)
    val previousChecker = CompletionCancellation.install(cancelChecker)
    try {
      doComplete(content, position, publisher, cancelChecker, extraArguments)
    } finally {
      CompletionCancellation.restore(previousChecker)
    }
  }

  private fun doComplete(
      content: ContentReference,
      position: CharPosition,
      publisher: CompletionPublisher,
      cancelChecker: CompletionCancelChecker,
      extraArguments: Bundle,
  ) {
    val server = languageServer ?: return
    val path = extraArguments.getString(IEditor.KEY_FILE, null)
    if (path == null) {
      log.warn("Cannot provide completions. No file provided.")
      return
    }

    val completionProvider = CommonCompletionProvider(server, cancelChecker)
    val file = Paths.get(path)
    val documentVersion = extraArguments.getInt(IEditor.KEY_DOCUMENT_VERSION, -1)
    val documentRevision = extraArguments.getLong(IEditor.KEY_DOCUMENT_REVISION, -1L)
    val completionResult =
        completionProvider.complete(content, file, position, documentVersion, documentRevision) {
          checkIsCompletionChar(it)
        }
    val idePublisher = publisher as IDECompletionPublisher
    publisher.setUpdateThreshold(1)
    val deferred = completionResult.deferredResult
    if (deferred == null) {
      idePublisher.addLSPItems(completionResult.items)
      return
    }

    // Sora 0.23.7 calls updateList() once when this CompletionThread returns. Keep the publisher
    // alive for the session worker instead of letting that empty immediate result hide the window.
    idePublisher.awaitDeferredResult()
    val detached = AtomicBoolean(false)
    fun detach() {
      if (detached.compareAndSet(false, true)) {
        completionResult.deferredDetach?.invoke()
      }
    }
    idePublisher.onCancelled(::detach)
    deferred.whenComplete { result, error ->
      try {
        if (error == null && result != null && !idePublisher.isCancelled()) {
          // Keep the marker until the callback observes these items. The worker can finish before
          // Sora 0.23.7 performs CompletionThread's initial updateList(true) callback.
          if (result.items.isEmpty()) {
            idePublisher.publishEmptyDeferredResult()
          } else {
            idePublisher.addLSPItems(result.items)
          }
        } else {
          // A failed or cancelled worker must not leave the initial Sora callback permanently
          // marked as deferred. If it has not run yet, Sora will hide the normal empty result.
          idePublisher.completeDeferredResult()
        }
      } finally {
        detach()
      }
    }
  }

  /**
   * Check if the given character is a completion character.
   *
   * @param c The character to check.
   * @return `true` if the character is completion char, `false` otherwise.
   */
  protected open fun checkIsCompletionChar(c: Char): Boolean {
    return false
  }

  override fun useTab(): Boolean {
    return !EditorPreferences.useSoftTab
  }

  override fun getFormatter(): Formatter {
    return formatter ?: LSPFormatter(languageServer).also { formatter = it }
  }

  override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
    return getIndentAdvance(content.getLine(line).substring(0, column))
  }

  open fun getIndentAdvance(line: String): Int {
    return 0
  }

  companion object {

    private val log = LoggerFactory.getLogger(IDELanguage::class.java)
  }
}
