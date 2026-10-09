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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.language.services.kotlin.actions.common

import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.editor.api.ILspEditor
import com.tom.rv2ide.language.services.kotlin.actions.BaseKotlinCodeAction
import com.tom.rv2ide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor

internal class GoToDefinitionAction : BaseKotlinCodeAction() {
  override val titleTextRes: Int = R.string.action_goto_definition
  override val id: String = "ide.editor.lsp.kotlin.gotoDefinition"
  override var label: String = ""
  override var requiresUIThread: Boolean = true

  override fun prepare(data: ActionData) {
    super.prepare(data)
    if (!visible || !hasEditor(data)) {
      markInvisible()
    }
  }

  override suspend fun execAction(data: ActionData): Any {
    val editor = data[CodeEditor::class.java]!!
    return (editor as? ILspEditor)?.findDefinition() ?: false
  }
}