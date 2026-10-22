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

package com.tom.rv2ide.lsp.edits

import android.os.Looper
import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.InsertTextFormat.SNIPPET
import com.tom.rv2ide.lsp.models.SnippetDescription
import com.tom.rv2ide.lsp.util.RewriteHelper
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import org.slf4j.LoggerFactory

/**
 * Default edit handler for completion items.
 *
 * @author Akash Yadav
 */
open class DefaultEditHandler : IEditHandler {

  companion object {

    private val log = LoggerFactory.getLogger(DefaultEditHandler::class.java)
  }

  override fun performEdits(
      item: CompletionItem,
      editor: CodeEditor,
      text: Content,
      line: Int,
      column: Int,
      index: Int,
  ) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      ThreadUtils.runOnUiThread {
        try {
          performEditsInternal(item, editor, text, line, column, index)
        } catch (t: Throwable) {
          log.error(
              "DefaultEditHandler.performEdits(ui-dispatch) failed: label='{}', insertText='{}', format={}, line={}, column={}, index={}, kind={}, command={}, additionalTextEditsCount={}, additionalEditHandler={}, editorClass={}",
              item.ideLabel,
              item.insertText,
              item.insertTextFormat,
              line,
              column,
              index,
              item.completionKind,
              item.command?.command,
              item.additionalTextEdits?.size ?: 0,
              item.additionalEditHandler?.javaClass?.name,
              editor::class.java.name,
              t,
          )
          throw t
        }
      }
      return
    }

    try {
      performEditsInternal(item, editor, text, line, column, index)
    } catch (t: Throwable) {
      log.error(
          "DefaultEditHandler.performEdits failed: label='{}', insertText='{}', format={}, line={}, column={}, index={}, kind={}, command={}, additionalTextEditsCount={}, additionalEditHandler={}, editorClass={}",
          item.ideLabel,
          item.insertText,
          item.insertTextFormat,
          line,
          column,
          index,
          item.completionKind,
          item.command?.command,
          item.additionalTextEdits?.size ?: 0,
          item.additionalEditHandler?.javaClass?.name,
          editor::class.java.name,
          t,
      )
      throw t
    }
  }

  protected open fun performEditsInternal(
      item: CompletionItem,
      editor: CodeEditor,
      text: Content,
      line: Int,
      column: Int,
      index: Int,
  ) {
    if (item.insertTextFormat == SNIPPET) {
      insertSnippet(item, editor, text, line, column, index)
      return
    }

    val lineText = text.getLine(line)
    val start = getIdentifierStart(lineText, column)
    text.delete(line, start, line, column)
    editor.commitText(item.insertText)

    text.beginBatchEdit()
    try {
      if (item.additionalEditHandler != null) {
        item.additionalEditHandler!!.performEdits(item, editor, text, line, column, index)
      } else if (item.additionalTextEdits != null && item.additionalTextEdits!!.isNotEmpty()) {
        RewriteHelper.performEdits(item.additionalTextEdits!!, editor)
      }
    } finally {
      text.endBatchEdit()
    }

    executeCommand(editor, item.command)
  }

  protected open fun insertSnippet(
      item: CompletionItem,
      editor: CodeEditor,
      text: Content,
      line: Int,
      column: Int,
      index: Int,
  ) {
    val snippetDescription: SnippetDescription =
        item.snippetDescription
            ?: SnippetDescription(
                selectedLength = 0,
                deleteSelected = false,
                snippet = null,
                allowCommandExecution = false,
            ).also {
              if (IdeLogConfig.shouldLogWarn()) {
                log.warn(
                    "DefaultEditHandler.insertSnippet: snippetDescription missing, using fallback. label='{}', insertText='{}', line={}, column={}, index={}",
                    item.ideLabel,
                    item.insertText,
                    line,
                    column,
                    index,
                )
              }
            }
    val snippet = CodeSnippetParser.parse(item.insertText)
    var prefixLength = snippetDescription.selectedLength
    if (prefixLength <= 0) {
      val lineText = text.getLine(line)
      val fallbackStart = getIdentifierStart(lineText, column)
      prefixLength = column - fallbackStart
    }
    val selectedText = text.subSequence(index - prefixLength, index).toString()
    var actionIndex = index
    if (snippetDescription.deleteSelected) {
      text.delete(index - prefixLength, index)
      actionIndex -= prefixLength
    }
    editor.snippetController.startSnippet(actionIndex, snippet, selectedText)

    if (snippetDescription.allowCommandExecution) {
      executeCommand(editor, item.command)
    }
  }

  protected open fun executeCommand(editor: CodeEditor, command: Command?) {
    if (command == null) {
      return
    }

    try {
      val klass = editor::class.java
      val method = klass.getMethod("executeCommand", Command::class.java)
      method.isAccessible = true
      method.invoke(editor, command)
    } catch (th: Throwable) {
      log.error("Unable to invoke 'executeCommand(Command) method in IDEEditor.", th)
    }
  }

  protected open fun getIdentifierStart(text: CharSequence, end: Int): Int {
    var start = end
    while (start > 0) {
      if (isPartialPart(text[start - 1])) {
        start--
        continue
      }
      break
    }
    return start
  }

  protected open fun isPartialPart(c: Char) = Character.isJavaIdentifierPart(c)
}
