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

import android.content.Context
import androidx.annotation.StringRes
import com.tom.rv2ide.models.ActionQuickItem
import com.tom.rv2ide.models.EditorQuickItem
import com.tom.rv2ide.models.Symbol
import com.tom.rv2ide.models.SymbolQuickItem
import com.tom.rv2ide.preferences.QuickInputTextType
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.resources.R
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Persisted, per-text-type quick-input items. */
object EditorQuickInputPreferences {
  const val MAX_ITEMS = 30

  data class QuickInputAction(val id: String, @StringRes val labelRes: Int)

  val supportedActions =
      listOf(
          QuickInputAction("ide.editor.text.uppercase", R.string.action_convert_to_uppercase),
          QuickInputAction("ide.editor.text.lowercase", R.string.action_convert_to_lowercase),
          QuickInputAction("ide.editor.text.deleteline", R.string.action_delete_line),
          QuickInputAction("ide.editor.text.duplicateline", R.string.action_duplicate_line),
          QuickInputAction("ide.editor.text.copyline", R.string.action_copy_line),
          QuickInputAction("ide.editor.text.commentline", R.string.action_comment_line),
          QuickInputAction("ide.editor.text.movelineup", R.string.action_move_line_up),
          QuickInputAction("ide.editor.text.movelinedown", R.string.action_move_line_down),
          QuickInputAction("ide.editor.text.insertlineabove", R.string.action_insert_line_above),
          QuickInputAction("ide.editor.text.insertlinebelow", R.string.action_insert_line_below),
          QuickInputAction("ide.editor.text.selectline", R.string.action_select_line),
          QuickInputAction("ide.editor.text.trimtrailingwhitespace", R.string.action_trim_trailing_whitespace),
          QuickInputAction("ide.editor.text.indentline", R.string.action_indent_line),
          QuickInputAction("ide.editor.text.unindentline", R.string.action_unindent_line),
          QuickInputAction("ide.editor.text.joinlines", R.string.action_join_lines),
          QuickInputAction("ide.editor.text.clearall", R.string.action_clear_all),
      )

  val defaultActionIds =
      listOf(
          "ide.editor.text.commentline",
          "ide.editor.text.duplicateline",
          "ide.editor.text.deleteline",
          "ide.editor.text.indentline",
          "ide.editor.text.unindentline",
      )

  private val supportedIds = supportedActions.map(QuickInputAction::id).toSet()

  fun labelForActionId(context: Context, id: String): String =
      supportedActions.firstOrNull { it.id == id }?.let { context.getString(it.labelRes) }
          ?: id

  fun textTypeFor(file: File?): QuickInputTextType =
      when (file?.extension) {
        "java", "gradle", "kt", "kts" -> QuickInputTextType.JAVA_JVM
        "xml" -> QuickInputTextType.XML
        else -> QuickInputTextType.PLAIN_TEXT
      }

  /** Symbols used by the editor's collapsed symbol bar. Text actions are deliberately excluded. */
  fun symbolsFor(file: File?): List<Symbol> {
    val type = textTypeFor(file)
    val baseSymbols = defaultSymbols(type)
    val symbolItems =
        baseSymbols.map {
          SymbolQuickItem(
              "symbol:${it.label}:${it.commit}:${it.offset}",
              it.label,
              it.commit,
              it.offset,
          )
        }
    return itemsFor(type, symbolItems)
        .mapNotNull { item ->
          (item as? SymbolQuickItem)?.let { Symbol(it.label.toString(), it.commit, it.offset) }
        }
        .ifEmpty { baseSymbols }
  }

  fun itemsFor(file: File?, symbols: List<EditorQuickItem>): List<EditorQuickItem> =
      itemsFor(textTypeFor(file), symbols)

  fun itemsFor(type: QuickInputTextType, symbols: List<EditorQuickItem>): List<EditorQuickItem> {
    val stored = readProfile(type) ?: return defaultItems(symbols)
    return stored.take(MAX_ITEMS).mapNotNull(::toQuickItem).ifEmpty { defaultItems(symbols) }
  }

  fun customizationItems(type: QuickInputTextType): List<StoredItem> {
    return readProfile(type) ?: defaultStoredItems(type)
  }

  fun saveItems(type: QuickInputTextType, items: List<StoredItem>) {
    val profiles = readProfiles()
    profiles.put(type.name, JSONArray().apply {
      items.take(MAX_ITEMS).forEach { put(it.toJson()) }
    })
    EditorPreferences.quickInputProfiles = JSONObject().put("profiles", profiles).toString()
  }

  fun resetItems(type: QuickInputTextType) {
    val profiles = readProfiles()
    profiles.remove(type.name)
    EditorPreferences.quickInputProfiles = JSONObject().put("profiles", profiles).toString()
  }

  data class StoredItem(
      val id: String,
      val label: String,
      val text: String? = null,
      val offset: Int = 0,
      val actionId: String? = null,
  ) {
    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("label", label).apply {
          if (actionId != null) {
            put("actionId", actionId)
          } else {
            put("text", text).put("offset", offset)
          }
        }
  }

  fun newItemId(): String = "custom:${UUID.randomUUID()}"

  fun insertion(label: String, text: String, offset: Int) =
      StoredItem(newItemId(), label, text, offset)

  fun action(label: String, actionId: String) =
      StoredItem(newItemId(), label, actionId = actionId)

  private fun defaultItems(symbols: List<EditorQuickItem>) =
      (symbols + defaultActionIds.map(::defaultAction)).take(MAX_ITEMS)

  private fun defaultSymbols(type: QuickInputTextType): List<Symbol> {
    val symbols =
        when (type) {
          QuickInputTextType.PLAIN_TEXT -> Symbols.plainTextSymbols
          QuickInputTextType.JAVA_JVM -> Symbols.forQuickInputTextType(type)
          QuickInputTextType.XML -> Symbols.forQuickInputTextType(type)
        }
    return symbols.take(MAX_ITEMS)
  }

  private fun defaultStoredItems(type: QuickInputTextType): List<StoredItem> {
    val symbols = defaultSymbols(type)
    return (symbols.map {
          StoredItem("builtin:${it.label}:${it.commit}:${it.offset}", it.label, it.commit, it.offset)
        } + defaultActionIds.map { id ->
          StoredItem("action:$id", defaultAction(id).label.toString(), actionId = id)
        })
        .take(MAX_ITEMS)
  }

  private fun defaultAction(id: String) =
      when (id) {
        "ide.editor.text.commentline" -> ActionQuickItem("action:comment-line", "Com", id)
        "ide.editor.text.duplicateline" -> ActionQuickItem("action:duplicate-line", "Dup", id)
        "ide.editor.text.deleteline" -> ActionQuickItem("action:delete-line", "Del", id)
        "ide.editor.text.indentline" -> ActionQuickItem("action:indent-line", "Ind", id)
        else -> ActionQuickItem("action:unindent-line", "Out", id)
      }

  private fun toQuickItem(item: StoredItem): EditorQuickItem? =
      when {
        item.actionId in supportedIds -> ActionQuickItem(item.id, item.label, item.actionId!!)
        item.actionId == null &&
            item.text != null &&
            item.offset in 0..item.text.length ->
            SymbolQuickItem(item.id, item.label, item.text, item.offset)
        else -> null
      }

  private fun readProfiles(): JSONObject =
      runCatching {
            JSONObject(EditorPreferences.quickInputProfiles ?: "{}")
                .optJSONObject("profiles") ?: JSONObject()
          }
          .getOrDefault(JSONObject())

  private fun readProfile(type: QuickInputTextType): List<StoredItem>? {
    val array = readProfiles().optJSONArray(type.name) ?: return null
    return List(array.length()) { index -> array.optJSONObject(index) }
        .mapNotNull { obj ->
          val item = obj ?: return@mapNotNull null
          val id = item.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
          val label = item.optString("label").takeIf(String::isNotEmpty) ?: return@mapNotNull null
          val text = item.optString("text").takeIf(String::isNotEmpty)
          val offset = item.optInt("offset")
          val actionId = item.optString("actionId").takeIf(String::isNotEmpty)
          if (text == null && actionId == null) {
            null
          } else {
            StoredItem(id, label, text, offset, actionId)
          }
        }
  }
}