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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounds contract for in-place replacement of a reparse range (method body or field initializer).
 *
 * The reparse path may only mutate the cached task when every change sits inside that range: the
 * declaration, other members and imports must be byte-identical. The contract accepts any number of
 * disjoint edits inside the range, which is what lets a formatter or a batch replacement stay
 * incremental.
 */
class ReparseRangeBoundsTest {

  private val bodyText = "{ return value; }"
  private val source = "class A {\n  int run(int value) $bodyText\n}"
  private val bodyStart = source.indexOf(bodyText)
  private val bodyEnd = bodyStart + bodyText.length

  @Test
  fun acceptsSingleEditInsideBody() {
    val updated = source.replace("return value;", "return value + 1;")
    assertTrue(
        "a single in-body edit must stay incremental",
        ReparseRangeBounds.staysInsideRange(source, updated, bodyStart, bodyEnd),
    )
  }

  @Test
  fun acceptsDisjointEditsInsideBody() {
    // Two independent whitespace insertions around the unchanged statement: two diff hunks.
    val updated = source.replace(bodyText, "{  return value;  }")
    assertTrue(
        "disjoint in-body edits must stay incremental",
        ReparseRangeBounds.staysInsideRange(source, updated, bodyStart, bodyEnd),
    )
  }

  @Test
  fun acceptsUnchangedContents() {
    assertTrue(ReparseRangeBounds.staysInsideRange(source, source, bodyStart, bodyEnd))
  }

  @Test
  fun acceptsInsertionBeforeClosingBrace() {
    val updated = source.replace(bodyText, "{ return value; int extra = 1; }")
    assertTrue(
        "inserting a statement before the closing brace must stay incremental",
        ReparseRangeBounds.staysInsideRange(source, updated, bodyStart, bodyEnd),
    )
  }

  @Test
  fun acceptsWholeRangeReplacement() {
    // A field initializer replaced by a completely different expression: the divergent window reaches
    // the range end, which is exactly the case that whole-range replacement must accept.
    val source = "class A { Runnable r = new Runnable() {}; }"
    val initializer = "new Runnable() {}"
    val start = source.indexOf(initializer)
    val end = start + initializer.length
    assertTrue(
        "replacing a whole initializer must stay incremental",
        ReparseRangeBounds.staysInsideRange(source, source.replace(initializer, "null"), start, end),
    )
  }

  @Test
  fun rejectsEditInMethodDeclaration() {
    val updated = source.replace("int run(int value)", "int run(int other)")
    assertFalse(
        "declaration edits must fall back to a full compile",
        ReparseRangeBounds.staysInsideRange(source, updated, bodyStart, bodyEnd),
    )
  }

  @Test
  fun rejectsEditAfterBody() {
    val updated = source.replace("\n}", "\n  int other;\n}")
    assertFalse(
        "member edits after the body must fall back to a full compile",
        ReparseRangeBounds.staysInsideRange(source, updated, bodyStart, bodyEnd),
    )
  }

  @Test
  fun rejectsBodyRemoval() {
    val updated = source.replace(bodyText, "")
    assertFalse(
        "removing the body entirely must fall back to a full compile",
        ReparseRangeBounds.staysInsideRange(source, updated, bodyStart, bodyEnd),
    )
  }

  @Test
  fun rejectsInvalidRangesAndNulls() {
    assertFalse(ReparseRangeBounds.staysInsideRange(null, source, bodyStart, bodyEnd))
    assertFalse(ReparseRangeBounds.staysInsideRange(source, null, bodyStart, bodyEnd))
    assertFalse(ReparseRangeBounds.staysInsideRange(source, source, -1, bodyEnd))
    assertFalse(ReparseRangeBounds.staysInsideRange(source, source, bodyEnd, bodyStart))
    assertFalse(
        ReparseRangeBounds.staysInsideRange(source, source, 0, source.length + 1),
    )
  }
}