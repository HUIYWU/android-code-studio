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
package com.tom.rv2ide.language.services.java.visitors

import androidx.core.util.Pair
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.VariableTree
import openjdk.source.util.TreePath
import openjdk.source.util.TreePathScanner
import openjdk.source.util.Trees
import openjdk.tools.javac.api.JavacTaskImpl

/**
 * Records the source range of every class-member field that has an initializer.
 *
 * Fields of local and anonymous classes are skipped on purpose: their declarations are replaced as
 * part of the enclosing method body, not as standalone members.
 */
class FieldRangeScanner(val task: JavacTaskImpl) :
    TreePathScanner<Unit, MutableList<Pair<Range, TreePath>>>() {

  var root: CompilationUnitTree? = null
  private val positions = Trees.instance(task).sourcePositions

  override fun visitCompilationUnit(
      node: CompilationUnitTree?,
      list: MutableList<Pair<Range, TreePath>>?,
  ) {
    this.root = node
    return super.visitCompilationUnit(node, list)
  }

  override fun visitVariable(node: VariableTree?, list: MutableList<Pair<Range, TreePath>>) {
    val root = this.root ?: return
    if (node == null || node.initializer == null) {
      return
    }
    if (!isMemberField()) {
      return
    }
    val start = positions.getStartPosition(root, node).toInt()
    val end = positions.getEndPosition(root, node).toInt()
    if (start < 0 || end <= start) {
      return
    }
    // Only the indices matter for reparse routing; line/column stay zero because this scanner does
    // not translate offsets into the line map.
    val startPosition = Position(0, 0, start)
    val endPosition = Position(0, 0, end)
    list.add(Pair.create(Range(startPosition, endPosition), currentPath))
  }

  /** True only for declarations whose class chain reaches the compilation unit through classes. */
  private fun isMemberField(): Boolean {
    val classPath = currentPath?.parentPath ?: return false
    if (classPath.leaf !is ClassTree) {
      return false
    }
    val outer = classPath.parentPath?.leaf ?: return false
    return outer is ClassTree || outer is CompilationUnitTree
  }
}