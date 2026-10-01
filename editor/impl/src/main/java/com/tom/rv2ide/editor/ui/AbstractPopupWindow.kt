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

import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.base.EditorPopupWindow
import org.slf4j.LoggerFactory

internal fun IDEEditor.imeAwarePopupBottom(): Int {
  if (imeBottomInset <= 0) {
    return height
  }

  val location = IntArray(2)
  getLocationInWindow(location)
  val rootHeight = rootView.height
  if (rootHeight <= 0) {
    return height
  }

  return (rootHeight - imeBottomInset - location[1]).coerceIn(0, height)
}

internal fun IDEEditor.constrainPopupHeight(height: Int): Int {
  return height.coerceAtMost(imeAwarePopupBottom())
}

internal fun IDEEditor.constrainPopupY(localY: Int, popupHeight: Int): Int {
  val maxY = (imeAwarePopupBottom() - popupHeight).coerceAtLeast(0)
  return localY.coerceIn(0, maxY)
}

/**
 * Abstract class for all [IDEEditor] popup windows.
 *
 * @author Akash Yadav
 */
abstract class AbstractPopupWindow(editor: CodeEditor, features: Int) :
    EditorPopupWindow(editor, features) {

  companion object {

    private val log = LoggerFactory.getLogger(AbstractPopupWindow::class.java)
  }

  override fun setSize(width: Int, height: Int) {
    val ideEditor = editor as? IDEEditor
    super.setSize(width, ideEditor?.constrainPopupHeight(height) ?: height)
  }

  override fun setLocation(x: Int, y: Int) {
    val ideEditor = editor as? IDEEditor
    if (ideEditor == null) {
      super.setLocation(x, y)
      return
    }

    val localY = y - ideEditor.offsetY
    val constrainedY = ideEditor.constrainPopupY(localY, height) + ideEditor.offsetY
    super.setLocation(x, constrainedY)
  }

  override fun show() {
    if (!isShowing()) {
      (editor as? IDEEditor)?.ensureWindowsDismissed()
    }
    if (!editor.isAttachedToWindow) {
      log.error(
          "Trying to show popup window '{}' when editor is not attached to window",
          javaClass.name,
      )
      return
    }

    super.show()
  }

  override fun isShowing(): Boolean {
    @Suppress("UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
    return popup?.isShowing ?: false
  }
}
