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
package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.language.services.java.semantic.DocumentStamp
import com.tom.rv2ide.lsp.models.DiagnosticResult
import java.nio.file.Path
import java.time.Instant

/**
 * Per-file diagnostics cache owned by [JavaDiagnosticsAnalyzer].
 *
 * An entry is valid only when it belongs to the same ledger generation, semantic [Entry.epoch] and
 * document stamp. Comparing the epoch and generation prevents a classpath or Kotlin ABI change from
 * being answered with diagnostics computed for the previous environment.
 *
 * Lifetime is driven by the document and workspace lifecycles, not by a size policy: entries are
 * dropped when a document closes and when the workspace environment is reset.
 */
internal class DiagnosticsLedger {
  data class Entry(
      val generation: Long,
      val stamp: DocumentStamp?,
      val epoch: Long,
      val resolverGeneration: Long?,
      val analyzedAt: Instant,
      val result: DiagnosticResult,
  )

  private val entries = HashMap<Path, Entry>()
  @Volatile private var generationValue = 0L

  fun generation(): Long = generationValue

  /** Returns the cached result when it is newer than [modifiedAt] and still matches its identity. */
  fun find(
      file: Path,
      stamp: DocumentStamp?,
      epoch: Long,
      generation: Long,
      modifiedAt: Instant,
      resolverGeneration: Long? = null,
  ): DiagnosticResult? =
      synchronized(entries) {
        val entry = entries[file.normalize()] ?: return null
        if (entry.generation != generation || generationValue != generation) return null
        if (!entry.analyzedAt.isAfter(modifiedAt)) return null
        if (entry.epoch != epoch) return null
        if (entry.resolverGeneration != resolverGeneration) return null
        if (stamp != null && entry.stamp != stamp) return null
        entry.result
      }

  /** Returns the cached result for [revision] when it belongs to the current generation. */
  fun findByRevision(
      file: Path,
      revision: Long,
      generation: Long,
      resolverGeneration: Long? = null,
  ): DiagnosticResult? =
      synchronized(entries) {
        val entry = entries[file.normalize()] ?: return null
        if (entry.generation != generation || generationValue != generation) return null
        if (entry.resolverGeneration != resolverGeneration) return null
        val cachedRevision = entry.result.documentRevision
        if (revision == DiagnosticResult.UNKNOWN_DOCUMENT_REVISION || cachedRevision == revision) {
          entry.result
        } else {
          null
        }
      }

  fun record(
      file: Path,
      stamp: DocumentStamp?,
      epoch: Long,
      generation: Long,
      analyzedAt: Instant,
      result: DiagnosticResult,
      resolverGeneration: Long? = null,
  ) {
    synchronized(entries) {
      entries[file.normalize()] = Entry(generation, stamp, epoch, resolverGeneration, analyzedAt, result)
    }
  }


  fun forget(file: Path) {
    synchronized(entries) { entries.remove(file.normalize()) }
  }
  fun clear() {
    synchronized(entries) {
      entries.clear()
      generationValue++
    }
  }

}