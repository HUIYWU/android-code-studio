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
 * Bounds contract for in-place replacement of a method body or a field initializer.
 *
 * Kept outside {@link JavaCompilerService} on purpose: that class has Android-dependent static state,
 * while this check is a pure text-range predicate that must stay usable from plain JVM tests.
 */
final class ReparseRangeBounds {

  private ReparseRangeBounds() {}

  /**
   * True when every change between {@code oldContents} and {@code newContents} lies inside the range
   * {@code [start, end)}, so that range can be replaced in place.
   *
   * <p>The check compares the minimal divergent window of the two texts. Shared prefix and shifted
   * suffix alone are not sufficient: inserting a member directly after the range keeps both equal, yet
   * the inserted text would be swallowed into the replacement text.
   *
   * <p>Replacing the whole range, delimiters included, is accepted: only insertions that start at or
   * after the range end are rejected.
   */
  static boolean staysInsideRange(
      String oldContents, String newContents, int start, int end) {
    if (oldContents == null || newContents == null) {
      return false;
    }
    if (start < 0 || end < start || end > oldContents.length()) {
      return false;
    }
    final int newEnd = end + newContents.length() - oldContents.length();
    if (newEnd > newContents.length() || newEnd <= start) {
      return false;
    }
    if (oldContents.contentEquals(newContents)) {
      return true;
    }

    final int oldLength = oldContents.length();
    final int newLength = newContents.length();
    int prefix = 0;
    final int maxPrefix = Math.min(oldLength, newLength);
    while (prefix < maxPrefix && oldContents.charAt(prefix) == newContents.charAt(prefix)) {
      prefix++;
    }
    int oldWindowEnd = oldLength;
    int newWindowEnd = newLength;
    while (oldWindowEnd > prefix
        && newWindowEnd > prefix
        && oldContents.charAt(oldWindowEnd - 1) == newContents.charAt(newWindowEnd - 1)) {
      oldWindowEnd--;
      newWindowEnd--;
    }
    // Requiring "prefix < end" keeps insertions that start exactly at the range end out of the
    // replacement text, while "<=" still allows replacing the whole range including its delimiters.
    return prefix >= start && prefix < end && oldWindowEnd <= end && newWindowEnd <= newEnd;
  }
}