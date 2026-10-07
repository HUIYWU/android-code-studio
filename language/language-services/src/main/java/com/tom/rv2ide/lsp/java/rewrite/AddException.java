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

package com.tom.rv2ide.lsp.java.rewrite;

import androidx.annotation.NonNull;
import com.tom.rv2ide.lsp.java.compiler.CompilerProvider;
import com.tom.rv2ide.lsp.java.compiler.SynchronizedTask;
import com.tom.rv2ide.lsp.java.utils.FindHelper;
import com.tom.rv2ide.lsp.models.TextEdit;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import jdkx.lang.model.element.ExecutableElement;
import openjdk.source.tree.CompilationUnitTree;
import openjdk.source.util.Trees;

import static com.tom.rv2ide.lsp.java.utils.InsertUtilsKt.positionForImports;

public class AddException extends Rewrite {

  final String className, methodName;
  final String[] erasedParameterTypes;
  final String exceptionType;

  public AddException(
      String className,
      String methodName,
      String[] erasedParameterTypes,
      String exceptionType
  ) {
    this.className = className;
    this.methodName = methodName;
    this.erasedParameterTypes = erasedParameterTypes;
    this.exceptionType = exceptionType;
  }

  @NonNull
  @Override
  public Map<Path, TextEdit[]> rewrite(@NonNull CompilerProvider compiler) {
    Path file = compiler.findTypeDeclaration(className);
    if (file == CompilerProvider.NOT_FOUND) {
      return CANCELLED;
    }

    SynchronizedTask synchronizedTask = compiler.compile(file);
    return synchronizedTask.get(task -> {
      Trees trees = Trees.instance(task.task);
      final var type = task.task.getElements().getTypeElement(className);
      if (type == null) {
        return CANCELLED;
      }

      ExecutableElement methodElement =
          FindHelper.findMethod(task, className, methodName, erasedParameterTypes);
      if (methodElement == null) {
        return CANCELLED;
      }

      final var methodTree = trees.getTree(methodElement);
      if (methodTree == null || methodTree.getBody() == null) {
        return CANCELLED;
      }

      final var fileRoot = task.root(file);
      final var pos = trees.getSourcePositions();
      final var lines = fileRoot.getLineMap();

      final var index = pos.getStartPosition(fileRoot, methodTree.getBody());
      int line = (int) lines.getLineNumber(index);
      int column = (int) lines.getColumnNumber(index);
      Position insertPos = new Position(line - 1, column - 1);

      String simpleName = exceptionType;
      int lastDot = simpleName.lastIndexOf('.');
      if (lastDot != -1) {
        simpleName = exceptionType.substring(lastDot + 1);
      }

      String insertText;
      if (methodTree.getThrows().isEmpty()) {
        insertText = "throws " + simpleName + " ";
      } else {
        insertText = ", " + simpleName + " ";
      }

      final List<TextEdit> edits = new ArrayList<>();
      edits.add(new TextEdit(new Range(insertPos, insertPos), insertText));

      // Keep the quick fix compilable after adding a checked exception. We only add an import
      // when the resolved exception type is fully qualified and not already visible via java.lang,
      // same-package lookup or an existing explicit import.
      if (needsImport(fileRoot, exceptionType)) {
        final Position importPos = positionForImports(exceptionType, task, file);
        edits.add(new TextEdit(new Range(importPos, importPos), "import " + exceptionType + ";\n"));
      }

      return Collections.singletonMap(file, edits.toArray(new TextEdit[0]));
    });
  }

  private static boolean needsImport(
      final CompilationUnitTree fileRoot,
      final String exceptionType
  ) {
    if (exceptionType == null || exceptionType.isBlank() || exceptionType.indexOf('.') == -1) {
      return false;
    }

    if (exceptionType.startsWith("java.lang.")) {
      return false;
    }

    final String packageName =
        fileRoot.getPackageName() != null ? fileRoot.getPackageName().toString() : "";
    final int lastDot = exceptionType.lastIndexOf('.');
    final String exceptionPackage = lastDot != -1 ? exceptionType.substring(0, lastDot) : "";
    if (exceptionPackage.equals(packageName)) {
      return false;
    }

    for (final var importTree : fileRoot.getImports()) {
      final String imported = importTree.getQualifiedIdentifier().toString();
      if (exceptionType.equals(imported)) {
        return false;
      }
    }

    return true;
  }
}