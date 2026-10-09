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
package com.tom.rv2ide.language.services.kotlin.actions

import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.lsp.actions.IActionsMenuProvider
import com.tom.rv2ide.language.services.kotlin.actions.common.GoToDefinitionAction

internal object KotlinCodeActionsMenu : IActionsMenuProvider {
  override val actions: List<ActionItem> =
      listOf(GoToDefinitionAction())
}
