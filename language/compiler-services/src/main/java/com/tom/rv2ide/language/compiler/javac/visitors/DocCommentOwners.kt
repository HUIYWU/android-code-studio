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
package com.tom.rv2ide.language.compiler.javac.visitors

import openjdk.source.tree.ClassTree
import openjdk.source.tree.MethodTree
import openjdk.source.tree.Tree
import openjdk.source.tree.VariableTree

/**
 * Collects the trees of a method body that can own a doc comment.
 *
 * Partial reparse replaces a method body in place, so the doc-comment table must drop the entries of
 * the replaced body before the parsed owners are added. Local and anonymous classes are collected
 * like any other owner: only their stale doc comments are removed here, their symbols are handled by
 * `Enter.unenter`.
 */
class DocCommentOwners : ErrorAwareTreeScanner<Unit, Unit>() {

  val owners: MutableSet<Tree> = HashSet()

  override fun visitClass(node: ClassTree, p: Unit?) {
    owners.add(node)
    return super.visitClass(node, p)
  }

  override fun visitMethod(node: MethodTree, p: Unit?) {
    owners.add(node)
    return super.visitMethod(node, p)
  }

  override fun visitVariable(node: VariableTree, p: Unit?) {
    owners.add(node)
    return super.visitVariable(node, p)
  }
}