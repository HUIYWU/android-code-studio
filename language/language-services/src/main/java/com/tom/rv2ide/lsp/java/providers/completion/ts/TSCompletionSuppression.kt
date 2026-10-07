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

package com.tom.rv2ide.lsp.java.providers.completion.ts

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSPoint
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import java.net.URI
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Identifies source regions where Java semantic completion must be suppressed.
 *
 * This is deliberately not a general completion-context classifier. The implementation keeps one
 * transformed-source Tree-sitter tree and uses Tree-sitter incremental parsing when the next
 * transformed source can be safely derived from the cached one. Any cache or incremental failure
 * falls back to a full parse; suppression semantics never depend on the optimization succeeding.
 */
object TSCompletionSuppression {
  private val log = LoggerFactory.getLogger(TSCompletionSuppression::class.java)
  private val cacheLock = Any()
  private val parser: TSParser = TSParser.create().also {
    it.language = TSLanguageJava.getInstance()
  }

  private var cachedFile: Path? = null
  private var cachedContents: String? = null
  private var cachedTree: TSTree? = null

  @JvmStatic
  fun classify(
      file: Path,
      content: String,
      cursor: Long,
      line: Int,
      column: Int,
  ): TSCompletionSuppressionReason {
    if (cursor < 0 || cursor > content.length || line < 0 || column < 0) {
      return TSCompletionSuppressionReason.NONE
    }

    synchronized(cacheLock) {
      val tree = obtainTree(file, content)
      return classifyNode(
          file,
          cursor,
          file.toUri(),
          tree.rootNode,
          line,
          column,
      )
    }
  }

  private fun obtainTree(file: Path, content: String): TSTree {
    val oldFile = cachedFile
    val oldContents = cachedContents
    val oldTree = cachedTree

    if (oldFile == file && oldContents == content && oldTree?.canAccess() == true) {
      logTree(
          file,
          oldContents.length,
          content.length,
          cacheHit = true,
          incrementalAttempted = false,
          incrementalUsed = false,
          fullParseFallback = false,
          reason = "CACHE_HIT",
          parseUs = 0L,
      )
      return oldTree
    }

    if (oldFile == file && oldContents != null && oldTree?.canAccess() == true) {
      val textEdit = diff(oldContents, content)
      if (textEdit != null && oldContents.indexOf('\r') < 0 && content.indexOf('\r') < 0) {
        val incrementalStartedNs = System.nanoTime()
        var candidate: TSTree? = null
        try {
          candidate = oldTree.copy()
          candidate.edit(toInputEdit(oldContents, content, textEdit))
          val newTree = parser.parseString(candidate, content)
          val parseUs = (System.nanoTime() - incrementalStartedNs) / 1_000L
          if (newTree != null && newTree.canAccess()) {
            candidate.close()
            candidate = null
            replaceCache(file, content, newTree)
            logTree(
                file,
                oldContents.length,
                content.length,
                cacheHit = false,
                incrementalAttempted = true,
                incrementalUsed = true,
                fullParseFallback = false,
                reason = "INCREMENTAL_PARSE",
                parseUs = parseUs,
            )
            return newTree
          }
        } catch (err: Throwable) {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info(
                "TSCompletionSuppression.incrementalParseFailed file={} errorType={} errorMessage={}",
                file,
                err.javaClass.name,
                err.message,
            )
          }
        } finally {
          candidate?.close()
        }
        return fullParse(
            file,
            content,
            oldContents.length,
            incrementalAttempted = true,
            reason = "INCREMENTAL_PARSE_FAILED",
        )
      }
    }

    return fullParse(
        file,
        content,
        oldContents?.length ?: 0,
        incrementalAttempted = oldFile == file && oldContents != null,
        reason = when {
          oldFile != file -> "CACHE_MISS_FILE"
          oldContents == null || oldTree == null -> "CACHE_MISS_EMPTY"
          content.indexOf('\r') >= 0 || oldContents.indexOf('\r') >= 0 -> "CR_TEXT"
          else -> "DIFF_UNAVAILABLE"
        },
    )
  }

  private fun fullParse(
      file: Path,
      content: String,
      oldLength: Int,
      incrementalAttempted: Boolean,
      reason: String,
  ): TSTree {
    val startedNs = System.nanoTime()
    val newTree = parser.parseString(content)
        ?: throw IllegalStateException("Tree-sitter full parse returned null")
    val parseUs = (System.nanoTime() - startedNs) / 1_000L
    replaceCache(file, content, newTree)
    logTree(
        file,
        oldLength,
        content.length,
        cacheHit = false,
        incrementalAttempted = incrementalAttempted,
        incrementalUsed = false,
        fullParseFallback = incrementalAttempted,
        reason = reason,
        parseUs = parseUs,
    )
    return newTree
  }

  private fun replaceCache(file: Path, content: String, tree: TSTree) {
    val previous = cachedTree
    cachedFile = file
    cachedContents = content
    cachedTree = tree
    if (previous != null && previous !== tree) {
      previous.close()
    }
  }

  private data class TextEdit(val start: Int, val oldEnd: Int, val newEnd: Int)

  private fun diff(old: String, new: String): TextEdit? {
    var start = 0
    val commonLength = minOf(old.length, new.length)
    while (start < commonLength && old[start] == new[start]) {
      start++
    }
    if (start == old.length && start == new.length) return null

    var oldEnd = old.length
    var newEnd = new.length
    while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) {
      oldEnd--
      newEnd--
    }
    return TextEdit(start, oldEnd, newEnd)
  }

  private fun toInputEdit(old: String, new: String, edit: TextEdit): TSInputEdit {
    return TSInputEdit.create(
        edit.start * 2,
        edit.oldEnd * 2,
        edit.newEnd * 2,
        pointAt(old, edit.start),
        pointAt(old, edit.oldEnd),
        pointAt(new, edit.newEnd),
    )
  }

  private fun pointAt(content: String, offset: Int): TSPoint {
    var row = 0
    var lineStart = 0
    var index = 0
    while (index < offset) {
      if (content[index] == '\n') {
        row++
        lineStart = index + 1
      }
      index++
    }
    return TSPoint.create(row, (offset - lineStart) * 2)
  }

  private fun classifyNode(
      file: Path,
      cursor: Long,
      uri: URI,
      root: TSNode,
      line: Int,
      column: Int,
  ): TSCompletionSuppressionReason {
    val byteColumn = column * 2
    val node = try {
      root.getNamedDescendantForPointRange(
          TSPoint.create(line, byteColumn),
          TSPoint.create(line, byteColumn + 2),
      )
    } catch (err: Throwable) {
      if (IdeLogConfig.shouldLogInfo()) {
        log.info(
            "TSCompletionSuppression.descendantLookupFailed file={} cursor={} uri={} line={} column={} errorType={} errorMessage={}",
            file,
            cursor,
            uri,
            line,
            column,
            err.javaClass.name,
            err.message,
        )
      }
      throw err
    }

    if (node == null || !node.canAccess()) {
      return TSCompletionSuppressionReason.NONE
    }

    // android-tree-sitter represents a missing parent as a non-null TSNode with a null native
    // pointer. Stop before dereferencing that sentinel; normal source reaches it after the root.
    var current: TSNode? = node
    while (current?.canAccess() == true) {
      val type = current.getType()
      when (type) {
        "line_comment", "block_comment", "comment" ->
            return TSCompletionSuppressionReason.COMMENT
        "string_literal" -> return TSCompletionSuppressionReason.STRING_LITERAL
        "character_literal" -> return TSCompletionSuppressionReason.CHARACTER_LITERAL
      }
      current = current.getParent()
    }
    return TSCompletionSuppressionReason.NONE
  }

  private fun logTree(
      file: Path,
      oldLength: Int,
      newLength: Int,
      cacheHit: Boolean,
      incrementalAttempted: Boolean,
      incrementalUsed: Boolean,
      fullParseFallback: Boolean,
      reason: String,
      parseUs: Long,
  ) {
    if (!IdeLogConfig.shouldLogIde()) return
    log.debug(
        "JAVA_TS_SUPPRESSION_TREE file={} oldContentLength={} newContentLength={} cacheHit={} incrementalAttempted={} incrementalUsed={} fullParseFallback={} reason={} parseUs={}",
        file,
        oldLength,
        newLength,
        cacheHit,
        incrementalAttempted,
        incrementalUsed,
        fullParseFallback,
        reason,
        parseUs,
    )
  }
}