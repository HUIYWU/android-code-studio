/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.projects

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.eventbus.events.editor.ChangeType
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.models.ActiveDocument
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Test

class FileManagerTest {

  @Test
  fun documentSnapshotsRemainCoherentDuringConcurrentUpdates() {
    val document =
        ActiveDocument(
            file = Files.createTempFile("active-document", ".xml"),
            version = 0,
            modified = Instant.EPOCH,
            content = "content-0",
            revision = 0L,
        )
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val writer =
          executor.submit {
            start.await()
            repeat(1_000) { index ->
              val value = index + 1
              document.update(
                  version = value,
                  modified = Instant.ofEpochMilli(value.toLong()),
                  content = "content-$value",
                  revision = value.toLong(),
              )
            }
          }
      val reader =
          executor.submit {
            start.await()
            repeat(1_000) {
              val snapshot = document.snapshot()
              assertThat(snapshot.content).isEqualTo("content-${snapshot.version}")
              assertThat(snapshot.revision).isEqualTo(snapshot.version.toLong())
              assertThat(snapshot.modified)
                  .isEqualTo(Instant.ofEpochMilli(snapshot.version.toLong()))
            }
          }
      start.countDown()
      writer.get()
      reader.get()
    } finally {
      executor.shutdownNow()
      Files.deleteIfExists(document.file)
    }
  }

  @Test
  fun revisionsAdvanceAcrossOpenChangeCloseAndReopen() {
    val file = Files.createTempFile("file-manager", ".xml")
    try {
      FileManager.onDocumentOpen(DocumentOpenEvent(file, "open", 1))
      val opened = checkNotNull(FileManager.getActiveDocumentSnapshot(file))

      FileManager.onDocumentContentChange(change(file, "changed", 2))
      val changed = checkNotNull(FileManager.getActiveDocumentSnapshot(file))

      FileManager.onDocumentClose(DocumentCloseEvent(file))
      assertThat(FileManager.getActiveDocumentSnapshot(file)).isNull()

      FileManager.onDocumentOpen(DocumentOpenEvent(file, "reopened", 1))
      val reopened = checkNotNull(FileManager.getActiveDocumentSnapshot(file))

      assertThat(opened.content).isEqualTo("open")
      assertThat(changed.content).isEqualTo("changed")
      assertThat(changed.version).isEqualTo(2)
      assertThat(changed.revision).isGreaterThan(opened.revision)
      assertThat(reopened.version).isEqualTo(1)
      assertThat(reopened.revision).isGreaterThan(changed.revision)
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun batchSnapshotsUseNormalizedPathsAndOnlyActiveDocuments() {
    val active = Files.createTempFile("file-manager-active", ".xml")
    val inactive = Files.createTempFile("file-manager-inactive", ".xml")
    try {
      FileManager.onDocumentOpen(DocumentOpenEvent(active, "active", 1))

      val snapshots =
          FileManager.getActiveDocumentSnapshots(
              listOf(active.parent.resolve(".").resolve(active.fileName), inactive)
          )

      assertThat(snapshots.keys).containsExactly(active.normalize())
      assertThat(snapshots.getValue(active.normalize()).content).isEqualTo("active")
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(active))
      Files.deleteIfExists(active)
      Files.deleteIfExists(inactive)
    }
  }

  @Test
  fun verifiedOneHopEditCapturesInsertionDeletionAndReplacement() {
    val file = Files.createTempFile("file-manager-one-hop", ".java")
    try {
      FileManager.onDocumentOpen(DocumentOpenEvent(file, "abc", 1))

      FileManager.onDocumentContentChange(change(file, "aXbc", "X", 2, ChangeType.INSERT))
      val insertion = checkNotNull(FileManager.getLatestOneHopDocumentEdit(file))
      assertThat(insertion.kind).isEqualTo(com.tom.rv2ide.projects.models.OneHopDocumentEdit.Kind.INSERT)
      assertThat(insertion.baseStartIndex).isEqualTo(1)
      assertThat(insertion.baseEndIndex).isEqualTo(1)
      assertThat(insertion.replacementText).isEqualTo("X")
      assertThat(insertion.base.version).isEqualTo(1)
      assertThat(insertion.target.version).isEqualTo(2)

      FileManager.onDocumentContentChange(change(file, "aXc", "b", 3, ChangeType.DELETE))
      val deletion = checkNotNull(FileManager.getLatestOneHopDocumentEdit(file))
      assertThat(deletion.kind).isEqualTo(com.tom.rv2ide.projects.models.OneHopDocumentEdit.Kind.DELETE)
      assertThat(deletion.baseStartIndex).isEqualTo(2)
      assertThat(deletion.baseEndIndex).isEqualTo(3)
      assertThat(deletion.replacementText).isEmpty()

      FileManager.onDocumentContentChange(change(file, "aYc", "Y", 4, ChangeType.INSERT))
      val replacement = checkNotNull(FileManager.getLatestOneHopDocumentEdit(file))
      assertThat(replacement.kind).isEqualTo(com.tom.rv2ide.projects.models.OneHopDocumentEdit.Kind.REPLACE)
      assertThat(replacement.baseStartIndex).isEqualTo(1)
      assertThat(replacement.baseEndIndex).isEqualTo(2)
      assertThat(replacement.replacementText).isEqualTo("Y")
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun verifiedOneHopEditUsesUtf16CoordinatesForSurrogatePairsCrLfAndMultilineReplacement() {
    val file = Files.createTempFile("file-manager-one-hop-utf16", ".java")
    try {
      val initial = "😀\r\n// note\r\nold\r\n"
      FileManager.onDocumentOpen(DocumentOpenEvent(file, initial, 1))

      val withInsertion = "😀X\r\n// note\r\nold\r\n"
      FileManager.onDocumentContentChange(change(file, withInsertion, "X", 2, ChangeType.INSERT))
      val insertion = checkNotNull(FileManager.getLatestOneHopDocumentEdit(file))
      assertThat(insertion.baseStartIndex).isEqualTo(2)
      assertThat(insertion.baseEndIndex).isEqualTo(2)
      assertThat(insertion.removedText).isEmpty()
      assertThat(insertion.replacementText).isEqualTo("X")

      val withoutCommentLineEnding = "😀X\r\n// noteold\r\n"
      FileManager.onDocumentContentChange(
          change(file, withoutCommentLineEnding, "\r\n", 3, ChangeType.DELETE))
      val crLfDeletion = checkNotNull(FileManager.getLatestOneHopDocumentEdit(file))
      assertThat(crLfDeletion.baseStartIndex).isEqualTo("😀X\r\n// note".length)
      assertThat(crLfDeletion.baseEndIndex).isEqualTo("😀X\r\n// note\r\n".length)
      assertThat(crLfDeletion.removedText).isEqualTo("\r\n")
      assertThat(crLfDeletion.replacementText).isEmpty()

      val replacedMultiline = "😀X\r\n// notefirst\nnext\r\n"
      FileManager.onDocumentContentChange(
          change(file, replacedMultiline, "first\nnext", 4, ChangeType.INSERT))
      val replacement = checkNotNull(FileManager.getLatestOneHopDocumentEdit(file))
      assertThat(replacement.kind)
          .isEqualTo(com.tom.rv2ide.projects.models.OneHopDocumentEdit.Kind.REPLACE)
      assertThat(replacement.baseStartIndex).isEqualTo("😀X\r\n// note".length)
      assertThat(replacement.baseEndIndex).isEqualTo("😀X\r\n// noteold".length)
      assertThat(replacement.removedText).isEqualTo("old")
      assertThat(replacement.replacementText).isEqualTo("first\nnext")
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun rangedNewlineInsertionUsesEventPayloadOrder() {
    val file = Files.createTempFile("file-manager-ranged-insert", ".kt")
    try {
      val initial = "fun f() {\n    \n}\n"
      val target = "fun f() {\n    \n    \n}\n"
      FileManager.onDocumentOpen(DocumentOpenEvent(file, initial, 0))
      val start = initial.indexOf("    \n") + 4
      val end = start + 5
      FileManager.onDocumentContentChange(
          change(
              file,
              target,
              "\n    ",
              1,
              ChangeType.INSERT,
              Range(Position(1, 4, start), Position(2, 4, end)),
          )
      )

      val edit = checkNotNull(FileManager.getLatestOneHopDocumentEdit(file))
      assertThat(edit.kind).isEqualTo(com.tom.rv2ide.projects.models.OneHopDocumentEdit.Kind.INSERT)
      assertThat(edit.baseStartIndex).isEqualTo(start)
      assertThat(edit.baseEndIndex).isEqualTo(start)
      assertThat(edit.removedText).isEmpty()
      assertThat(edit.replacementText).isEqualTo("\n    ")
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun rangedEmptyLineDeletionUsesEventPayloadOrder() {
    val file = Files.createTempFile("file-manager-ranged-delete", ".kt")
    try {
      val initial = "fun f() {\n    \n    \n}\n"
      val target = "fun f() {\n    \n}\n"
      FileManager.onDocumentOpen(DocumentOpenEvent(file, initial, 0))
      val start = initial.indexOf("    \n") + 4
      val end = start + 5
      FileManager.onDocumentContentChange(
          change(
              file,
              target,
              "\n    ",
              1,
              ChangeType.DELETE,
              Range(Position(1, 4, start), Position(2, 4, end)),
          )
      )

      val edit = checkNotNull(FileManager.getLatestOneHopDocumentEdit(file))
      assertThat(edit.kind).isEqualTo(com.tom.rv2ide.projects.models.OneHopDocumentEdit.Kind.DELETE)
      assertThat(edit.baseStartIndex).isEqualTo(start)
      assertThat(edit.baseEndIndex).isEqualTo(end)
      assertThat(edit.removedText).isEqualTo("\n    ")
      assertThat(edit.replacementText).isEmpty()
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun rangedPayloadMismatchClearsOneHopEdit() {
    val file = Files.createTempFile("file-manager-ranged-mismatch", ".kt")
    try {
      val initial = "abc"
      val target = "aXbc"
      FileManager.onDocumentOpen(DocumentOpenEvent(file, initial, 0))
      FileManager.onDocumentContentChange(
          change(
              file,
              target,
              "Y",
              1,
              ChangeType.INSERT,
              Range(Position(0, 1, 1), Position(0, 2, 2)),
          )
      )
      assertThat(FileManager.getLatestOneHopDocumentEdit(file)).isNull()
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun opaqueOrLifecycleBreakingChangesClearOneHopEdit() {
    val file = Files.createTempFile("file-manager-one-hop-clear", ".java")
    try {
      FileManager.onDocumentOpen(DocumentOpenEvent(file, "abc", 1))
      FileManager.onDocumentContentChange(change(file, "aXbc", "X", 2, ChangeType.INSERT))
      assertThat(FileManager.getLatestOneHopDocumentEdit(file)).isNotNull()

      FileManager.onDocumentContentChange(change(file, "replacement", "replacement", 3, ChangeType.NEW_TEXT))
      assertThat(FileManager.getLatestOneHopDocumentEdit(file)).isNull()

      FileManager.onDocumentContentChange(change(file, "replacement!", "!", 4, ChangeType.INSERT))
      assertThat(FileManager.getLatestOneHopDocumentEdit(file)).isNotNull()

      FileManager.onDocumentContentChange(change(file, "replacement!?", "?", 6, ChangeType.INSERT))
      assertThat(FileManager.getLatestOneHopDocumentEdit(file)).isNull()
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      assertThat(FileManager.getLatestOneHopDocumentEdit(file)).isNull()

      FileManager.onDocumentOpen(DocumentOpenEvent(file, "reopened", 1))
      assertThat(FileManager.getLatestOneHopDocumentEdit(file)).isNull()
    } finally {
      FileManager.onDocumentClose(DocumentCloseEvent(file))
      Files.deleteIfExists(file)
    }
  }

  private fun change(
      file: java.nio.file.Path,
      targetText: String,
      changedText: String,
      version: Int,
      type: ChangeType,
  ): DocumentChangeEvent {
    return change(file, targetText, changedText, version, type, Range.NONE)
  }

  private fun change(
      file: java.nio.file.Path,
      targetText: String,
      changedText: String,
      version: Int,
      type: ChangeType,
      range: Range,
  ): DocumentChangeEvent {
    return DocumentChangeEvent(
        file,
        changedText,
        targetText,
        version,
        type,
        0,
        range,
    )
  }

  private fun change(file: java.nio.file.Path, text: String, version: Int): DocumentChangeEvent {
    return DocumentChangeEvent(
        file,
        text,
        text,
        version,
        ChangeType.NEW_TEXT,
        0,
        Range.NONE,
    )
  }
}
