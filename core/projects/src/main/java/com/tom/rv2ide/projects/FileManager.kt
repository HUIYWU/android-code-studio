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
      val verifiedEdit = verifiedOneHopEdit(base, target, event.changeType, event.changedText)
      val failureReason =
          if (verifiedEdit == null) {
            oneHopFailureReason(base, target, event.changeType, event.changedText)
          } else {
            null
          }
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
      val latestEdit = latestOneHopEdits[file]
      val textDiff = documentTextDiff(base.content, target.content)
      log.info(
          "Document one-hop trace: path={}, result={}, reason={}, changeType={}, changedTextLength={}, changedTextShape={}, changedTextBreaks={}, changedTextSignature={}, removedLength={}, removedShape={}, removedBreaks={}, removedSignature={}, replacementLength={}, replacementShape={}, replacementBreaks={}, replacementSignature={}, diffStart={}, diffBaseEnd={}, diffTargetEnd={}, baseVersion={}, targetVersion={}, baseRevision={}, targetRevision={}, baseTextLength={}, targetTextLength={}, latestEditBaseVersion={}, latestEditTargetVersion={}, latestEditBaseRevision={}, latestEditTargetRevision={}",
          file,
          if (verifiedEdit != null) "accepted" else "rejected",
          failureReason ?: "none",
          event.changeType,
          event.changedText.length,
          textShape(event.changedText),
          lineBreakSummary(event.changedText),
          textSignature(event.changedText),
          textDiff.removed.length,
          textShape(textDiff.removed),
          lineBreakSummary(textDiff.removed),
          textSignature(textDiff.removed),
          textDiff.replacement.length,
          textShape(textDiff.replacement),
          lineBreakSummary(textDiff.replacement),
          textSignature(textDiff.replacement),
          textDiff.start,
          textDiff.baseEnd,
          textDiff.targetEnd,
          base.version,
          target.version,
          base.revision,
          target.revision,
          base.content.length,
          target.content.length,
          latestEdit?.base?.version,
          latestEdit?.target?.version,
          latestEdit?.base?.revision,
          latestEdit?.target?.revision,
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

  private fun oneHopFailureReason(
      base: ActiveDocumentSnapshot,
      target: ActiveDocumentSnapshot,
      changeType: com.tom.rv2ide.eventbus.events.editor.ChangeType,
      changedText: String,
  ): String {
    if (changeType == com.tom.rv2ide.eventbus.events.editor.ChangeType.NEW_TEXT) {
      return "new-text"
    }
    if (target.version != base.version + 1) {
      return "version-not-contiguous"
    }
    if (target.revision <= base.revision) {
      return "revision-not-increasing"
    }

    val baseText = base.content
    val targetText = target.content
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
    val replacement = targetText.substring(start, targetEnd)
    val removed = baseText.substring(start, baseEnd)
    if (start == baseEnd && replacement.isEmpty()) {
      return "no-text-difference"
    }
    return when (changeType) {
      com.tom.rv2ide.eventbus.events.editor.ChangeType.INSERT ->
          if (replacement == changedText && replacement.isNotEmpty()) "unknown" else "insert-payload-mismatch"
      com.tom.rv2ide.eventbus.events.editor.ChangeType.DELETE ->
          if (removed == changedText && removed.isNotEmpty() && replacement.isEmpty()) "unknown"
          else "delete-payload-mismatch"
      com.tom.rv2ide.eventbus.events.editor.ChangeType.NEW_TEXT -> "new-text"
    }
  }

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

  private fun lineBreakSummary(text: String): String {
    var crlf = 0
    var lf = 0
    var cr = 0
    var index = 0
    while (index < text.length) {
      when (text[index]) {
        '\r' -> {
          if (index + 1 < text.length && text[index + 1] == '\n') {
            crlf++
            index++
          } else {
            cr++
          }
        }
        '\n' -> lf++
      }
      index++
    }
    return "crlf=$crlf,lf=$lf,cr=$cr"
  }

  private fun textSignature(text: String): String {
    if (text.isEmpty()) return "empty"
    val signature = mutableListOf<String>()
    var index = 0
    while (index < text.length && signature.size < 32) {
      val current = text[index]
      val category =
          when {
            current == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> {
              index++
              "CRLF"
            }
            current == '\n' -> "LF"
            current == '\r' -> "CR"
            current == ' ' -> "SP"
            current == '\t' -> "TAB"
            current.isWhitespace() -> "WS"
            else -> "OTHER"
          }
      signature += category
      index++
    }
    if (index < text.length) signature += "..."
    return signature.joinToString(",")
  }

  private fun textShape(text: String): String {
    if (text.isEmpty()) return "empty"
    return when {
      text.all { it == ' ' } -> "spaces"
      text.all { it == '\t' } -> "tabs"
      text.all { it == ' ' || it == '\t' } -> "horizontal-whitespace"
      text.contains('\n') || text.contains('\r') -> "contains-line-break"
      else -> "text"
    }
  }

  private fun verifiedOneHopEdit(
      base: ActiveDocumentSnapshot,
      target: ActiveDocumentSnapshot,
      changeType: com.tom.rv2ide.eventbus.events.editor.ChangeType,
      changedText: String,
  ): OneHopDocumentEdit? {
    if (
        changeType == com.tom.rv2ide.eventbus.events.editor.ChangeType.NEW_TEXT ||
            target.version != base.version + 1 ||
            target.revision <= base.revision
    ) {
      return null
    }

    val baseText = base.content
    val targetText = target.content
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
    val replacement = targetText.substring(start, targetEnd)
    val removed = baseText.substring(start, baseEnd)
    val kind =
        when {
          start == baseEnd && replacement.isNotEmpty() -> OneHopDocumentEdit.Kind.INSERT
          start != baseEnd && replacement.isEmpty() -> OneHopDocumentEdit.Kind.DELETE
          start != baseEnd && replacement.isNotEmpty() -> OneHopDocumentEdit.Kind.REPLACE
          else -> return null
        }
    // ContentChangeEvent reports inserted text for INSERT and removed text for DELETE. Do not
    // infer replacement semantics from the action alone; accept a replacement only if the full
    // base/target comparison and the action-specific payload agree.
    val payloadMatches =
        when (changeType) {
          com.tom.rv2ide.eventbus.events.editor.ChangeType.INSERT ->
              replacement == changedText && replacement.isNotEmpty()
          com.tom.rv2ide.eventbus.events.editor.ChangeType.DELETE ->
              removed == changedText && removed.isNotEmpty() && replacement.isEmpty()
          com.tom.rv2ide.eventbus.events.editor.ChangeType.NEW_TEXT -> false
        }
    if (!payloadMatches) {
      return null
    }
    return OneHopDocumentEdit(
        base = DocumentSnapshotIdentity(base.file.normalize(), base.version, base.revision),
        target = DocumentSnapshotIdentity(target.file.normalize(), target.version, target.revision),
        baseStartIndex = start,
        baseEndIndex = baseEnd,
        removedText = removed,
        replacementText = replacement,
        kind = kind,
    )
  }

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
