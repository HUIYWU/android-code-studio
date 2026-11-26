package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

internal class LiveRootPathIndex<T>(
    entries: List<Entry<T>>,
    private val archiveBoundary: Boolean = false,
) {
  internal class Entry<T>(val path: String, val value: T, val priority: Int, val order: Int) {
    fun preferredTo(other: Entry<T>?): Boolean = other == null || priority > other.priority ||
        (priority == other.priority && order < other.order)
  }

  private val roots = entries.sortedWith(
      compareBy<Entry<T>> { it.path }.thenByDescending { it.priority }.thenBy { it.order },
  ).distinctBy { it.path }
  private val parents = IntArray(roots.size) { -1 }

  init {
    val stack = IntArray(roots.size)
    var size = 0
    for (index in roots.indices) {
      while (size > 0 && !roots[index].path.startsWith(roots[stack[size - 1]].path)) size--
      if (size > 0) parents[index] = stack[size - 1]
      stack[size++] = index
    }
  }

  fun contains(path: String): Boolean {
    var index = floor(path)
    while (index >= 0) {
      if (matches(path, roots[index].path)) return true
      index = parents[index]
    }
    return false
  }

  fun find(path: String): Entry<T>? {
    var index = floor(path)
    var best: Entry<T>? = null
    while (index >= 0) {
      val root = roots[index]
      if (root.preferredTo(best) && matches(path, root.path)) best = root
      index = parents[index]
    }
    return best
  }

  private fun floor(path: String): Int {
    var low = 0
    var high = roots.lastIndex
    while (low <= high) {
      val middle = (low + high) ushr 1
      if (roots[middle].path.compareTo(path) <= 0) low = middle + 1 else high = middle - 1
    }
    return high
  }

  private fun matches(path: String, root: String): Boolean {
    if (!path.startsWith(root)) return false
    val length = root.length
    return path.length == length || root.endsWith('/') || path[length] == '/' ||
        (archiveBoundary && path[length] == '!' && path.getOrNull(length + 1) == '/')
  }
}
