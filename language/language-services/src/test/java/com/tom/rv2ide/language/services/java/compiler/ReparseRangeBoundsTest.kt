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
package com.tom.rv2ide.language.services.java.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for selecting a reparse unit from the edited text window.
 *
 * Units are chosen by the window that actually changed between two document revisions, not by the
 * caret, so a caret that moved after the edit cannot select the wrong member.
 */
class ReparseRangeBoundsTest {

  private val body = "{ return value; }"
  private val source = "class A {\n  int run(int value) $body\n}"
  private val bodyStart = source.indexOf(body)
  private val bodyEnd = bodyStart + body.length

  @Test
  fun changedRange_isNullForIdenticalTexts() {
    assertNull(ReparseRangeBounds.changedRange(source, source))
    assertNull(ReparseRangeBounds.changedRange(null, source))
    assertNull(ReparseRangeBounds.changedRange(source, null))
  }

  @Test
  fun changedRange_reportsInsertionPointForPureInsertion() {
    // The inserted ` +1` sits before the unchanged `; }` suffix, so the old window is empty.
    val updated = source.replace("return value;", "return value +1;")
    val window = ReparseRangeBounds.changedRange(source, updated)!!
    assertEquals("a pure insertion has an empty old window", window[0].toLong(), window[1].toLong())
    assertEquals(source.indexOf(';').toLong(), window[0].toLong())
    assertTrue(ReparseRangeBounds.contains(window, bodyStart, bodyEnd))
  }

  @Test
  fun changedRange_coversReplacedText() {
    val updated = source.replace("return value;", "return compute();")
    val window = ReparseRangeBounds.changedRange(source, updated)!!
    assertEquals("value", source.substring(window[0], window[1]))
    assertTrue(ReparseRangeBounds.contains(window, bodyStart, bodyEnd))
  }

  @Test
  fun changedRange_spansDisjointEditsInsideOneUnit() {
    // Two whitespace insertions around the unchanged statement: two diff hunks, one window.
    val updated = source.replace(body, "{  return value;  }")
    val window = ReparseRangeBounds.changedRange(source, updated)!!
    assertTrue(ReparseRangeBounds.contains(window, bodyStart, bodyEnd))
  }

  @Test
  fun contains_acceptsInsertionBeforeClosingBrace() {
    val updated = source.replace(body, "{ return value; int extra = 1; }")
    val window = ReparseRangeBounds.changedRange(source, updated)!!
    assertTrue(ReparseRangeBounds.contains(window, bodyStart, bodyEnd))
  }

  @Test
  fun contains_acceptsWholeUnitReplacement() {
    val updated = source.replace(body, "{ }")
    val window = ReparseRangeBounds.changedRange(source, updated)!!
    assertTrue(ReparseRangeBounds.contains(window, bodyStart, bodyEnd))
  }

  @Test
  fun contains_rejectsEditInDeclaration() {
    val updated = source.replace("int run(int value)", "int run(int other)")
    val window = ReparseRangeBounds.changedRange(source, updated)!!
    assertFalse(ReparseRangeBounds.contains(window, bodyStart, bodyEnd))
  }

  @Test
  fun contains_rejectsInsertionAtUnitEnd() {
    val updated = source.replace("\n}", "\n  int other;\n}")
    val window = ReparseRangeBounds.changedRange(source, updated)!!
    assertFalse(
        "an insertion that starts at the unit end would be swallowed into the replacement text",
        ReparseRangeBounds.contains(window, bodyStart, bodyEnd),
    )
  }

  @Test
  fun contains_rejectsInvalidWindows() {
    assertFalse(ReparseRangeBounds.contains(null, bodyStart, bodyEnd))
    assertFalse(ReparseRangeBounds.contains(intArrayOf(0), bodyStart, bodyEnd))
    assertFalse(ReparseRangeBounds.contains(intArrayOf(bodyStart, bodyEnd), -1, bodyEnd))
    assertFalse(ReparseRangeBounds.contains(intArrayOf(bodyStart, bodyEnd), bodyEnd, bodyStart))
  }

  @Test
  fun within_acceptsWindowsEndingAtTheUnitEnd() {
    assertTrue(ReparseRangeBounds.within(intArrayOf(5, 9), 5, 9))
    assertTrue(ReparseRangeBounds.within(intArrayOf(5, 5), 5, 9))
  }

  @Test
  fun within_acceptsInsertionExactlyAtTheUnitEnd() {
    // Adding the first modifier inserts right in front of the return type, which is the header end.
    assertTrue(ReparseRangeBounds.within(intArrayOf(9, 9), 5, 9))
  }

  @Test
  fun within_rejectsWindowsOutsideTheUnit() {
    assertFalse(ReparseRangeBounds.within(intArrayOf(4, 4), 5, 9))
    assertFalse(ReparseRangeBounds.within(intArrayOf(10, 10), 5, 9))
    assertFalse(ReparseRangeBounds.within(intArrayOf(5, 10), 5, 9))
    assertFalse(ReparseRangeBounds.within(null, 5, 9))
    assertFalse(ReparseRangeBounds.within(intArrayOf(0), 5, 9))
    assertFalse(ReparseRangeBounds.within(intArrayOf(5, 9), -1, 9))
    assertFalse(ReparseRangeBounds.within(intArrayOf(5, 9), 9, 5))
  }
}