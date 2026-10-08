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

package com.tom.rv2ide.lsp.models

import com.tom.rv2ide.lsp.CancellableRequestParams
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path

/** @author Akash Yadav */
data class ReferenceParams(
    var file: Path,
    var position: Position,
    var includeDeclaration: Boolean,
    override val cancelChecker: ICancelChecker,
) : CancellableRequestParams {
  var documentVersion: Int = -1
  var documentRevision: Long = -1L
}

enum class ReferenceRole {
  DEFINITION,
  USAGE,
}

/**
 * Locations returned by a references request.
 *
 * [roles] is optional for compatibility with language servers that only provide locations. When
 * present, it must have the same order and size as [locations].
 */
data class ReferenceResult @JvmOverloads constructor(
    var locations: List<Location>,
    var roles: List<ReferenceRole> = emptyList(),
)
