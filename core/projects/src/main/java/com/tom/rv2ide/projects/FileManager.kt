/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.projects

import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.progress.ProgressManager
import com.tom.rv2ide.projects.models.ActiveDocument
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.projects.models.OneHopDocumentEdit
import java.io.BufferedReader
import java.io.InputStream
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

/**
 * Manages active documents.
 *
 * @author Akash Yadav
 */
object FileManager {

  private val log = LoggerFactory.getLogger(FileManager::class.java)
  private val activeDocuments = ConcurrentHashMap<Path, ActiveDocument>()

  // This map intentionally retains only one verified edge. Analysis must fall back to full work
  // whenever it cannot prove that its requested target is exactly this edge's target snapshot.
  private val latestOneHopEdits = ConcurrentHashMap<Path, OneHopDocumentEdit>()

  fun isActive(uri: URI): Boolean {
    return isActive(Paths.get(uri))
  }

  fun isActive(file: Path): Boolean {
    return this.activeDocuments.containsKey(file.normalize())
  }

  fun getActiveDocument(file: Path): ActiveDocument? {
    return this.activeDocuments[file.normalize()]
  }

  fun getActiveDocumentSnapshot(file: Path): ActiveDocumentSnapshot? {
    return getActiveDocument(file)?.snapshot()
  }

  /**
   * Returns the only verified base-to-target edit retained for this file.
   *
   * Callers must additionally compare both identities with their own frozen request snapshots;
   * this method deliberately does not synthesize history after a lifecycle break or opaque edit.
   */
  fun getLatestOneHopDocumentEdit(file: Path): OneHopDocumentEdit? {
    return latestOneHopEdits[file.normalize()]
  }

  fun getActiveDocumentSnapshots(files: Collection<Path>): Map<Path, ActiveDocumentSnapshot> {
    return files.mapNotNull { file ->
      getActiveDocumentSnapshot(file)?.let { snapshot -> snapshot.file.normalize() to snapshot }
    }.toMap()
  }

  fun getActiveDocumentCount(): Int {
    return this.activeDocuments.size
  }

  /** Returns a stable snapshot of active paths without exposing mutable document state. */
  fun getActiveDocumentFiles(): Set<Path> {
    return this.activeDocuments.keys.toSet()
  }

  fun getDocumentContents(file: Path): String {
    val document = getActiveDocumentSnapshot(file)
    if (document != null) {
      return document.content
    }

    return getFileContents(file)
  }

  fun getLastModified(file: Path): Instant {
    val document = getActiveDocumentSnapshot(file)
    if (document != null) {
      return document.modified
    }

    return getLastModifiedFromDisk(file)
  }

  fun getReader(file: Path): BufferedReader {
    val document = getActiveDocument(file)
    if (document != null) {
      return document.reader()
    }

    return createFileReader(file)
  }

  fun getInputStream(file: Path): InputStream {
    val document = getActiveDocument(file)
    if (document != null) {
      return document.inputStream()
    }

    return createFileInputStream(file)
  }
  fun onDocumentOpen(event: DocumentOpenEvent) {
    val file = event.openedFile.normalize()
    latestOneHopEdits.remove(file)
    activeDocuments[file] = createDocument(event)
  }

  fun onDocumentContentChange(event: DocumentChangeEvent) {
    val file = event.changedFile.normalize()
    val document = activeDocuments[file]

    if (document == null) {
      // A missing open event means there is no trustworthy base snapshot to link to this target.
      latestOneHopEdits.remove(file)
      activeDocuments[file] = createDocument(event)
      log.warn("Document change event received before open event for file {}", event.changedFile)
      return
    }

    // Event dispatch normally serializes edits. Keep base capture, edge creation, and target update
    // under the document lock for direct callers as well; consumers still compare identities because
    // the edit map and active-document map are separate concurrent structures.
    synchronized(document) {
      val base = document.snapshot()
      val targetContent = event.newText ?: base.content
      val target =
          ActiveDocumentSnapshot(file, event.version, Instant.now(), targetContent, event.revision)
      val verifiedEdit =
          verifiedOneHopEdit(
              base,
              target,
              event.changeType,
              event.changedText,
              event.changeRange,
          )
      if (verifiedEdit != null) {
        latestOneHopEdits[file] = verifiedEdit
      } else {
        // Opaque replacement, non-continuous version, or malformed text must use the stable full path.
        latestOneHopEdits.remove(file)
      }
      document.update(
          version = target.version,
          modified = target.modified,
          content = target.content,
          revision = target.revision,
      )
    }
  }

  fun onDocumentClose(event: DocumentCloseEvent) {
    val file = event.closedFile.normalize()
    latestOneHopEdits.remove(file)
    activeDocuments.remove(file)
  }

  fun onFileRenamed(event: FileRenameEvent) {
    val oldPath = event.file.toPath().normalize()
    latestOneHopEdits.remove(oldPath)
    latestOneHopEdits.remove(event.newFile.toPath().normalize())
    val document = activeDocuments.remove(oldPath)
    if (document != null) {
      val snapshot = document.snapshot()
      val newPath = event.newFile.toPath().normalize()
      activeDocuments[newPath] =
          ActiveDocument(
              file = newPath,
              version = snapshot.version,
              modified = Instant.now(),
              content = snapshot.content,
              // A rename changes the lookup key, not the document text identity.
              revision = snapshot.revision,
          )
    }
  }

  fun onFileDeleted(event: FileDeletionEvent) {
    // If the file was an active document, remove both its snapshot and its only edit edge.
    val file = event.file.toPath().normalize()
    latestOneHopEdits.remove(file)
    activeDocuments.remove(file)
  }

  // A range is required for edits around line separators: prefix/suffix diffing can assign an adjacent LF to the wrong side of a multiline edit.
  private data class TextDiff(
      val start: Int,
      val baseEnd: Int,
      val targetEnd: Int,
      val removed: String,
      val replacement: String,
  )

  private fun documentTextDiff(baseText: String, targetText: String): TextDiff {
    var start = 0
    val commonLength = minOf(baseText.length, targetText.length)
    while (start < commonLength && baseText[start] == targetText[start]) {
      start++
    }

    var baseEnd = baseText.length
    var targetEnd = targetText.length
    while (
        baseEnd > start &&
            targetEnd > start &&
            baseText[baseEnd - 1] == targetText[targetEnd - 1]
    ) {
      baseEnd--
      targetEnd--
    }

    return TextDiff(
        start = start,
        baseEnd = baseEnd,
        targetEnd = targetEnd,
        removed = baseText.substring(start, baseEnd),
        replacement = targetText.substring(start, targetEnd),
    )
  }

  private fun verifiedOneHopEdit(
      base: ActiveDocumentSnapshot,
      target: ActiveDocumentSnapshot,
      changeType: com.tom.rv2ide.eventbus.events.editor.ChangeType,
      changedText: String,
      changeRange: com.tom.rv2ide.models.Range,
  ): OneHopDocumentEdit? {
    if (
        changeType == com.tom.rv2ide.eventbus.events.editor.ChangeType.NEW_TEXT ||
            target.version != base.version + 1 ||
            target.revision <= base.revision
    ) {
      return null
    }

    // Line-separator edits can have the same LF on both sides of the changed region; prefix/suffix diffing may assign that LF to the wrong side, so use the editor range first.
    val rangeEdit = verifiedOneHopEditFromRange(base, target, changeType, changedText, changeRange)
    if (rangeEdit != null) {
      return rangeEdit
    }
    if (!hasUsableChangeRange(changeRange)) {
      return verifiedOneHopEditFromTextDiff(base, target, changeType, changedText)
    }
    return null
  }

  private fun verifiedOneHopEditFromRange(
      base: ActiveDocumentSnapshot,
      target: ActiveDocumentSnapshot,
      changeType: com.tom.rv2ide.eventbus.events.editor.ChangeType,
      changedText: String,
      changeRange: com.tom.rv2ide.models.Range,
  ): OneHopDocumentEdit? {
    if (!hasUsableChangeRange(changeRange)) return null
    val start = changeRange.start.index
    val end = changeRange.end.index
    if (start < 0 || end < start) return null

    val baseText = base.content
    val targetText = target.content
    val baseStart: Int
    val baseEnd: Int
    val targetStart: Int
    val targetEnd: Int
    when (changeType) {
      com.tom.rv2ide.eventbus.events.editor.ChangeType.INSERT -> {
        baseStart = start
        baseEnd = start
        targetStart = start
        targetEnd = end
      }
      com.tom.rv2ide.eventbus.events.editor.ChangeType.DELETE -> {
        baseStart = start
        baseEnd = end
        targetStart = start
        targetEnd = start
      }
      com.tom.rv2ide.eventbus.events.editor.ChangeType.NEW_TEXT -> return null
    }
    if (
        baseStart > baseText.length ||
            baseEnd > baseText.length ||
            targetStart > targetText.length ||
            targetEnd > targetText.length
    ) {
      return null
    }

    val removed = baseText.substring(baseStart, baseEnd)
    val replacement = targetText.substring(targetStart, targetEnd)
    val payloadMatches =
        when (changeType) {
          com.tom.rv2ide.eventbus.events.editor.ChangeType.INSERT ->
              replacement == changedText && replacement.isNotEmpty() && removed.isEmpty()
          com.tom.rv2ide.eventbus.events.editor.ChangeType.DELETE ->
              removed == changedText && removed.isNotEmpty() && replacement.isEmpty()
          com.tom.rv2ide.eventbus.events.editor.ChangeType.NEW_TEXT -> false
        }
    if (!payloadMatches) return null

    val expectedTarget =
        baseText.substring(0, baseStart) + replacement + baseText.substring(baseEnd)
    if (expectedTarget != targetText) return null

    val kind =
        when {
          removed.isEmpty() && replacement.isNotEmpty() -> OneHopDocumentEdit.Kind.INSERT
          removed.isNotEmpty() && replacement.isEmpty() -> OneHopDocumentEdit.Kind.DELETE
          else -> return null
        }
    return OneHopDocumentEdit(
        base = DocumentSnapshotIdentity(base.file.normalize(), base.version, base.revision),
        target = DocumentSnapshotIdentity(target.file.normalize(), target.version, target.revision),
        baseStartIndex = baseStart,
        baseEndIndex = baseEnd,
        removedText = removed,
        replacementText = replacement,
        kind = kind,
    )
  }

  private fun verifiedOneHopEditFromTextDiff(
      base: ActiveDocumentSnapshot,
      target: ActiveDocumentSnapshot,
      changeType: com.tom.rv2ide.eventbus.events.editor.ChangeType,
      changedText: String,
  ): OneHopDocumentEdit? {
    val diff = documentTextDiff(base.content, target.content)
    val removed = diff.removed
    val replacement = diff.replacement
    val kind =
        when {
          diff.start == diff.baseEnd && replacement.isNotEmpty() -> OneHopDocumentEdit.Kind.INSERT
          diff.start != diff.baseEnd && replacement.isEmpty() -> OneHopDocumentEdit.Kind.DELETE
          diff.start != diff.baseEnd && replacement.isNotEmpty() -> OneHopDocumentEdit.Kind.REPLACE
          else -> return null
        }
    val payloadMatches =
        when (changeType) {
          com.tom.rv2ide.eventbus.events.editor.ChangeType.INSERT ->
              replacement == changedText && replacement.isNotEmpty()
          com.tom.rv2ide.eventbus.events.editor.ChangeType.DELETE ->
              removed == changedText && removed.isNotEmpty() && replacement.isEmpty()
          com.tom.rv2ide.eventbus.events.editor.ChangeType.NEW_TEXT -> false
        }
    if (!payloadMatches) return null
    return OneHopDocumentEdit(
        base = DocumentSnapshotIdentity(base.file.normalize(), base.version, base.revision),
        target = DocumentSnapshotIdentity(target.file.normalize(), target.version, target.revision),
        baseStartIndex = diff.start,
        baseEndIndex = diff.baseEnd,
        removedText = removed,
        replacementText = replacement,
        kind = kind,
    )
  }

  private fun hasUsableChangeRange(range: com.tom.rv2ide.models.Range): Boolean =
      range.start.index >= 0 && range.end.index >= range.start.index

  private fun createDocument(event: DocumentOpenEvent): ActiveDocument {
    val initialContent = readOpenEventText(event)
    // Keep the event coherent for subscribers posted after FileManager.onDocumentOpen(). Large-file
    // editor flows may intentionally omit the eager payload, but downstream language services still
    // expect DocumentOpenEvent.text to contain the initial snapshot when available.
    if (event.text.isEmpty() && initialContent.isNotEmpty()) {
      event.text = initialContent
    }
    return ActiveDocument(
        file = event.openedFile.normalize(),
        version = event.version,
        modified = Instant.now(),
        content = initialContent,
        revision = event.revision,
    )
  }

  private fun readOpenEventText(event: DocumentOpenEvent): String {
    if (event.text.isNotEmpty()) {
      return event.text
    }

    return try {
      if (Files.exists(event.openedFile) && Files.size(event.openedFile) > 0L) {
        getFileContents(event.openedFile)
      } else {
        ""
      }
    } catch (error: Exception) {
      log.warn("Unable to recover opened document contents for {}", event.openedFile, error)
      ""
    }
  }

  private fun createDocument(event: DocumentChangeEvent): ActiveDocument {
    val initialContent = event.newText ?: getFileContents(event.changedFile)
    return ActiveDocument(
        file = event.changedFile.normalize(),
        version = event.version,
        modified = Instant.now(),
        content = initialContent,
        revision = event.revision,
    )
  }

  private fun createFileReader(file: Path): BufferedReader {
    return try {
      Files.newBufferedReader(file)
    } catch (noFile: java.nio.file.NoSuchFileException) {
      log.warn("No such file", noFile)
      "".reader().buffered()
    } catch (cancelled: CancellationException) {
      "".reader().buffered()
    }
  }

  private fun createFileInputStream(file: Path): InputStream {
    return try {
      Files.newInputStream(file)
    } catch (noFile: java.nio.file.NoSuchFileException) {
      log.warn("No such file", noFile)
      "".byteInputStream()
    } catch (cancelled: CancellationException) {
      "".byteInputStream()
    }
  }

  private fun getLastModifiedFromDisk(file: Path): Instant {
    return Files.getLastModifiedTime(file).toInstant()
  }

  private fun getFileContents(file: Path): String {
    return try {
      ProgressManager.abortIfCancelled()
      FileUtils.readFileToString(file.toFile(), Charset.defaultCharset())
    } catch (noFile: java.nio.file.NoSuchFileException) {
      log.warn("No such file", noFile)
      ""
    } catch (cancelled: CancellationException) {
      ""
    }
  }
}
