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
package com.tom.rv2ide.lsp.java.providers.completion

import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.java.compiler.CompileTask
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.InsertTextFormat.SNIPPET
import com.tom.rv2ide.lsp.models.MatchLevel.NO_MATCH
import com.tom.rv2ide.progress.ProgressManager.Companion.abortIfCancelled
import java.nio.file.Path
import jdkx.lang.model.element.ElementKind.CLASS
import jdkx.lang.model.element.Modifier.STATIC
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.TypeVariable
import openjdk.source.tree.NewClassTree
import openjdk.source.util.TreePath
import openjdk.source.util.Trees

/** Completes the member class in a qualified creation such as `outer.new Inner(...)`. */
class QualifiedNewClassCompletionProvider(
    completingFile: Path,
    cursor: Long,
    compiler: JavaCompilerService,
    settings: IServerSettings,
) : IJavaCompletionProvider(cursor, completingFile, compiler, settings) {

  override fun doComplete(
      task: CompileTask,
      path: TreePath,
      partial: String,
      endsWithParen: Boolean,
  ): CompletionResult {
    val creation = path.leaf as? NewClassTree ?: return CompletionResult.EMPTY
    val qualifier = creation.enclosingExpression ?: return CompletionResult.EMPTY
    val qualifierPath = TreePath(path, qualifier)
    val trees = Trees.instance(task.task)
    val scope = trees.getScope(qualifierPath)
    val qualifierType = declaredUpperBound(trees.getTypeMirror(qualifierPath))
        ?: return CompletionResult.EMPTY
    val owner = qualifierType.asElement() as? TypeElement ?: return CompletionResult.EMPTY
    val items = mutableListOf<CompletionItem>()

    for (member in task.task.elements.getAllMembers(owner)) {
      abortIfCancelled()
      abortCompletionIfCancelled()
      if (member.kind != CLASS || member.modifiers.contains(STATIC)) {
        continue
      }
      val matchLevel = matchLevel(member.simpleName, partial)
      if (matchLevel == NO_MATCH || !trees.isAccessible(scope, member, qualifierType)) {
        continue
      }
      val completion = item(task, member, matchLevel)
      if (!endsWithParen) {
        val insertionPrefix =
            if (partial.isEmpty() && !hasWhitespaceBeforeCursor(path)) "new " else ""
        completion.insertText = insertionPrefix + member.simpleName.toString() + "($0)"
        completion.insertTextFormat = SNIPPET
        completion.command = Command("Trigger Parameter Hints", Command.TRIGGER_PARAMETER_HINTS)
        completion.snippetDescription =
            describeSnippet(prefix = partial, allowCommandExecution = true)
      }
      items.add(completion)
    }
    return CompletionResult(items)
  }

  private fun hasWhitespaceBeforeCursor(path: TreePath): Boolean {
    if (cursor <= 0) return false
    return runCatching {
      val content = path.compilationUnit.sourceFile.getCharContent(true)
      cursor <= content.length && content[(cursor - 1).toInt()].isWhitespace()
    }.getOrDefault(false)
  }

  private fun declaredUpperBound(type: jdkx.lang.model.type.TypeMirror?): DeclaredType? {
    var current = type
    val visited = mutableSetOf<jdkx.lang.model.type.TypeMirror>()
    while (current is TypeVariable && visited.add(current)) {
      current = current.upperBound
    }
    return current as? DeclaredType
  }
}
