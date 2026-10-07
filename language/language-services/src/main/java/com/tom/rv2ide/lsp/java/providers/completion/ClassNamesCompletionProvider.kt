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
import com.tom.rv2ide.lsp.java.compiler.CompileTask
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.java.providers.CompletionProvider
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.MatchLevel.NO_MATCH
import com.tom.rv2ide.progress.ProgressManager.Companion.abortIfCancelled
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Objects
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.util.TreePath

/**
 * Completes class names.
 *
 * @author Akash Yadav
 */
class ClassNamesCompletionProvider(
    completingFile: Path,
    cursor: Long,
    compiler: JavaCompilerService,
    settings: IServerSettings,
    val root: CompilationUnitTree,
) : IJavaCompletionProvider(cursor, completingFile, compiler, settings) {

  override fun doComplete(
      task: CompileTask,
      path: TreePath,
      partial: String,
      endsWithParen: Boolean,
  ): CompletionResult {
    val totalStartedNs = System.nanoTime()
    val list = mutableListOf<CompletionItem>()
    val packageName = Objects.toString(root.packageName, "")
    val uniques: MutableSet<String> = HashSet()

    val file: Path = Paths.get(root.sourceFile.toUri())
    val imports: Set<String> =
        root.imports.map { it.qualifiedIdentifier }.mapNotNull { it.toString() }.toSet()

    abortIfCancelled()
    abortCompletionIfCancelled()
    val packagePrivateStartedNs = System.nanoTime()
    val packagePrivateTypes = compiler.packagePrivateTopLevelTypes(packageName)
    for (className in packagePrivateTypes) {
      val matchLevel = matchLevel(className, partial)
      if (matchLevel == NO_MATCH) {
        continue
      }

      list.add(classItem(imports, file, className, matchLevel))
      uniques.add(className)
    }

    val packagePrivateUs = (System.nanoTime() - packagePrivateStartedNs) / 1_000L
    abortIfCancelled()
    abortCompletionIfCancelled()
    val publicIndexStartedNs = System.nanoTime()
    val topLevelTypes = compiler.publicTopLevelTypes()
    val publicIndexUs = (System.nanoTime() - publicIndexStartedNs) / 1_000L
    if (IdeLogConfig.shouldLogInfo()) {
      log.info(
          "class-name completion partial='{}' topLevelTypes={} hasString={} hasInteger={} hasDouble={}",
          partial,
          topLevelTypes.size,
          topLevelTypes.contains("java.lang.String"),
          topLevelTypes.contains("java.lang.Integer"),
          topLevelTypes.contains("java.lang.Double"))
    }
    val publicScanStartedNs = System.nanoTime()
    for (className in topLevelTypes) {
      val matchLevel = matchLevel(simpleName(className), partial)

      if (matchLevel == NO_MATCH) {
        continue
      }

      if (uniques.contains(className)) {
        continue
      }

      list.add(classItem(imports, file, className, matchLevel))
      uniques.add(className)
    }
    val publicScanUs = (System.nanoTime() - publicScanStartedNs) / 1_000L
    abortIfCancelled()
    abortCompletionIfCancelled()
    val localTypesStartedNs = System.nanoTime()
    for (t in root.typeDecls) {
      if (t !is ClassTree) {
        continue
      }
      val candidate = if (t.simpleName == null) "" else t.simpleName

      val matchLevel = matchLevel(candidate, partial)
      if (matchLevel == NO_MATCH) {
        continue
      }

      val name = packageName + "." + t.simpleName
      list.add(classItem(name, matchLevel))

      if (list.size > CompletionProvider.MAX_COMPLETION_ITEMS) {
        break
      }
    }

    val localTypesUs = (System.nanoTime() - localTypesStartedNs) / 1_000L
    if (IdeLogConfig.shouldLogDebug()) {
      log.debug("...found {} class names", list.size)
      log.debug(
          "JAVA_CLASS_NAMES_COMPLETION partialLength={} packagePrivateUs={} publicIndexUs={} " +
              "publicScanUs={} localTypesUs={} totalUs={} packagePrivateCount={} " +
              "publicTypeCount={} resultCount={}",
          partial.length,
          packagePrivateUs,
          publicIndexUs,
          publicScanUs,
          localTypesUs,
          (System.nanoTime() - totalStartedNs) / 1_000L,
          packagePrivateTypes.size,
          topLevelTypes.size,
          list.size)
    }

    return CompletionResult(list)
  }
}
