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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the unit-selection boundary: which part of a method declaration owns the edit.
 *
 * The layout mirrors a real declaration: `[declarationStart, headerEnd]` are the written modifiers,
 * `[bodyStart, bodyEnd]` is the method body and `[throwsStart, throwsEnd]` is the declared throws
 * list.
 */
class ReparseUnitSelectorTest {

  private val declarationStart = 0
  private val headerEnd = 8
  private val bodyStart = 10
  private val bodyEnd = 20
  private val throwsStart = 22
  private val throwsEnd = 28
  private val fieldInitStart = 10
  private val fieldInitEnd = 20

  private fun select(window: IntArray?): ReparseUnit =
      selectRegions(
          window,
          bodyStart,
          bodyEnd,
          declarationStart,
          headerEnd,
          throwsStart,
          throwsEnd,
      )

  private fun selectRegions(
      window: IntArray?,
      bodyStart: Int,
      bodyEnd: Int,
      declarationStart: Int,
      headerEnd: Int,
      throwsStart: Int,
      throwsEnd: Int,
  ): ReparseUnit =
      ReparseUnitSelector.select(
          window,
          bodyStart,
          bodyEnd,
          declarationStart,
          headerEnd,
          throwsStart,
          throwsEnd,
      )

  private fun selectField(window: IntArray?): ReparseUnit =
      ReparseUnitSelector.selectField(window, fieldInitStart, fieldInitEnd, declarationStart, headerEnd)

  @Test
  fun selectsFieldInitializerInsideInitializer() {
    assertEquals(ReparseUnit.FIELD_INITIALIZER, selectField(intArrayOf(15, 17)))
    assertEquals(ReparseUnit.FIELD_INITIALIZER, selectField(intArrayOf(fieldInitStart, fieldInitEnd)))
  }

  @Test
  fun selectsFieldModifiersForHeaderEdits() {
    assertEquals(ReparseUnit.FIELD_MODIFIERS, selectField(intArrayOf(declarationStart, headerEnd)))
    assertEquals(ReparseUnit.FIELD_MODIFIERS, selectField(intArrayOf(headerEnd, headerEnd)))
  }

  @Test
  fun selectsNothingForFieldEditsOutsideFieldRegions() {
    assertEquals(ReparseUnit.NONE, selectField(intArrayOf(headerEnd + 1, headerEnd + 1)))
    assertEquals(ReparseUnit.NONE, selectField(intArrayOf(fieldInitEnd, fieldInitEnd)))
    assertEquals(
        ReparseUnit.NONE,
        ReparseUnitSelector.selectField(intArrayOf(25, 25), -1, -1, declarationStart, headerEnd),
    )
  }

  private fun selectClass(window: IntArray?): ReparseUnit =
      ReparseUnitSelector.selectClass(window, declarationStart, headerEnd)

  @Test
  fun selectsClassModifiersForHeaderEdits() {
    assertEquals(ReparseUnit.CLASS_MODIFIERS, selectClass(intArrayOf(declarationStart, headerEnd)))
    assertEquals(ReparseUnit.CLASS_MODIFIERS, selectClass(intArrayOf(headerEnd, headerEnd)))
  }

  @Test
  fun selectsNothingOutsideTheClassHeader() {
    assertEquals(ReparseUnit.NONE, selectClass(intArrayOf(headerEnd + 1, headerEnd + 1)))
    assertEquals(ReparseUnit.NONE, selectClass(intArrayOf(0, headerEnd + 1)))
    assertEquals(ReparseUnit.NONE, selectClass(null))
    assertEquals(ReparseUnit.NONE, ReparseUnitSelector.selectClass(intArrayOf(0, 8), -1, headerEnd))
  }

  @Test
  fun selectsBodyForEditsInsideTheBody() {
    assertEquals(ReparseUnit.METHOD_BODY, select(intArrayOf(12, 14)))
    assertEquals(ReparseUnit.METHOD_BODY, select(intArrayOf(bodyStart, bodyEnd)))
  }

  @Test
  fun selectsBodyBeforeDeclarationRegions() {
    // The regions merely touch: the body keeps precedence.
    assertEquals(
        ReparseUnit.METHOD_BODY,
        selectRegions(intArrayOf(12, 12), 10, 20, 12, 12, 12, 12),
    )
  }

  @Test
  fun selectsModifiersForHeaderEdits() {
    assertEquals(ReparseUnit.METHOD_MODIFIERS, select(intArrayOf(declarationStart, headerEnd)))
    assertEquals(ReparseUnit.METHOD_MODIFIERS, select(intArrayOf(headerEnd, headerEnd)))
  }

  @Test
  fun selectsThrowsForClauseEdits() {
    assertEquals(ReparseUnit.THROWS_CLAUSE, select(intArrayOf(throwsStart, throwsEnd)))
    assertEquals(ReparseUnit.THROWS_CLAUSE, select(intArrayOf(throwsEnd, throwsEnd)))
  }

  @Test
  fun selectsNothingBetweenRegions() {
    assertEquals(ReparseUnit.NONE, select(intArrayOf(headerEnd + 1, headerEnd + 1)))
    assertEquals(ReparseUnit.NONE, select(intArrayOf(bodyEnd, bodyEnd)))
    assertEquals(ReparseUnit.NONE, select(intArrayOf(throwsEnd + 1, throwsEnd + 1)))
  }

  @Test
  fun selectsNothingForAbsentRegions() {
    assertEquals(
        ReparseUnit.NONE,
        selectRegions(intArrayOf(25, 25), -1, -1, 0, -1, -1, -1),
    )
  }

  @Test
  fun selectsNothingForInvalidWindows() {
    assertEquals(ReparseUnit.NONE, select(null))
    assertEquals(ReparseUnit.NONE, select(intArrayOf(0)))
    assertEquals(ReparseUnit.NONE, select(intArrayOf(bodyEnd, bodyStart)))
  }

  private fun acceptsInsertion(
      window: IntArray?,
      parametersEnd: Int = 10,
      insertionUpperBound: Int = 20,
  ): Boolean = ReparseUnitSelector.acceptsThrowsInsertion(window, parametersEnd, insertionUpperBound)

  @Test
  fun acceptsThrowsInsertionAtBothBounds() {
    assertTrue(acceptsInsertion(intArrayOf(10, 10)))
    assertTrue(acceptsInsertion(intArrayOf(20, 20)))
  }

  @Test
  fun rejectsThrowsInsertionOutsideTheGap() {
    assertFalse(acceptsInsertion(intArrayOf(9, 9)))
    assertFalse(acceptsInsertion(intArrayOf(21, 21)))
  }

  @Test
  fun rejectsThrowsInsertionForNonEmptyWindows() {
    assertFalse(acceptsInsertion(intArrayOf(10, 11)))
    assertFalse(acceptsInsertion(null))
    assertFalse(acceptsInsertion(intArrayOf(0)))
  }

  @Test
  fun rejectsThrowsInsertionForAbsentBounds() {
    assertFalse(acceptsInsertion(intArrayOf(10, 10), parametersEnd = -1))
    assertFalse(acceptsInsertion(intArrayOf(10, 10), insertionUpperBound = -1))
  }
}