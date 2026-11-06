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
package com.tom.rv2ide.language.services.java.semantic

/**
 * Identity of the editor snapshot a semantic request was computed from.
 *
 * [version] follows the editor's ordinary edit sequence. [revision] distinguishes document
 * lifecycles such as close/reopen, where a version can legitimately restart at zero.
 */
internal data class DocumentStamp(
    val version: Int,
    val revision: Long,
) {
  init {
    require(version >= 0) { "A semantic request requires a known document version" }
    require(revision >= 0L) { "A semantic request requires a known document revision" }
  }
}
