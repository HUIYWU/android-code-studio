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

package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.utils.StopWatch
import java.util.function.Consumer
import openjdk.source.tree.CompilationUnitTree
import openjdk.tools.javac.api.JavacTaskImpl

/**
 * Default implementation of [CompilationTaskProcessor].
 *
 * @author Akash Yadav
 */
class DefaultCompilationTaskProcessor : CompilationTaskProcessor {

  override fun process(task: JavacTaskImpl, processCompilationUnit: Consumer<CompilationUnitTree>) {
    val watch = StopWatch("Process compilation task")
    val trees = try {
      val parsed = task.parse().toList()
      parsed.also {
        if (IdeLogConfig.shouldLogDebug()) {
          watch.lapFromLast("Parsed trees")
        }
      }
    } catch (err: Throwable) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn(
            "DefaultCompilationTaskProcessor.parse failed taskClass={} contextPresent={}",
            task.javaClass.name,
            task.context != null,
            err,
        )
      }
      throw err
    }

    trees.forEach { processCompilationUnit.accept(it) }
    if (IdeLogConfig.shouldLogDebug()) {
      watch.lapFromLast("Processed trees")
    }

    try {
      task.analyze()
      if (IdeLogConfig.shouldLogDebug()) {
        watch.lapFromLast("Analyzed all trees")
      }
    } catch (err: Throwable) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn(
            "DefaultCompilationTaskProcessor.analyze failed taskClass={} contextPresent={}",
            task.javaClass.name,
            task.context != null,
            err,
        )
      }
      throw err
    }
  }

  companion object {
    private val log = org.slf4j.LoggerFactory.getLogger(DefaultCompilationTaskProcessor::class.java)
  }
}
