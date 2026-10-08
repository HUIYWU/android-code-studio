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

package com.tom.rv2ide.language.services.kotlin.document

import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class KotlinDocumentSync(
    private val connection: KotlinBackendConnection,
    private val isServerReady: () -> Boolean = { true },
) {

  companion object {
    private const val INITIAL_DID_SAVE_DELAY_MS = 1000L
  }
  private val openedDocuments = ConcurrentHashMap.newKeySet<String>()
  private val documentVersions = ConcurrentHashMap<String, Int>()
  fun ensureDocumentOpen(file: Path, content: String? = null, version: Int? = null) {
    if (!connection.supportsDocument(file) || !isServerReady()) return
    val uri = file.toUri().toString()
    if (openedDocuments.contains(uri)) {
      return
    }

    val text =
        content
            ?: try {
              file.toFile().readText()
            } catch (e: Exception) {
              KlsLogs.error("Failed to read file: {}", file, e)
              return
            }

    val requestedVersion = version ?: (getDocumentVersion(uri) + 1).coerceAtLeast(1)

    openDocumentNow(file, uri, text, requestedVersion)
  }

  private fun openDocumentNow(file: Path, uri: String, text: String, version: Int): Boolean {
    if (!connection.supportsDocument(file) || !isServerReady()) return false
    setDocumentVersion(uri, version)

    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                addProperty("languageId", "kotlin")
                addProperty("version", version)
                addProperty("text", text)
              },
          )
        }

    return try {
      connection.sendNotificationOrThrow("textDocument/didOpen", params)
      openedDocuments.add(uri)

      // Keep the initial open path responsive. The follow-up didSave is only a bootstrap lint
      // trigger for servers that need an explicit save after open, so it can be delayed until
      // after the editor has rendered its first frames.
      android.os
          .Handler(android.os.Looper.getMainLooper())
          .postDelayed(
              {
                notifyDocumentSave(file)
              },
              INITIAL_DID_SAVE_DELAY_MS,
          )
      true
    } catch (e: Exception) {
      KlsLogs.warn("didOpen failed, document remains unopened: {}", uri, e)
      openedDocuments.remove(uri)
      false
    }
  }

  fun notifyDocumentChange(file: Path, newText: String, version: Int) {
    if (!connection.supportsDocument(file) || !isServerReady()) return
    val uri = file.toUri().toString()

    if (!openedDocuments.contains(uri)) {
      KlsLogs.warn("Document not opened, opening it first: {}", uri)
      ensureDocumentOpen(file, newText, version)
      return
    }

    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                addProperty("version", version)
              },
          )
          add(
              "contentChanges",
              com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("text", newText) })
              },
          )
        }

    connection.sendNotification("textDocument/didChange", params)
  }

  fun notifyDocumentSave(file: Path, text: String? = null) {
    if (!connection.supportsDocument(file) || !isServerReady()) return
    val uri = file.toUri().toString()
    if (!openedDocuments.contains(uri)) {
      return
    }

    val currentText =
        text
            ?: try {
              file.toFile().readText()
            } catch (e: Exception) {
              KlsLogs.warn("Failed to read file for didSave, sending save without text: {}", uri, e)
              null
            }

    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                if (currentText != null) {
                  addProperty("text", currentText)
                }
              },
          )
        }
    connection.sendNotification("textDocument/didSave", params)
  }

  fun closeDocument(file: Path) {
    val uri = file.toUri().toString()
    if (openedDocuments.remove(uri)) {
      documentVersions.remove(uri)
      val params =
          JsonObject().apply { add("textDocument", JsonObject().apply { addProperty("uri", uri) }) }
      connection.sendNotification("textDocument/didClose", params)
    }
  }

  fun isDocumentOpen(uri: String): Boolean = openedDocuments.contains(uri)

  fun getDocumentVersion(uri: String): Int = documentVersions.getOrDefault(uri, 0)

  fun setDocumentVersion(uri: String, version: Int) {
    documentVersions[uri] = version
  }

  fun clear() {
    openedDocuments.clear()
    documentVersions.clear()
  }

  fun resyncActiveDocuments() {
    clear()
    FileManager.getActiveDocumentFiles()
        .filter(connection::supportsDocument)
        .forEach { file ->
          val snapshot = FileManager.getActiveDocumentSnapshot(file)
          ensureDocumentOpen(file, snapshot?.content, snapshot?.version)
        }
  }
}
