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
package com.tom.rv2ide.editor.ui
import android.content.Intent
import android.net.Uri
import com.tom.rv2ide.common.logging.IdeLogConfig
import android.widget.ListView
import com.tom.rv2ide.lsp.util.DocumentationReferenceProvider
import com.tom.rv2ide.progress.ProgressManager
import com.tom.rv2ide.utils.KeyboardUtils
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.widget.component.CompletionLayout
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import java.lang.ref.WeakReference
import kotlin.math.min
import org.slf4j.LoggerFactory



/**
 * Completion window for the editor.
 *
 * @author Akash Yadav
 */
class EditorCompletionWindow(val editor: IDEEditor) : EditorAutoCompletion(editor) {

  private var listView: ListView? = null
  private val items: MutableList<CompletionItem> = mutableListOf()
  @Volatile private var latestPublisherGeneration: Long = -1

  companion object {

    private val log = LoggerFactory.getLogger(EditorCompletionWindow::class.java)
    private val COMPLETION_SUPPRESS_CHARS =
        setOf(
            ';',
            ',',
            ':',
            '(',
            ')',
            '[',
            ']',
            '{',
            '}',
            '+',
            '-',
            '*',
            '/',
            '%',
            '=',
            '!',
            '&',
            '|',
            '<',
            '>',
            '?',
            '~',
            '^',
            '@',
            '#',
            '\'',
            '"',
            '\\',
            '`',
        )
  }

  init {
    setLayout(EditorCompletionLayout())
    setEnabledAnimation(true)
  }

  // Sora treats a missing span as "no completion". At end of line the caret is outside the last
  // character's span and the spans are commonly stale right after inserting '.', so dot
  // completion was hidden. Only relax that exact case; other spans keep their default check.
  override fun checkNoCompletion(): Boolean {
    if (!super.checkNoCompletion()) return false
    val text = editor.text
    val cursor = text.cursor.left()
    val line = text.getLine(cursor.line)
    if (cursor.column <= 0 || cursor.column != line.length) return true
    if (line[cursor.column - 1] != '.') return true
    val spans =
        runCatching { editor.styles?.spans?.read()?.getSpansOnLine(cursor.line) }.getOrNull()
            ?: return true
    if (spans.isEmpty()) return false
    val currentIndex = spans.indexOfLast { it.column <= cursor.column }
    if (currentIndex != spans.lastIndex) return true
    return false
  }

  override fun isShowing(): Boolean {
    @Suppress("UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
    return popup?.isShowing ?: false
  }

  override fun setLayout(layout: CompletionLayout) {
    super.setLayout(layout)
    (layout.completionList as? ListView)?.let {
      listView = it
      it.adapter = this.adapter
      it.setOnItemLongClickListener { _, view, position, _ ->
        val data =
            (items[position] as? com.tom.rv2ide.lsp.models.CompletionItem)?.data
                ?: return@setOnItemLongClickListener false
        val url =
            DocumentationReferenceProvider.getUrl(data) ?: return@setOnItemLongClickListener false
        Intent().apply {
          action = Intent.ACTION_VIEW
          setData(Uri.parse(url))
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          view.context.startActivity(this)
        }
        true
      }
    }
  }

  override fun select(pos: Int): Boolean {
    if (pos >= adapter!!.count) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn(
            "Rejecting completion select request because position is out of bounds: pos={}, count={}, currentSelection={}, itemsSize={}",
            pos,
            adapter!!.count,
            currentSelection,
            items.size,
        )
      }
      return false
    }
    return try {
      super.select(pos)
    } catch (e: Throwable) {
      val selectedItem = items.getOrNull(pos)
      log.error(
          "Unable to select completion item at {}: count={}, currentSelection={}, itemClass={}, item={}",
          pos,
          adapter!!.count,
          currentSelection,
          selectedItem?.javaClass?.name,
          selectedItem,
          e,
      )
      false
    }
  }
  override fun select() : Boolean {
    try {
      return super.select()
    } catch (e: Throwable) {
      val selectedItem = items.getOrNull(currentSelection)
      log.error(
          "Unable to select current completion item: currentSelection={}, count={}, itemClass={}, item={}",
          currentSelection,
          adapter?.count,
          selectedItem?.javaClass?.name,
          selectedItem,
          e,
      )
      return false
    }
  }


  override fun cancelCompletion() {
    if (completionThread != null) {
      ProgressManager.instance.cancel(completionThread)
    }
    super.cancelCompletion()
  }

  override fun requireCompletion() {
    if (cancelShowUp || !isEnabled || !editor.isAttachedToWindow) {
      return
    }

    val text = editor.text
    if (text.cursor.isSelected || checkNoCompletion()) {
      hide()
      return
    }

    val cursor = text.cursor.left()
    val line = text.getLine(cursor.line)
    val previousChar =
        if (cursor.column > 0 && cursor.column - 1 < line.length) {
          line[cursor.column - 1]
        } else {
          null
        }
    if (previousChar != null && previousChar in COMPLETION_SUPPRESS_CHARS) {
      if (IdeLogConfig.shouldLogDebug()) {
        log.debug(
            "EditorCompletionWindow.requireCompletion ignored after punctuation '{}' at line={}, column={}",
            previousChar,
            cursor.line,
            cursor.column,
        )
      }

      hide()
      return
    }

    if (System.nanoTime() - requestTime < editor.props.cancelCompletionNs) {
      hide()
      requestTime = System.nanoTime()
      return
    }

    cancelCompletion()
    requestTime = System.nanoTime()
    currentSelection = -1
    lateinit var requestPublisher: IDECompletionPublisher
    requestPublisher =
        IDECompletionPublisher(
            editor.handler,
            {
              val currentPublisher = publisher as? IDECompletionPublisher ?: return@IDECompletionPublisher
              if (
                  currentPublisher !== requestPublisher ||
                      requestPublisher.generation != latestPublisherGeneration ||
                      requestPublisher.isCancelled()
              ) {
                if (IdeLogConfig.shouldLogDebug()) {
                  log.debug(
                      "Dropping stale completion UI update: callbackGeneration={}, currentGeneration={}, latestGeneration={}, cancelled={}",
                      requestPublisher.generation,
                      currentPublisher.generation,
                      latestPublisherGeneration,
                      requestPublisher.isCancelled(),
                  )
                }
                return@IDECompletionPublisher
              }

              // Sora 0.23.7 invokes this callback once after CompletionThread returns. A deferred
              // request intentionally has no candidates yet; do not clear/hide the window before
              // the session worker publishes its result through this same publisher.
              if (requestPublisher.consumeDeferredResultMarker()) {
                return@IDECompletionPublisher
              }

              val items = requestPublisher.items


              this.items.apply {
                clear()
                addAll(items)
              }

              if (lastAttachedItems == null || lastAttachedItems.get() != items) {
                adapter.attachValues(this, items)
                adapter.notifyDataSetInvalidated()
                lastAttachedItems = WeakReference(items)
              } else {
                adapter.notifyDataSetChanged()
              }

              val newHeight = (adapter!!.itemHeight * adapter!!.count).toFloat()
              if (newHeight == 0F) {
                hide()
              }

              editor.getComponent(EditorAutoCompletion::class.java).updateCompletionWindowPosition()
              setSize(width, min(newHeight, maxHeight.toFloat()).toInt())
              if (!isShowing) {
                show()
              }

              if (adapter!!.count >= 1 && KeyboardUtils.isHardKeyboardConnected(context)) {
                currentSelection = 0
              }
            },
            editor.editorLanguage.interruptionLevel,
        )
    publisher = requestPublisher

    publisher.setUpdateThreshold(1)
    latestPublisherGeneration = (publisher as IDECompletionPublisher).generation

    completionThread = CompletionThread(requestTime, publisher)
    completionThread.name = "CompletionThread-$requestTime"

    setLoading(true)

    completionThread.start()
  }
}

