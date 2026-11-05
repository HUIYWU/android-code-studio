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

package com.tom.rv2ide.utils

import com.tom.rv2ide.models.EditorQuickItem
import com.tom.rv2ide.models.Symbol
import com.tom.rv2ide.models.SymbolQuickItem
import java.io.File

object EditorQuickInputProvider {

  fun quickItemsFor(file: File?): List<EditorQuickItem> {
    val symbols = Symbols.forFile(file).ifEmpty { Symbols.plainTextSymbols }
    return EditorQuickInputPreferences.itemsFor(file, symbols.toQuickItems())
  }

  @JvmStatic
  fun defaultItems(): List<EditorQuickItem> = Symbols.plainTextSymbols.toQuickItems()

  private fun Symbol.toQuickItem(): SymbolQuickItem {
    return SymbolQuickItem(
        id = "symbol:$commit:$offset:$label",
        label = label,
        commit = commit,
        offset = offset,
    )
  }

  private fun List<Symbol>.toQuickItems(): List<EditorQuickItem> {
    return map { it.toQuickItem() }
  }
}