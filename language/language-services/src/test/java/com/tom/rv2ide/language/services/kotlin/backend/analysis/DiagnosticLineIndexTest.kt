package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.lsp.models.LineIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLineIndexTest {
  @Test
  fun mapsMixedNewlinesAndUtf16Offsets() {
    val text = "a\r\n😀b\rc\n"
    val index = LineIndex.from(text)
    assertEquals(0, index.indexToPosition(0).line)
    assertEquals(1, index.indexToPosition(3).line)
    assertEquals(2, index.indexToPosition(5).column)
    assertEquals(2, index.indexToPosition(7).line)
    assertEquals(3, index.indexToPosition(text.length).line)
    assertEquals(0, index.indexToPosition(text.length).column)
    for (offset in 0..text.length) {
      val position = index.indexToPosition(offset)
      assertEquals(offset, index.lineColumnToIndex(position.line, position.column))
    }
  }

  @Test
  fun mapsEmptyTextAndClampsOffsets() {
    val empty = LineIndex.from("")
    assertEquals(0, empty.indexToPosition(10).index)
    assertEquals(0, empty.indexToPosition(-10).column)
    val index = LineIndex.from("abc")
    assertEquals(3, index.indexToPosition(10).column)
    assertEquals(0, index.indexToPosition(-1).column)
  }
}