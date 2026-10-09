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
  private val documentRevisions = ConcurrentHashMap<String, Long>()
  @Synchronized
  fun ensureDocumentOpen(file: Path, content: String? = null, version: Int? = null) {
    if (!connection.supportsDocument(file) || !isServerReady()) return
    val uri = file.toUri().toString()
    val snapshot = FileManager.getActiveDocumentSnapshot(file)
    if (openedDocuments.contains(uri)) {
      if (snapshot != null && snapshot.revision != documentRevisions[uri] &&
          snapshot.version <= getDocumentVersion(uri)) {
        closeDocument(file)
      } else return
    }
    val text =
        snapshot?.content ?: content
            ?: try {
              file.toFile().readText()
            } catch (e: Exception) {
              KlsLogs.error("Failed to read file: {}", file, e)
              return
            }

    val requestedVersion = snapshot?.version ?: version ?: 1

    openDocumentNow(file, uri, text, requestedVersion, snapshot?.revision)
  }

  private fun openDocumentNow(file: Path, uri: String, text: String, version: Int, revision: Long?): Boolean {
    if (!connection.supportsDocument(file) || !isServerReady()) return false

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
      revision?.let { documentRevisions[uri] = it }
      documentVersions[uri] = version
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

  @Synchronized
  private fun notifyDocumentChange(file: Path, newText: String, version: Int, revision: Long) {
    if (!connection.supportsDocument(file) || !isServerReady()) return
    val uri = file.toUri().toString()

    if (!openedDocuments.contains(uri)) {
      KlsLogs.warn("Document not opened, opening it first: {}", uri)
      ensureDocumentOpen(file, newText, version)
      return
    }

    if (version <= getDocumentVersion(uri)) return

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

    connection.sendNotificationOrThrow("textDocument/didChange", params)
    documentRevisions[uri] = revision
    documentVersions[uri] = version
  }

  @Synchronized
  fun notifyDocumentSave(file: Path, text: String? = null) {
    if (!connection.supportsDocument(file) || !isServerReady()) return
    val uri = file.toUri().toString()
    if (!FileManager.isActive(file)) return
    syncActiveDocument(file)
    if (!openedDocuments.contains(uri)) return

    val currentText =
        FileManager.getActiveDocumentSnapshot(file)?.content ?: text
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
              JsonObject().apply { addProperty("uri", uri) },
          )
          if (currentText != null) addProperty("text", currentText)
        }
    connection.sendNotification("textDocument/didSave", params)
  }

  @Synchronized
  fun closeDocument(file: Path) {
    val uri = file.toUri().toString()
    if (openedDocuments.remove(uri)) {
      documentVersions.remove(uri)
      documentRevisions.remove(uri)
      val params =
          JsonObject().apply { add("textDocument", JsonObject().apply { addProperty("uri", uri) }) }
      connection.sendNotification("textDocument/didClose", params)
    }
  }

  @Synchronized
  fun closeDocumentsUnder(root: Path) {
    openedDocuments.toList().map { java.nio.file.Paths.get(java.net.URI(it)) }
        .filter { it.normalize().startsWith(root.normalize()) }
        .forEach(::closeDocument)
  }

  @Synchronized
  fun syncActiveDocument(file: Path) {
    val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return
    val uri = file.toUri().toString()
    ensureDocumentOpen(file, snapshot.content, snapshot.version)
    if (snapshot.version > getDocumentVersion(uri)) {
      notifyDocumentChange(file, snapshot.content, snapshot.version, snapshot.revision)
    }
  }

  fun isDocumentOpen(uri: String): Boolean = openedDocuments.contains(uri)

  fun getDocumentVersion(uri: String): Int = documentVersions.getOrDefault(uri, 0)

  @Synchronized
  fun clear() {
    openedDocuments.toList().map { java.nio.file.Paths.get(java.net.URI(it)) }.forEach(::closeDocument)
    openedDocuments.clear()
    documentVersions.clear()
    documentRevisions.clear()
  }

  @Synchronized
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
