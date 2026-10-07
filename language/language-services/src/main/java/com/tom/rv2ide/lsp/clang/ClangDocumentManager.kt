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

import android.os.SystemClock
import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ClangDocumentManager(
    private val processManager: ClangServerProcessManager,
    private val isServerInitialized: () -> Boolean,
) {

  data class ChangePerfSnapshot(
      val version: Int,
      val sentAtMs: Long,
      val textLength: Int,
      val sendCostMs: Long,
      val changeIntervalMs: Long,
      val threadName: String,
  )

  data class PendingDidChange(
      val file: Path,
      val uri: String,
      val text: String,
      val version: Int,
      val scheduledAtMs: Long,
      val changeIntervalMs: Long,
      val threadName: String,
  )

  data class PendingOpenDocument(
      val file: Path,
      val uri: String,
      val text: String,
      val queuedAtMs: Long,
      val threadName: String,
  )

  companion object {
    private const val DID_CHANGE_DEBOUNCE_MS = 300L
  }

  private val openedDocuments = ConcurrentHashMap.newKeySet<String>()
  private val documentVersions = ConcurrentHashMap<String, Int>()
  private val latestChangePerf = ConcurrentHashMap<String, ChangePerfSnapshot>()
  private val pendingDidChangeJobs = ConcurrentHashMap<String, Job>()
  private val pendingDidChanges = ConcurrentHashMap<String, PendingDidChange>()
  private val pendingOpenDocuments = ConcurrentHashMap<String, PendingOpenDocument>()
  private val debounceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  fun ensureDocumentOpen(file: Path, content: String? = null) {
    val uri = file.toUri().toString()

    ClangLogs.debug(
        "ensureDocumentOpen called: uri={}, hasContent={}, alreadyOpen={}, serverInitialized={}",
        uri,
        content != null,
        openedDocuments.contains(uri),
        isServerInitialized(),
    )

    if (openedDocuments.contains(uri)) {
      ClangLogs.debug("Document already open, skipping didOpen: {}", uri)
      return
    }

    val text =
        content
            ?: try {
              file.toFile().readText()
            } catch (e: Exception) {
              ClangLogs.error("Failed to read file: {}", file, e)
              return
            }

    if (!isServerInitialized()) {
      pendingOpenDocuments[uri] =
          PendingOpenDocument(
              file = file,
              uri = uri,
              text = text,
              queuedAtMs = SystemClock.elapsedRealtime(),
              threadName = Thread.currentThread().name,
          )
      return
    }


    openDocumentNow(file, uri, text)
  }

  fun flushPendingOpens() {
    if (!isServerInitialized()) {
      return
    }

    val pending = pendingOpenDocuments.values.sortedBy { it.queuedAtMs }
    if (pending.isEmpty()) {
      return
    }

    pending.forEach { pendingOpen ->
      if (openedDocuments.contains(pendingOpen.uri)) {
        pendingOpenDocuments.remove(pendingOpen.uri)
        ClangLogs.debug("Skipping pending didOpen because document already open: {}", pendingOpen.uri)
        return@forEach
      }

      val opened = openDocumentNow(pendingOpen.file, pendingOpen.uri, pendingOpen.text)
      if (opened) {
        pendingOpenDocuments.remove(pendingOpen.uri)
      }
    }
  }

  private fun openDocumentNow(file: Path, uri: String, text: String): Boolean {
    val languageId =
        when {
          file.toString().endsWith(".c") -> "c"
          file.toString().endsWith(".cpp") ||
              file.toString().endsWith(".cc") ||
              file.toString().endsWith(".cxx") -> "cpp"
          file.toString().endsWith(".h") || file.toString().endsWith(".hpp") -> "cpp"
          else -> "c"
        }

    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                addProperty("languageId", languageId)
                addProperty("version", 1)
                addProperty("text", text)
              },
          )
        }

    return try {
      processManager.sendNotificationOrThrow("textDocument/didOpen", params)
      openedDocuments.add(uri)
      documentVersions[uri] = 1
      pendingOpenDocuments.remove(uri)
      true
    } catch (e: Exception) {
      ClangLogs.warn(
          "didOpen failed, document remains unopened: uri={}, textLength={}, thread={}",
          uri,
          text.length,
          Thread.currentThread().name,
          e,
      )
      false
    }
  }

  fun closeDocument(file: Path) {
    val uri = file.toUri().toString()

    ClangLogs.debug("closeDocument called: uri={}, wasOpen={}", uri, openedDocuments.contains(uri))
    cancelPendingDidChange(uri)
    pendingOpenDocuments.remove(uri)

    if (openedDocuments.remove(uri)) {
      documentVersions.remove(uri)
      latestChangePerf.remove(uri)

      val params =
          JsonObject().apply { add("textDocument", JsonObject().apply { addProperty("uri", uri) }) }
      processManager.sendNotification("textDocument/didClose", params)
      ClangLogs.debug("Document closed: {}", uri)
    } else {
      ClangLogs.debug("Document not tracked, skipping didClose: {}", uri)
    }
  }

  fun clear() {
    pendingDidChangeJobs.keys.toList().forEach { uri -> cancelPendingDidChange(uri) }
    pendingOpenDocuments.clear()
    openedDocuments.clear()
    documentVersions.clear()
    latestChangePerf.clear()
  }

  fun closeAllDocuments() {
    openedDocuments.toList().forEach { uri ->
      try {
        closeDocument(Path.of(java.net.URI(uri)))
      } catch (e: Exception) {
        ClangLogs.warn("Failed to close tracked document: {}", uri, e)
      }
    }
    clear()
  }

  fun syncDocument(file: Path, content: String? = null) {
    val uri = file.toUri().toString()
    val text =
        content
            ?: try {
              file.toFile().readText()
            } catch (e: Exception) {
              ClangLogs.error("Failed to read file for sync: {}", file, e)
              return
            }

    if (!isDocumentOpen(uri)) {
      ensureDocumentOpen(file, text)
      return
    }

    val nextVersion = getDocumentVersion(uri) + 1
    notifyDocumentChange(file, text, nextVersion)
    setDocumentVersion(uri, nextVersion)
  }

  fun notifyDocumentChange(file: Path, newText: String, version: Int) {

    val uri = file.toUri().toString()

    // CRITICAL: If not in our tracking set, clangd definitely doesn't have it
    if (!openedDocuments.contains(uri)) {
      ClangLogs.warn("Document not in tracking set, opening fresh: {}", uri)
      ensureDocumentOpen(file, newText)
      return
    }

    val nowMs = SystemClock.elapsedRealtime()
    val previousPerf = latestChangePerf[uri]
    val changeIntervalMs = if (previousPerf != null) nowMs - previousPerf.sentAtMs else -1L
    val threadName = Thread.currentThread().name
    val previousPending = pendingDidChanges[uri]

    val pendingChange =
        PendingDidChange(
            file = file,
            uri = uri,
            text = newText,
            version = version,
            scheduledAtMs = nowMs,
            changeIntervalMs = changeIntervalMs,
            threadName = threadName,
        )

    pendingDidChanges[uri] = pendingChange

    pendingDidChangeJobs.remove(uri)?.cancel()
    if (previousPending != null) {
      ClangLogs.debug(
          "Coalescing pending didChange: uri={}, droppedVersion={}, replacedByVersion={}",
          uri,
          previousPending.version,
          version,
      )
    }

    val job =
        debounceScope.launch {
          delay(DID_CHANGE_DEBOUNCE_MS)

          val latestPending = pendingDidChanges[uri]
          if (latestPending == null || latestPending.version != version) {
            ClangLogs.debug(
                "Skipping stale debounced didChange: uri={}, scheduledVersion={}, latestVersion={}",
                uri,
                version,
                latestPending?.version,
            )
            return@launch
          }

          if (!openedDocuments.contains(uri)) {
            cancelPendingDidChange(uri)
            return@launch
          }

          sendDidChange(latestPending)
        }

    pendingDidChangeJobs[uri] = job
  }

  private fun sendDidChange(pendingChange: PendingDidChange) {
    val uri = pendingChange.uri
    val textLength = pendingChange.text.length

    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                addProperty("version", pendingChange.version)
              },
          )
          add(
              "contentChanges",
              com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("text", pendingChange.text) })
              },
          )
        }

    try {
      val sendStartMs = SystemClock.elapsedRealtime()
      processManager.sendNotification("textDocument/didChange", params)
      val sendCostMs = SystemClock.elapsedRealtime() - sendStartMs
      val sentAtMs = SystemClock.elapsedRealtime()
      latestChangePerf[uri] =
          ChangePerfSnapshot(
              version = pendingChange.version,
              sentAtMs = sentAtMs,
              textLength = textLength,
              sendCostMs = sendCostMs,
              changeIntervalMs = pendingChange.changeIntervalMs,
              threadName = pendingChange.threadName,
          )
      clearPendingDidChangeIfVersionMatches(uri, pendingChange.version)
    } catch (e: Exception) {
      ClangLogs.error("Failed to send didChange, reopening document", e)
      openedDocuments.remove(uri)
      latestChangePerf.remove(uri)
      clearPendingDidChangeIfVersionMatches(uri, pendingChange.version)
      ensureDocumentOpen(pendingChange.file, pendingChange.text)
    }
  }

  private fun cancelPendingDidChange(uri: String) {
    pendingDidChangeJobs.remove(uri)?.cancel()
    pendingDidChanges.remove(uri)
  }

  private fun clearPendingDidChangeIfVersionMatches(uri: String, version: Int) {
    val latestPending = pendingDidChanges[uri]
    if (latestPending != null && latestPending.version == version) {
      pendingDidChanges.remove(uri)
      pendingDidChangeJobs.remove(uri)
    }
  }

  fun getLatestChangePerf(uri: String): ChangePerfSnapshot? {
    return latestChangePerf[uri]
  }

  fun isDocumentOpen(uri: String): Boolean {
    val isOpen = openedDocuments.contains(uri)
    ClangLogs.debug("isDocumentOpen({}): {}", uri, isOpen)
    return isOpen
  }

  fun getDocumentVersion(uri: String): Int {
    return documentVersions.getOrDefault(uri, 0)
  }

  fun setDocumentVersion(uri: String, version: Int) {
    documentVersions[uri] = version
  }
}
