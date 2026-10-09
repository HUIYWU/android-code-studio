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

import com.tom.rv2ide.lsp.models.DiagnosticSeverity.ERROR
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.HINT
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.INFO
import com.tom.rv2ide.lsp.models.DiagnosticSeverity.WARNING
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.models.Position
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_ERROR
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_NONE
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_TYPO
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_WARNING
import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.LoggerFactory


data class DiagnosticItem(
    var message: String,
    var code: String,
    var range: Range,
    var source: String,
    var severity: DiagnosticSeverity,
) {

  var extra: Any = Any()
  companion object {
    @JvmField
    val START_COMPARATOR: Comparator<in DiagnosticItem> =
        Comparator.comparing(DiagnosticItem::range)

    private val log = LoggerFactory.getLogger("DiagnosticRegionMapping")

    private fun mapSeverity(severity: DiagnosticSeverity): Short {
      return when (severity) {
        ERROR -> SEVERITY_ERROR
        WARNING -> SEVERITY_WARNING
        INFO -> SEVERITY_NONE
        HINT -> SEVERITY_TYPO
      }
    }
  }


  fun asDiagnosticRegion(content: CharSequence): DiagnosticRegion {
    return asDiagnosticRegion(LineIndex.from(content))
  }
  fun asDiagnosticRegion(lineIndex: LineIndex): DiagnosticRegion {
    return try {
      val startIndex = lineIndex.lineColumnToIndex(range.start.line, range.start.column)
      val endIndex = lineIndex.lineColumnToIndex(range.end.line, range.end.column)

      DiagnosticRegion(startIndex, endIndex, mapSeverity(severity))
    } catch (e: Exception) {
      // Keep diagnostics publishing fail-soft. A single malformed diagnostic range must not prevent
      // the editor from receiving the rest of the diagnostics batch.
      log.warn(
          "Failed to map diagnostic region code={} severity={} rawRange=({}:{})-({}:{})",
          code,
          severity,
          range.start.line,
          range.start.column,
          range.end.line,
          range.end.column,
          e,
      )
      DiagnosticRegion(0, 1, mapSeverity(severity))
    }
  }

}

class LineIndex private constructor(
    private val lineStarts: IntArray,
    private val contentLength: Int,
) {

  fun lineColumnToIndex(line: Int, column: Int): Int {
    if (lineStarts.isEmpty()) {
      return 0
    }
    val safeLine = line.coerceIn(0, lineStarts.lastIndex)
    val safeColumn = column.coerceAtLeast(0)
    return (lineStarts[safeLine] + safeColumn).coerceIn(0, contentLength)
  }

  fun indexToPosition(offset: Int): Position {
    val index = offset.coerceIn(0, contentLength)
    val found = lineStarts.binarySearch(index)
    val line = if (found >= 0) found else -found - 2
    return Position(line, index - lineStarts[line], index)
  }

  companion object {
    /**
     * Builds a reusable line-start table for a diagnostics publish batch. This avoids repeatedly
     * scanning a large editor buffer for every diagnostic range.
     */
    @JvmStatic
    fun from(content: CharSequence): LineIndex {
      val starts = ArrayList<Int>()
      starts.add(0)
      var index = 0
      while (index < content.length) {
        when (content[index].code) {
          13 -> {
            if (index + 1 < content.length && content[index + 1].code == 10) index++
            starts.add(index + 1)
          }
          10 -> starts.add(index + 1)
        }
        index++
      }
      return LineIndex(starts.toIntArray(), content.length)
    }
  }
}
data class DiagnosticResult(
    var file: Path,
    var diagnostics: List<DiagnosticItem>,
    var channel: String = DEFAULT_CHANNEL,
    var documentVersion: Int = UNKNOWN_DOCUMENT_VERSION,
    var documentRevision: Long = UNKNOWN_DOCUMENT_REVISION,
    var backendGeneration: Long = UNKNOWN_BACKEND_GENERATION,
) {
  companion object {
    const val DEFAULT_CHANNEL = "default"
    const val CHANNEL_SERVER = "server"
    const val CHANNEL_KOTLIN = "kotlin"
    const val UNKNOWN_BACKEND_GENERATION = -1L
    const val UNKNOWN_DOCUMENT_VERSION = -1
    const val UNKNOWN_DOCUMENT_REVISION = -1L

    @JvmField val NO_UPDATE = DiagnosticResult(Paths.get(""), emptyList())
  }
}


enum class DiagnosticSeverity {
  ERROR,
  WARNING,
  INFO,
  HINT,
}
