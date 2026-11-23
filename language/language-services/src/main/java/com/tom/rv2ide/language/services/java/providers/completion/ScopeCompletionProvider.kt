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

package com.tom.rv2ide.language.services.java.providers.completion

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.services.java.compiler.CompileTask
import com.tom.rv2ide.language.services.java.compiler.JavaCompilerService
import com.tom.rv2ide.language.services.java.utils.CancelChecker
import com.tom.rv2ide.language.services.java.utils.ScopeHelper
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.MatchLevel.NO_MATCH
import com.tom.rv2ide.progress.ProgressManager.Companion.abortIfCancelled
import java.nio.file.Path
import java.util.function.Predicate
import jdkx.lang.model.element.ElementKind.METHOD
import jdkx.lang.model.element.ExecutableElement
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
          list.add(method(task, listOf(method), !endsWithParen, matchLevel, partial))
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
}
