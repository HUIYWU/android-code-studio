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

package com.tom.rv2ide.fragments.output

import androidx.fragment.app.Fragment
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.tom.rv2ide.R

/**
 * Text based filter used by the log viewers. The filter text is matched against the level, the tag
 * or the message of a log line, depending on the selected [Field]. If no field is selected, all of
 * them are searched.
 */
class LogFilter {

  private enum class Field {
    LEVEL,
    TAG,
    MESSAGE,
  }

  private var text: String? = null
  private var field: Field? = null

  fun matches(level: String, tag: String, message: String): Boolean {
    val query = text ?: return true

    val fields =
        when (field) {
          Field.LEVEL -> listOf(level)
          Field.TAG -> listOf(tag)
          Field.MESSAGE -> listOf(message)
          null -> listOf(level, tag, message)
        }

    return fields.any { it.contains(query, ignoreCase = true) }
  }

  /** Shows the filter dialog. [onFilterChanged] is invoked when the filter is applied or cleared. */
  fun showDialog(fragment: Fragment, onFilterChanged: () -> Unit) {
    val content = fragment.layoutInflater.inflate(R.layout.dialog_log_filter, null)
    val filterInput = content.findViewById<TextInputEditText>(R.id.filterTextInput)
    val filterGroup = content.findViewById<ChipGroup>(R.id.filterFieldGroup)

    filterInput.setText(text.orEmpty())
    field?.let { filterGroup.check(it.chipId()) }

    MaterialAlertDialogBuilder(fragment.requireContext())
        .setTitle(R.string.title_log_filter)
        .setView(content)
        .setPositiveButton(R.string.title_apply) { _, _ ->
          text = filterInput.text.toString().trim().ifEmpty { null }
          field = filterFieldFor(filterGroup.checkedChipId)
          onFilterChanged()
        }
        .setNegativeButton(R.string.action_cancel, null)
        .setNeutralButton(R.string.clear) { _, _ ->
          text = null
          field = null
          onFilterChanged()
        }
        .show()
  }

  private fun Field.chipId(): Int =
      when (this) {
        Field.LEVEL -> R.id.chipFilterLevel
        Field.TAG -> R.id.chipFilterTag
        Field.MESSAGE -> R.id.chipFilterMessage
      }

  private fun filterFieldFor(chipId: Int): Field? =
      when (chipId) {
        R.id.chipFilterLevel -> Field.LEVEL
        R.id.chipFilterTag -> Field.TAG
        R.id.chipFilterMessage -> Field.MESSAGE
        else -> null
      }
}
