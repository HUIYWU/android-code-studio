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
 * Bounds contract for in-place replacement of one reparse unit (method body, method declaration
 * header, throws clause, field initializer or initializer block).
 *
 * Reparse units are selected by the edited text window, not by the caret: the window is exactly the
 * text that changed between two document revisions, so a caret that moved after the edit cannot pick
 * the wrong unit.
 *
 * Kept outside {@link JavaCompilerService} on purpose: that class has Android-dependent static state,
 * while these are pure text predicates that must stay usable from plain JVM tests.
 */
final class ReparseRangeBounds {

  private ReparseRangeBounds() {}

  /**
   * Returns {@code {oldWindowStart, oldWindowEnd}} describing the minimal divergent window between
   * {@code oldContents} and {@code newContents}, or {@code null} when the texts are identical.
   *
   * <p>A pure insertion yields an empty old window ({@code start == end}); a pure deletion yields an
   * empty new window. The returned offsets are indices into {@code oldContents}.
   */
  static int[] changedRange(String oldContents, String newContents) {
    if (oldContents == null || newContents == null) {
      return null;
    }
    final int oldLength = oldContents.length();
    final int newLength = newContents.length();
    int prefix = 0;
    final int maxPrefix = Math.min(oldLength, newLength);
    while (prefix < maxPrefix && oldContents.charAt(prefix) == newContents.charAt(prefix)) {
      prefix++;
    }
    if (prefix == oldLength && prefix == newLength) {
      return null;
    }
    int oldEnd = oldLength;
    int newEnd = newLength;
    while (oldEnd > prefix
        && newEnd > prefix
        && oldContents.charAt(oldEnd - 1) == newContents.charAt(newEnd - 1)) {
      oldEnd--;
      newEnd--;
    }
    return new int[] {prefix, oldEnd};
  }

  /**
   * True when [window] lies strictly inside the unit range {@code [start, end)}.
   *
   * <p>"Strictly" means the window starts inside the unit and ends before the unit end, so an
   * insertion that starts exactly at the unit end is rejected: it would otherwise be swallowed into
   * the replacement text. Replacing the whole unit, delimiters included, is still accepted.
   */
  static boolean contains(int[] window, int start, int end) {
    if (window == null || window.length != 2) {
      return false;
    }
    if (start < 0 || end < start) {
      return false;
    }
    final int windowStart = window[0];
    final int windowEnd = window[1];
    return windowStart >= start && windowStart < end && windowEnd <= end;
  }

  /**
   * True when [window] lies entirely inside {@code [start, end]}, both ends included.
   *
   * Unlike {@link #contains}, an empty window sited exactly at {@code end} is accepted: that is an
   * insertion right after the last character of the unit, which still belongs to it. Method
   * declaration headers need this, because adding the first modifier inserts in front of the return
   * type, which is the header end.
   */
  static boolean within(int[] window, int start, int end) {
    if (window == null || window.length != 2) {
      return false;
    }
    if (start < 0 || end < start) {
      return false;
    }
    final int windowStart = window[0];
    final int windowEnd = window[1];
    return windowStart >= start && windowStart <= windowEnd && windowEnd <= end;
  }
}
