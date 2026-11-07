package com.tom.rv2ide.language.services.kotlin.semantic

import com.tom.rv2ide.models.Position

internal fun shouldSkipCompletionAfterWhitespace(
    content: CharSequence,
    position: Position,
    prefix: String?,
): Boolean {
  if (!prefix.isNullOrEmpty() || position.line < 0 || position.column <= 0) return false
  var line = 0
  var index = 0
  while (index < content.length && line < position.line) {
    if (content[index] == '\n') line++
    index++
  }
  if (line != position.line) return false
  val lineStart = index
  var lineEnd = lineStart
  while (lineEnd < content.length && content[lineEnd] != '\n') lineEnd++
  val cursor = (lineStart + position.column).coerceAtMost(lineEnd)
  if (cursor <= lineStart) return false
  val previous = content[cursor - 1]
  return previous == ' ' || previous == '\t'
}