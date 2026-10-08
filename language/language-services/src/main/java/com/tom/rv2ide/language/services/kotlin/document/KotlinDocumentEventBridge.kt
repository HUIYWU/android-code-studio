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

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinDocumentEventBridge(
    private val documentManager: KotlinDocumentSync,
    private val supportsDocument: (java.nio.file.Path) -> Boolean,
) {

  private val lastSaveTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
  private val saveDebounceMs = 350L

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onContentChange(event: com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent) {
    val file = event.changedFile
    if (!supportsDocument(file)) return

    val uri = file.toUri().toString()

    try {
      val snapshot = com.tom.rv2ide.projects.FileManager.getActiveDocumentSnapshot(file) ?: return
      val content = snapshot.content
      val currentTime = System.currentTimeMillis()
      val currentVersion = documentManager.getDocumentVersion(uri)

      if (snapshot.version >= 0 &&
          (!documentManager.isDocumentOpen(uri) || snapshot.version > currentVersion)) {
        documentManager.setDocumentVersion(uri, snapshot.version)
        documentManager.notifyDocumentChange(file, content, snapshot.version)
        // KLS diagnostics are commonly refreshed on didSave. Send it after didChange instead of
        // relying on stale disk content or a no-op save hook.
        val lastSaved = lastSaveTime[uri] ?: 0L
        val deltaSinceLastSave = currentTime - lastSaved
        if (deltaSinceLastSave >= saveDebounceMs) {
          documentManager.notifyDocumentSave(file, content)
          lastSaveTime[uri] = currentTime
        }
      } else {
        KlsLogs.debug("Skip Kotlin document change without a newer in-memory snapshot: {}", uri)
      }
    } catch (e: Exception) {
      KlsLogs.error("Failed to handle document change", e)
    }
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileOpened(event: com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent) {
    val file = event.openedFile
    if (!supportsDocument(file)) return

    KlsLogs.debug("Document open event for: {}", file)
    val snapshot = com.tom.rv2ide.projects.FileManager.getActiveDocumentSnapshot(file)
    documentManager.ensureDocumentOpen(
        file,
        snapshot?.content ?: event.text,
        snapshot?.version ?: event.version,
    )
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileSelected(event: com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent) {
    val file = event.selectedFile
    if (!supportsDocument(file)) return
    KlsLogs.debug("Document selected event for: {}", file)
    val snapshot = com.tom.rv2ide.projects.FileManager.getActiveDocumentSnapshot(file) ?: return
    documentManager.ensureDocumentOpen(file, snapshot.content, snapshot.version)
    documentManager.notifyDocumentSave(file, snapshot.content)
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileSaved(event: com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent) {
    val file = event.savedFile
    if (!supportsDocument(file)) return

    documentManager.notifyDocumentSave(file)
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileClosed(event: com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent) {
    val file = event.closedFile
    if (!supportsDocument(file)) return

    KlsLogs.debug("Document close event for: {}", file)
    lastSaveTime.remove(file.toUri().toString())
    documentManager.closeDocument(file)
  }
}
