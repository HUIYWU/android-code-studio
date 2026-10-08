/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.editor.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.progress.ICancelChecker
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/** Manages hover tooltips showing documentation when cursor hovers over code */
class HoverTooltipManager(private val context: Context, private val editor: IDEEditor) {

  companion object {
    private val log = LoggerFactory.getLogger(HoverTooltipManager::class.java)
    private const val HOVER_DELAY = 800L
  }

  private val handler = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private val markdownRenderer = HoverMarkdownRenderer(context)

  private var currentJob: Job? = null
  private var hoverRunnable: Runnable? = null
  private var selectionReceipt: SubscriptionReceipt<SelectionChangeEvent>? = null
  private var lastHoverLine = -1
  private var lastHoverColumn = -1
  private var isActive = true
  private var requestGeneration = 0L

  /** Initialize hover support */
  fun init() {
    // Listen to cursor/selection changes.
    selectionReceipt =
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> handleCursorMove() }
  }

  private fun handleCursorMove() {
    val cursor = editor.cursor
    val line = cursor.leftLine
    val column = cursor.leftColumn

    // If same position, don't restart
    if (line == lastHoverLine && column == lastHoverColumn) {
      return
    }

    lastHoverLine = line
    lastHoverColumn = column

    cancelHover()

    // Schedule hover request after cursor stops moving
    hoverRunnable = Runnable { requestHover(line, column) }
    handler.postDelayed(hoverRunnable!!, HOVER_DELAY)
  }

  private fun cancelHover() {
    hoverRunnable?.let { handler.removeCallbacks(it) }
    hoverRunnable = null
    currentJob?.cancel()
    currentJob = null
    editor.dismissHoverWindow()
  }

  private fun requestHover(line: Int, column: Int) {
    if (!isActive) return
    val file = editor.file ?: return
    val languageServer = editor.languageServer ?: return
    val generation = requestGeneration
    val requestVersion = editor.currentDocumentVersion
    val requestRevision = editor.currentDocumentRevision

    currentJob =
        scope.launch {
          try {
            val cancelChecker =
                object : ICancelChecker {
                  override fun isCancelled(): Boolean {
                    val job = currentJob
                    return job == null || !job.isActive
                  }

                  override fun abortIfCancelled() {
                    if (isCancelled()) {
                      throw CancellationException("Operation cancelled")
                    }
                  }

                  override fun cancel() {
                    currentJob?.cancel()
                  }
                }

            val params =
                DefinitionParams(
                    file = file.toPath(),
                    position = Position(line, column),
                    cancelChecker = cancelChecker,
                ).apply {
                  documentVersion = requestVersion
                  documentRevision = requestRevision
                }

            val hoverResult = withContext(Dispatchers.IO) { languageServer.hover(params) }

            // Filter out meaningless hover results
            val content = hoverResult.value.trim()
            if (
              isActive &&
                  generation == requestGeneration &&
                  editor.isShown &&
                  content.isNotEmpty() &&
                  content != "Unit" &&
                  !content.equals("unit", ignoreCase = true) &&
                  content.length > 2
            ) {
              withContext(Dispatchers.Main) { displayTooltip(hoverResult) }
            }
          } catch (e: Exception) {
            if (e !is CancellationException && IdeLogConfig.shouldLogDebug()) {
              log.debug("Failed to fetch hover info", e)
            }
          }

        }
  }

  private fun displayTooltip(content: MarkupContent) {
    if (!isActive || !editor.isShown || !editor.canShowHoverWindow()) return

    try {
      editor.hoverWindow.showHover(markdownRenderer.render(content))
    } catch (e: Exception) {
      log.error("Failed to display hover window", e)
    }
  }

  fun setVisible(visible: Boolean) {
    if (isActive == visible) return
    isActive = visible
    requestGeneration++
    if (visible) {
      return
    }
    cancelHover()
  }

  fun destroy() {
    isActive = false
    requestGeneration++
    selectionReceipt?.unsubscribe()
    selectionReceipt = null
    cancelHover()
    scope.cancel()
  }
}

// Extension functions
private const val HOVER_TOOLTIP_TAG = 0x7F0A0002

fun IDEEditor.initHoverTooltips() {
  (getTag(HOVER_TOOLTIP_TAG) as? HoverTooltipManager)?.destroy()
  val tooltipManager = HoverTooltipManager(context, this)
  tooltipManager.init()
  setTag(HOVER_TOOLTIP_TAG, tooltipManager)
}

fun IDEEditor.setHoverTooltipsVisible(visible: Boolean) {
  (getTag(HOVER_TOOLTIP_TAG) as? HoverTooltipManager)?.setVisible(visible)
}

fun IDEEditor.cleanupHoverTooltips() {
  val tooltipManager = getTag(HOVER_TOOLTIP_TAG) as? HoverTooltipManager
  tooltipManager?.destroy()
  setTag(HOVER_TOOLTIP_TAG, null)
}
