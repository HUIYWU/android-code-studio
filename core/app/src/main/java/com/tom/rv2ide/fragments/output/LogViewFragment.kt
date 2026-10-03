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

package com.tom.rv2ide.fragments.output

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.FragmentLogBinding
import com.tom.rv2ide.editor.language.treesitter.LogLanguage
import com.tom.rv2ide.editor.language.treesitter.TreeSitterLanguageProvider
import com.tom.rv2ide.editor.schemes.IDEColorScheme
import com.tom.rv2ide.editor.schemes.IDEColorSchemeProvider
import com.tom.rv2ide.fragments.EmptyStateFragment
import com.tom.rv2ide.logging.IdeLogEntry
import com.tom.rv2ide.utils.jetbrainsMono
import io.github.rosemoe.sora.widget.style.CursorAnimator
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Fragment to show logs.
 *
 * @author Akash Yadav
 */
abstract class LogViewFragment :
    EmptyStateFragment<FragmentLogBinding>(R.layout.fragment_log, FragmentLogBinding::bind),
    ShareableOutputFragment {

  companion object {

    /** The maximum number of characters to append to the editor in case of huge log texts. */
    const val MAX_CHUNK_SIZE = 10000

    /**
     * The time duration, in milliseconds which is used to determine whether logs are too frequent
     * or not. If the logs are produced within this time duration, they are considered as too
     * frequent. In this case, the logs are cached and appended in chunks of [MAX_CHUNK_SIZE]
     * characters in size.
     */
    const val LOG_FREQUENCY = 120L

    /**
     * The time duration, in milliseconds we wait before appending the logs. This must be greater
     * than [LOG_FREQUENCY].
     */
    const val LOG_DELAY = 250L

    /**
     * Trim the logs when the number of lines reaches this value. Only [MAX_LINE_COUNT] number of
     * lines are kept in the logs.
     */
    const val TRIM_ON_LINE_COUNT = 2000

    /**
     * The maximum number of lines that are shown in the log view. This value must be less than
     * [TRIM_ON_LINE_COUNT] by a difference of [LOG_FREQUENCY] or preferably, more.
     */
    const val MAX_LINE_COUNT = 1600

    /** Keep auto-follow lightweight and avoid selection invalidation on every append. */
    const val FOLLOW_TAIL_THROTTLE_MS = 120L
  }

  private var lastLog = -1L
  private var lastTailFollow = 0L

  private val logFilter = LogFilter()

  /**
   * The log entries shown in the log view. The entries are kept in memory so that the log view can
   * be re-rendered when the filter changes.
   */
  private val entries = ArrayDeque<IdeLogEntry>()

  private val cacheLock = ReentrantLock()
  private val cache = StringBuilder()
  private var cacheLineTrack = ArrayBlockingQueue<Int>(MAX_LINE_COUNT, true)

  private val isTrimming = AtomicBoolean(false)

  private val logHandler = Handler(Looper.getMainLooper())
  private val logRunnable =
      object : Runnable {
        override fun run() {
          cacheLock.withLock {
            if (cacheLineTrack.size == MAX_LINE_COUNT) {
              cache.delete(0, cacheLineTrack.poll()!!)
            }

            cacheLineTrack.clear()

            if (cache.isEmpty()) {
              trimLinesAtStart()
              return
            }

            if (!shouldRenderLogsNow()) {
              logHandler.removeCallbacks(this)
              logHandler.postDelayed(this, LOG_DELAY)
              return
            }

            if (cache.length < MAX_CHUNK_SIZE) {
              append(cache)
              cache.clear()
            } else {
              // Append the lines in chunks to avoid UI lags
              val length = min(cache.length, MAX_CHUNK_SIZE)
              append(cache.subSequence(0, length))
              cache.delete(0, length)
            }

            if (cache.isNotEmpty()) {
              // if we still have data left to append, resechedule this
              logHandler.removeCallbacks(this)
              logHandler.postDelayed(this, LOG_DELAY)
            } else {
              trimLinesAtStart()
            }
          }
        }
      }

  /**
   * Appends the given log entry to the log view if it matches the current filter. The entry is kept
   * in memory, so that the log view can be re-rendered when the filter changes.
   */
  protected fun appendLog(entry: IdeLogEntry) {
    cacheLock.withLock {
      entries.addLast(entry)
      while (entries.size > MAX_LINE_COUNT) {
        entries.removeFirst()
      }

      if (logFilter.matches(entry.level, entry.tag, entry.message)) {
        appendLine(entry.formatted)
      }
    }
  }

  private fun appendLine(line: String) {
    var lineStr = line
    if (!lineStr.endsWith("\n")) {
      lineStr += "\n"
    }

    if (
        !shouldRenderLogsNow() ||
            isTrimming.get() ||
            cache.isNotEmpty() ||
            System.currentTimeMillis() - lastLog <= LOG_FREQUENCY
    ) {
      cacheLock.withLock {
        logHandler.removeCallbacks(logRunnable)

        // If the log lines are too frequent, cache the lines to log them later at once
        cache.append(lineStr)
        logHandler.postDelayed(logRunnable, LOG_DELAY)

        lastLog = System.currentTimeMillis()

        val length = cache.length + 1
        if (!cacheLineTrack.offer(length)) {
          cacheLineTrack.poll()
          cacheLineTrack.offer(length)
        }
      }
      return
    }

    lastLog = System.currentTimeMillis()

    append(lineStr)
    trimLinesAtStart()
  }

  private fun shouldRenderLogsNow(): Boolean {
    return isAdded && _binding?.editor != null
  }

  private fun append(chars: CharSequence?) {
    chars?.let { appended ->
      ThreadUtils.runOnUiThread {
        val editor = _binding?.editor ?: return@runOnUiThread
        val content = editor.text
        val wasNearBottom = isNearBottom(editor)
        val lastLine = content.lineCount - 1
        val lastColumn = content.getColumnCount(lastLine)

        content.beginBatchEdit()
        try {
          content.insert(lastLine, lastColumn, appended)
        } finally {
          content.endBatchEdit()
        }

        if (wasNearBottom) {
          followTailIfNeeded(editor, content)
        }
        updateLogsView(content.length > 0)
      }
    }
  }

  private fun trimLinesAtStart() {
    if (!isTrimming.compareAndSet(false, true)) {
      // trimming is already in progress
      return
    }

    ThreadUtils.runOnUiThread {
      try {
        val editor = _binding?.editor ?: return@runOnUiThread
        val content = editor.text
        if (content.lineCount <= TRIM_ON_LINE_COUNT) {
          return@runOnUiThread
        }

        val wasNearBottom = isNearBottom(editor)
        val startLine = (content.lineCount - MAX_LINE_COUNT).coerceAtLeast(0)
        val startIndex = content.getCharIndex(startLine, 0)
        if (startIndex > 0) {
          content.beginBatchEdit()
          try {
            content.delete(0, startIndex)
          } finally {
            content.endBatchEdit()
          }
        }
        if (wasNearBottom) {
          followTailIfNeeded(editor, content, force = true)
        }
      } finally {
        isTrimming.set(false)
      }
    }
  }

  private fun isNearBottom(editor: io.github.rosemoe.sora.widget.CodeEditor): Boolean {
    return editor.getScrollMaxY() - editor.offsetY <= editor.rowHeight * 3
  }

  private fun followTailIfNeeded(
      editor: io.github.rosemoe.sora.widget.CodeEditor,
      content: io.github.rosemoe.sora.text.Content,
      force: Boolean = false,
  ) {
    val now = System.currentTimeMillis()
    if (!force && now - lastTailFollow < FOLLOW_TAIL_THROTTLE_MS) {
      return
    }
    val lastLine = content.lineCount - 1
    val lastColumn = content.getColumnCount(lastLine)
    editor.ensurePositionVisible(lastLine, lastColumn, true)
    lastTailFollow = now
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val editor = this.binding.editor
    editor.props.autoIndent = false
    editor.isEditable = false
    editor.dividerWidth = 0f
    editor.isWordwrap = false
    editor.isUndoEnabled = false
    editor.typefaceLineNumber = jetbrainsMono()
    editor.setTextSize(8f)
    editor.typefaceText = jetbrainsMono()
    editor.isEnsurePosAnimEnabled = false
    IDEColorSchemeProvider.readSchemeAsync(
        context = requireContext(),
        coroutineScope = editor.editorScope,
        type = LogLanguage.TS_TYPE,
    ) { scheme ->
      val language =
          checkNotNull(TreeSitterLanguageProvider.forType(LogLanguage.TS_TYPE, requireContext())) {
            "No TreeSitterLanguage found for type ${LogLanguage.TS_TYPE}"
          }
      if (scheme is IDEColorScheme) {
        language.setupWith(scheme)
      }
      editor.applyTreeSitterLang(language, LogLanguage.TS_TYPE, scheme)
    }

    editor.cursorAnimator =
        object : CursorAnimator {
          override fun markStartPos() {
            // no-op
          }

          override fun markEndPos() {
            // no-op
          }

          override fun start() {
            // no-op
          }

          override fun cancel() {
            // no-op
          }

          override fun isRunning(): Boolean {
            return false
          }

          override fun animatedX(): Float {
            return 0f
          }

          override fun animatedY(): Float {
            return 0f
          }

          override fun animatedLineHeight(): Float {
            return 0f
          }

          override fun animatedLineBottom(): Float {
            return 0f
          }
        }

    TooltipCompat.setTooltipText(binding.btnFilterLog, getString(R.string.title_log_filter))
    binding.btnFilterLog.setOnClickListener {
      logFilter.showDialog(this) { refreshDisplay() }
    }

    editor.setText("")
    emptyStateViewModel.isEmpty.observe(viewLifecycleOwner) {
      emptyStateBinding?.root?.displayedChild = if (it) 0 else 1
    }
  }

  override fun onResume() {
    super.onResume()
    cacheLock.withLock {
      if (cache.isNotEmpty()) {
        logHandler.removeCallbacks(logRunnable)
        logHandler.post(logRunnable)
      }
    }
  }

  override fun onDestroyView() {
    _binding?.editor?.release()
    logHandler.removeCallbacks(logRunnable)
    super.onDestroyView()
  }

  /**
   * Re-renders the log view from the entries collected so far, with the current filter applied.
   */
  protected fun refreshDisplay() {
    val filtered =
        cacheLock.withLock {
          logHandler.removeCallbacks(logRunnable)
          cache.clear()
          cacheLineTrack.clear()
          entries.filter { logFilter.matches(it.level, it.tag, it.message) }
        }

    ThreadUtils.runOnUiThread {
      val editor = _binding?.editor ?: return@runOnUiThread
      val content = buildString {
        filtered.forEach {
          append(it.formatted)
          append('\n')
        }
      }

      editor.setText(content)
      updateLogsView(content.isNotEmpty())

      if (content.isNotEmpty()) {
        editor.postInLifecycle { followTailIfNeeded(editor, editor.text, force = true) }
      }
    }
  }

  private fun updateLogsView(hasLogs: Boolean) {
    emptyStateViewModel.isEmpty.value = false
    _binding?.emptyLogMessage?.isVisible = !hasLogs
    _binding?.editor?.isVisible = hasLogs
  }

  override fun clearOutput() {
    cacheLock.withLock {
      entries.clear()
    }
    refreshDisplay()
  }

  override fun getContent(): String {
    return _binding?.editor?.text?.toString() ?: ""
  }
}
