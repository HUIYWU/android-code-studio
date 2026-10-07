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
package com.tom.rv2ide.lsp.clang

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.SnippetDescription

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class ClangCompletionConverter {


  fun convert(jsonItems: JsonArray, prefix: String = ""): List<CompletionItem> {
    return jsonItems.mapNotNull { element ->
      var labelForLog: String? = null
      try {
        val item = element.asJsonObject
        val label = item.getAsSafeString("label") ?: return@mapNotNull null
        labelForLog = label
        val kind = item.get("kind")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        val detail = item.getAsSafeString("detail") ?: ""
        val insertText =
            item.getAsSafeString("insertText")
                ?: item.getTextEditNewText()
                ?: label
        val insertTextFormat =
            when (item.get("insertTextFormat")?.takeIf { it.isJsonPrimitive }?.asInt) {
              2 -> InsertTextFormat.SNIPPET
              else -> InsertTextFormat.PLAIN_TEXT
            }
        val documentation = item.getDocumentationString()

        CompletionItem(
                ideLabel = label,
                detail = detail,
                insertText = insertText,
                insertTextFormat = insertTextFormat,
                sortText = item.getAsSafeString("sortText") ?: label,
                command = null,
                completionKind = convertKind(kind),
                matchLevel = MatchLevel.NO_MATCH,
                additionalTextEdits = null,
                data = null,
            )
            .apply {
              documentation?.let { this.desc = it }
              if (insertTextFormat == InsertTextFormat.SNIPPET) {
                this.snippetDescription =
                    SnippetDescription(
                        selectedLength = prefix.length,
                        deleteSelected = true,
                        snippet = null,
                        allowCommandExecution = false,
                    )
                ClangLogs.info(
                    "ClangCompletionConverter: assigned snippetDescription for snippet item: label='{}', insertText='{}', prefix='{}', selectedLength={}, deleteSelected={}",
                    label,
                    insertText,
                    prefix,
                    this.snippetDescription?.selectedLength,
                    this.snippetDescription?.deleteSelected,
                )
              }
            }
      } catch (e: Exception) {
        ClangLogs.error("Error converting completion item: label={}", labelForLog ?: "<unknown>", e)
        null
      }
    }
  }


  private fun JsonObject.getAsSafeString(name: String): String? {
    val value = get(name) ?: return null
    return value.takeIf { it.isJsonPrimitive }?.asString
  }

  private fun JsonObject.getTextEditNewText(): String? {
    val textEdit = get("textEdit")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null

    textEdit.getAsSafeString("newText")?.let { return it }

    val textEditText =
        textEdit.get("textEdit")?.takeIf { it.isJsonObject }?.asJsonObject?.getAsSafeString("newText")
    if (textEditText != null) {
      return textEditText
    }

    val insertReplaceEdit =
        textEdit.get("insert")?.takeIf { it.isJsonObject }?.asJsonObject?.getAsSafeString("newText")
    if (insertReplaceEdit != null) {
      return insertReplaceEdit
    }

    return null
  }

  private fun JsonObject.getDocumentationString(): String? {
    val documentation = get("documentation") ?: return null
    return documentation.asCompletionDocString()
  }

  private fun JsonElement.asCompletionDocString(): String? {
    return when {
      isJsonNull -> null
      isJsonPrimitive -> asString
      isJsonObject -> {
        val obj = asJsonObject
        obj.getAsSafeString("value") ?: obj.getAsSafeString("kind")
      }
      else -> toString()
    }
  }

  private fun convertKind(lspKind: Int): CompletionItemKind {
    return when (lspKind) {
      2 -> CompletionItemKind.METHOD
      3 -> CompletionItemKind.FUNCTION
      4 -> CompletionItemKind.CONSTRUCTOR
      5 -> CompletionItemKind.FIELD
      6 -> CompletionItemKind.VARIABLE
      7 -> CompletionItemKind.CLASS
      8 -> CompletionItemKind.INTERFACE
      9 -> CompletionItemKind.MODULE
      10 -> CompletionItemKind.PROPERTY
      12 -> CompletionItemKind.VALUE
      13 -> CompletionItemKind.ENUM
      14 -> CompletionItemKind.KEYWORD
      15 -> CompletionItemKind.SNIPPET
      21 -> CompletionItemKind.ENUM_MEMBER
      26 -> CompletionItemKind.TYPE_PARAMETER
      else -> CompletionItemKind.NONE
    }
  }
}
