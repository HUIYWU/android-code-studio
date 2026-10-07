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

package com.tom.rv2ide.lsp.java.visitors;

import openjdk.source.tree.CompilationUnitTree;
import openjdk.source.tree.Tree;
import openjdk.source.util.JavacTask;
import openjdk.source.util.SourcePositions;
import openjdk.source.util.TreePath;
import openjdk.source.util.TreePathScanner;
import openjdk.source.util.Trees;

public class FindPathAt extends TreePathScanner<TreePath, Long> {

  private final SourcePositions pos;
  private CompilationUnitTree root;

  public FindPathAt(JavacTask task) {
    this.pos = Trees.instance(task).getSourcePositions();
  }

  @Override
  public TreePath visitCompilationUnit(CompilationUnitTree t, Long find) {
    root = t;
    return super.visitCompilationUnit(t, find);
  }

  @Override
  public TreePath scan(Tree tree, Long find) {
    if (tree == null || root == null) {
      return super.scan(tree, find);
    }
    long start = pos.getStartPosition(root, tree);
    long end = pos.getEndPosition(root, tree);
    if (start == -1 || end == -1 || find < start || find >= end) {
      return null;
    }
    TreePath smaller = super.scan(tree, find);
    if (smaller != null) {
      return smaller;
    }
    return getCurrentPath();
  }

  @Override
  public TreePath reduce(TreePath r1, TreePath r2) {
    if (r1 != null) {
      return r1;
    }
    return r2;
  }
}
