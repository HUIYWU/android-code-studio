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

package com.tom.rv2ide.language.services.kotlin

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinEventHandler(private val documentManager: KotlinDocumentManager) {

  private val lastChangeTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
  private val changeThrottleMs = 100L // Only process changes every 100ms
  private val lastSaveTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
  private val saveDebounceMs = 350L

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onContentChange(event: com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent) {
    val file = event.changedFile
    if (!(file.toString().endsWith(".kt") || file.toString().endsWith(".kts"))) return

    val uri = file.toUri().toString()

    try {
      val content = event.newText
      val currentTime = System.currentTimeMillis()
      val lastChange = lastChangeTime[uri] ?: 0L
      val deltaSinceLastChange = currentTime - lastChange

      // Throttle rapid changes
      if (deltaSinceLastChange < changeThrottleMs) {
        return
      }

      lastChangeTime[uri] = currentTime

      if (content != null && event.version > 0) {
        val currentVersion = documentManager.getDocumentVersion(uri)
        if (event.version > currentVersion) {
          documentManager.setDocumentVersion(uri, event.version)
          documentManager.notifyDocumentChange(file, content, event.version)
          // KLS diagnostics are commonly refreshed on didSave. Send it after didChange instead of
          // relying on stale disk content or a no-op save hook.
          val lastSaved = lastSaveTime[uri] ?: 0L
          val deltaSinceLastSave = currentTime - lastSaved
          if (deltaSinceLastSave >= saveDebounceMs) {
            documentManager.notifyDocumentSave(file, content)
            lastSaveTime[uri] = currentTime
          }
        }
      } else {
        KslLogs.debug("Skip Kotlin document change without in-memory content: {}", uri)
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to handle document change", e)
    }
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileOpened(event: com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent) {
    val file = event.openedFile
    if (!(file.toString().endsWith(".kt") || file.toString().endsWith(".kts"))) return

    KslLogs.debug("Document open event for: {}", file)
    val initialText = event.text.ifEmpty { com.tom.rv2ide.projects.FileManager.getDocumentContents(file) }
    documentManager.ensureDocumentOpen(file, initialText, event.version)
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileSelected(event: com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent) {
    val file = event.selectedFile
    if (!(file.toString().endsWith(".kt") || file.toString().endsWith(".kts"))) return
    KslLogs.debug("Document selected event for: {}", file)
    val selectedText = com.tom.rv2ide.projects.FileManager.getDocumentContents(file)
    documentManager.ensureDocumentOpen(file, selectedText)
    documentManager.notifyDocumentSave(file, selectedText)
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileSaved(event: com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent) {
    val file = event.savedFile
    if (!(file.toString().endsWith(".kt") || file.toString().endsWith(".kts"))) return

    documentManager.notifyDocumentSave(file)
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileClosed(event: com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent) {
    val file = event.closedFile
    if (!(file.toString().endsWith(".kt") || file.toString().endsWith(".kts"))) return

    KslLogs.debug("Document close event for: {}", file)
    documentManager.closeDocument(file)
  }
}
