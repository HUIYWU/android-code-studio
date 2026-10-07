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

package com.tom.rv2ide.lsp.java.providers.completion

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.java.compiler.CompileTask
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.java.edits.MultipleClassImportEditHandler
import com.tom.rv2ide.lsp.java.models.JavaCompletionItem
import com.tom.rv2ide.lsp.java.utils.CancelChecker
import com.tom.rv2ide.lsp.java.utils.MethodStubGenerator
import com.tom.rv2ide.lsp.java.utils.ScopeHelper
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.InsertTextFormat.SNIPPET
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.MatchLevel.NO_MATCH
import com.tom.rv2ide.progress.ProgressManager.Companion.abortIfCancelled
import java.nio.file.Path
import java.util.function.Predicate
import jdkx.lang.model.element.ElementKind.METHOD
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.Modifier.FINAL
import jdkx.lang.model.element.Modifier.PRIVATE
import jdkx.lang.model.element.Modifier.STATIC
import jdkx.lang.model.type.DeclaredType
import openjdk.source.tree.ClassTree
import openjdk.source.tree.Tree.Kind.CLASS
import openjdk.source.util.TreePath
import openjdk.source.util.Trees

/**
 * Provides completions using [openjdk.source.tree.Scope].
 *
 * @author Akash Yadav
 */
class ScopeCompletionProvider(
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
    val trees = Trees.instance(task.task)
    val list: MutableList<CompletionItem> = ArrayList()
    val scope =
        try {
          trees.getScope(path)
        } catch (error: Throwable) {
          log.error(
              "Scope completion failed stage=getScope file={} cursor={} partial={} leafKind={}",
              file,
              cursor,
              partial,
              path.leaf.kind,
              error,
          )
          throw error
        }
    val filter =
        Predicate<CharSequence?> {
          if (it == null || it.isEmpty()) {
            return@Predicate false
          }

          var name = it
          if (it.contains('(')) {
            name = it.substring(0, it.lastIndexOf('('))
          }

          return@Predicate matchLevel(name, partial) != NO_MATCH
        }

    abortIfCancelled()
    abortCompletionIfCancelled()
    val scopeMembers =
        try {
          ScopeHelper.scopeMembers(task, scope, filter)
        } catch (error: Throwable) {
          log.error(
              "Scope completion failed stage=scopeMembers file={} cursor={} partial={} leafKind={}",
              file,
              cursor,
              partial,
              path.leaf.kind,
              error,
          )
          throw error
        }
    for (member in scopeMembers) {
      val name =
          try {
            var value = member.simpleName.toString()
            if (value.contains('(')) {
              value = value.substring(0, value.lastIndexOf('('))
            }
            value
          } catch (error: Throwable) {
            if (IdeLogConfig.shouldLogIde()) {
              log.debug(
                  "Scope completion skipped candidate with unreadable name file={} cursor={} partial={} memberKind={} errorType={}",
                  file,
                  cursor,
                  partial,
                  member.kind,
                  error.javaClass.name,
              )
            }
            if (CancelChecker.isCancelled(error)) {
              throw error
            }
            continue
          }
      val matchLevel =
          try {
            matchLevel(name, partial)
          } catch (error: Throwable) {
            if (IdeLogConfig.shouldLogIde()) {
              log.debug(
                  "Scope completion skipped candidate with failed match file={} cursor={} partial={} memberKind={} errorType={}",
                  file,
                  cursor,
                  partial,
                  member.kind,
                  error.javaClass.name,
              )
            }
            if (CancelChecker.isCancelled(error)) {
              throw error
            }
            continue
          }

      if (member.kind == METHOD) {
        val method = member as ExecutableElement
        try {
          val methodPath = path.parentPath
          val parentPath = methodPath?.parentPath
          if (parentPath == null) {
            if (IdeLogConfig.shouldLogIde()) {
              log.debug(
                  "Scope completion override path missing file={} cursor={} partial={} member={}",
                  file,
                  cursor,
                  partial,
                  method,
              )
            }
            list.add(method(task, listOf(method), !endsWithParen, matchLevel, partial))
          } else {
            overrideIfPossible(task, parentPath, method, endsWithParen, matchLevel, partial)
                ?.let(list::add)
          }
        } catch (error: Throwable) {
          if (IdeLogConfig.shouldLogIde()) {
            log.debug(
                "Scope completion skipped method candidate file={} cursor={} partial={} member={} errorType={}",
                file,
                cursor,
                partial,
                method,
                error.javaClass.name,
            )
          }
          if (CancelChecker.isCancelled(error)) {
            throw error
          }
          continue
        }
      } else {
        try {
          list.add(item(task, member, matchLevel))
        } catch (error: Throwable) {
          if (IdeLogConfig.shouldLogIde()) {
            log.debug(
                "Scope completion skipped non-method candidate file={} cursor={} partial={} memberKind={} errorType={}",
                file,
                cursor,
                partial,
                member.kind,
                error.javaClass.name,
            )
          }
          if (CancelChecker.isCancelled(error)) {
            throw error
          }
          continue
        }
      }
    }

    if (IdeLogConfig.shouldLogInfo()) {
      log.info("...found  {} scope members", list.size)
    }

    return CompletionResult(list)
  }

  /**
   * Override the given method if it is overridable.
   *
   * @param task The compilation task.
   * @param parentPath The tree path of the parent class.
   * @param method The method to override if possible.
   * @param endsWithParen Does the statement at cursor ends with a parenthesis?
   * @return The completion item.
   */
  private fun overrideIfPossible(
      task: CompileTask,
      parentPath: TreePath,
      method: ExecutableElement,
      endsWithParen: Boolean,
      matchLevel: MatchLevel,
      partial: String,
  ): CompletionItem? {
    var stage = "parentPathKind"
    try {
      if (parentPath.leaf.kind != CLASS) {
        // Can only override if the cursor is directly in a class declaration.
        return method(task, listOf(method), !endsWithParen, matchLevel, partial)
      }

      abortIfCancelled()
      abortCompletionIfCancelled()
      val types = task.task.types
      stage = "getParentElement"
      val parentElement =
          Trees.instance(task.task).getElement(parentPath)
              ?: return method(task, listOf(method), !endsWithParen, matchLevel, partial)
      if (method.enclosingElement == parentElement) {
        // Scope includes members declared by this anonymous class. They are already implemented,
        // so offering an override stub again is both redundant and unsafe during error recovery.
        if (IdeLogConfig.shouldLogIde()) {
          log.debug(
              "Scope completion skipping already-declared method member={} owner={} parentLeafKind={}",
              method,
              parentElement,
              parentPath.leaf.kind,
          )
        }
        return null
      }
      stage = "parentDeclaredType"
      val type = parentElement.asType() as? DeclaredType
          ?: return method(task, listOf(method), !endsWithParen, matchLevel, partial)
      stage = "methodEnclosingElement"
      val enclosing = method.enclosingElement
      val isFinalClass = enclosing.modifiers.contains(FINAL)
      val isNotOverridable =
          method.modifiers.contains(STATIC) ||
              method.modifiers.contains(FINAL) ||
              method.modifiers.contains(PRIVATE)
      stage = "overrideEligibility"
      if (
          isFinalClass ||
              isNotOverridable ||
              !types.isAssignable(type, enclosing.asType()) ||
              parentPath.leaf !is ClassTree
      ) {
        return method(task, listOf(method), !endsWithParen, matchLevel, partial)
      }

      stage = "generateStub"
      val generated =
          try {
            MethodStubGenerator.generate(
                method = method,
                parameterizedType =
                    types.asMemberOf(type, method) as jdkx.lang.model.type.ExecutableType,
                source = null,
                bodyStrategy = MethodStubGenerator.BodyStrategy.OVERRIDE_SUPER,
                module = task.module(),
            )
          } catch (error: Throwable) {
            if (CancelChecker.isCancelled(error)) {
              throw error
            }
            if (IdeLogConfig.shouldLogIde()) {
              log.debug(
                  "Scope completion override stub fallback member={} errorType={}",
                  method,
                  error.javaClass.name,
              )
            }
            return method(task, listOf(method), !endsWithParen, matchLevel, partial)
          }

      stage = "buildItem"
      val imports = generated.imports
      val methodSpec = generated.declaration
      val item = JavaCompletionItem()
      item.ideLabel = methodSpec.nameAsString
      item.completionKind = com.tom.rv2ide.lsp.models.CompletionItemKind.METHOD
      item.detail = method.returnType.toString() + " " + method
      item.ideSortText = item.ideLabel
      item.insertText = generated.renderedText
      item.insertTextFormat = SNIPPET
      item.snippetDescription = describeSnippet(partial)
      item.matchLevel = matchLevel
      item.data = data(task, method, 1)
      if (item.additionalTextEdits == null) {
        item.additionalTextEdits = mutableListOf()
      }

      stage = "imports"
      imports.removeIf { "java.lang." == it || fileImports.contains(it) || filePackage == it }
      item.additionalEditHandler = MultipleClassImportEditHandler(imports, fileImports, file)
      return item
    } catch (error: Throwable) {
      if (IdeLogConfig.shouldLogIde()) {
        log.debug(
            "Scope completion override fallback stage={} member={} errorType={}",
            stage,
            method,
            error.javaClass.name,
        )
      }
      if (CancelChecker.isCancelled(error)) {
        throw error
      }
      return method(task, listOf(method), !endsWithParen, matchLevel, partial)
    }
  }

  // Candidate-level failures are isolated above so malformed javac recovery symbols cannot abort completion.
}
