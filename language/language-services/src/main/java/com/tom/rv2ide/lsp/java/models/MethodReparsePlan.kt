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

package com.tom.rv2ide.lsp.java.models

import java.nio.file.Path

/**
 * Immutable snapshot used by the method-level reparse MVP.
 *
 * The contents are the exact text that will be analyzed by javac, after CompletionProvider's
 * temporary syntax fixes have been applied. The compiler derives the single edit range by comparing
 * this snapshot with the text retained by the cached CompileBatch.
 */
data class MethodReparsePlan(
    @JvmField val file: Path,
    @JvmField val contents: String,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val cursor: Long,
)