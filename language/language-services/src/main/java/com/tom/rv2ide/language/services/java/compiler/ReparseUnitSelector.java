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
package com.tom.rv2ide.language.services.java.compiler;

/**
 * Decides which in-place reparse unit owns an edited window.
 *
 * Precedence is part of the contract: the body wins when the regions merely touch, because the body
 * replacement is the narrower and better supported unit. Absent regions must be passed as negative
 * offsets, which {@link ReparseRangeBounds} rejects.
 */
final class ReparseUnitSelector {

  private ReparseUnitSelector() {}

  static ReparseUnit select(
      int[] window,
      int bodyStart,
      int bodyEnd,
      int declarationStart,
      int headerEnd,
      int throwsStart,
      int throwsEnd) {
    if (ReparseRangeBounds.contains(window, bodyStart, bodyEnd)) {
      return ReparseUnit.METHOD_BODY;
    }
    if (ReparseRangeBounds.within(window, declarationStart, headerEnd)) {
      return ReparseUnit.METHOD_MODIFIERS;
    }
    if (ReparseRangeBounds.within(window, throwsStart, throwsEnd)) {
      return ReparseUnit.THROWS_CLAUSE;
    }
    return ReparseUnit.NONE;
  }

  /**
   * Selects the field unit that owns the window. A field without an initializer must pass negative
   * offsets for it, which {@link ReparseRangeBounds} rejects.
   */
  static ReparseUnit selectField(
      int[] window,
      int initializerStart,
      int initializerEnd,
      int declarationStart,
      int headerEnd) {
    if (ReparseRangeBounds.contains(window, initializerStart, initializerEnd)) {
      return ReparseUnit.FIELD_INITIALIZER;
    }
    if (ReparseRangeBounds.within(window, declarationStart, headerEnd)) {
      return ReparseUnit.FIELD_MODIFIERS;
    }
    return ReparseUnit.NONE;
  }

  /**
   * True when the window may introduce a throws clause.
   *
   * The window must be an empty insertion sited between the end of the last parameter and the point
   * where the body or the terminating semicolon starts. Absent bounds must be passed as negative
   * offsets.
   */
  static boolean acceptsThrowsInsertion(int[] window, int parametersEnd, int insertionUpperBound) {
    if (window == null || window.length != 2) {
      return false;
    }
    if (window[0] != window[1]) {
      return false;
    }
    if (parametersEnd < 0 || insertionUpperBound < 0) {
      return false;
    }
    return window[0] >= parametersEnd && window[0] <= insertionUpperBound;
  }

  /** Selects the class declaration header unit, or {@link ReparseUnit#NONE}. */
  static ReparseUnit selectClass(int[] window, int declarationStart, int headerEnd) {
    return ReparseRangeBounds.within(window, declarationStart, headerEnd)
        ? ReparseUnit.CLASS_MODIFIERS
        : ReparseUnit.NONE;
  }
}