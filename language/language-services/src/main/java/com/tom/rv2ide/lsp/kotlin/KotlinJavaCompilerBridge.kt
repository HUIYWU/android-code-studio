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
package com.tom.rv2ide.lsp.kotlin

import com.tom.rv2ide.lsp.java.JavaCompilerProvider
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.android.AndroidModule
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinJavaCompilerBridge(private val workspace: IWorkspace) {

  companion object {
    private val log = LoggerFactory.getLogger(KotlinJavaCompilerBridge::class.java)
    private const val MAX_PREFIX_CACHE_SIZE = 64
  }

  private var javaCompiler: JavaCompilerService? = null
  @Volatile private var allClassesCache: List<String>? = null
  private val prefixCache = ConcurrentHashMap<String, List<ClassInfo>>()

  init {
    initializeCompiler()
  }

  private fun initializeCompiler() {
    try {
      val mainModule =
          workspace.getSubProjects().filterIsInstance<AndroidModule>().firstOrNull {
            it.isApplication
          } ?: workspace.getSubProjects().filterIsInstance<AndroidModule>().firstOrNull()

      if (mainModule != null) {
        javaCompiler = JavaCompilerProvider.get(mainModule)
        KslLogs.info("Java compiler bridge initialized for module: {}", mainModule.path)
      } else {
        KslLogs.warn("No Android module found for Java compiler bridge")
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to initialize Java compiler bridge", e)
    }
  }

  /**
   * Get all available classes from the Java compiler This includes Android framework classes,
   * dependencies, and project classes
   */
  fun getAllAvailableClasses(): List<String> {
    allClassesCache?.let { cached ->
      return cached
    }
    return try {
      val classes = javaCompiler?.publicTopLevelTypes()?.toList() ?: emptyList()
      allClassesCache = classes
      classes
    } catch (e: Exception) {
      KslLogs.error("Failed to get available classes", e)
      emptyList()
    }
  }

  /** Find classes matching a prefix */
  fun findClassesByPrefix(prefix: String): List<ClassInfo> {
    if (prefix.isEmpty()) return emptyList()

    prefixCache[prefix]?.let { cached ->
      return cached
    }

    val allClasses = getAllAvailableClasses()
    val matches = allClasses
        .asSequence()
        .filter { className ->
          val simpleName = className.substringAfterLast('.')
          simpleName.startsWith(prefix, ignoreCase = false)
        }
        .map { className ->
          ClassInfo(
              simpleName = className.substringAfterLast('.'),
              fullyQualifiedName = className,
              packageName = className.substringBeforeLast('.', ""),
          )
        }
        .toList()

    if (prefixCache.size >= MAX_PREFIX_CACHE_SIZE) {
      prefixCache.clear()
    }
    prefixCache[prefix] = matches
    return matches
  }

  fun invalidateCaches() {
    allClassesCache = null
    prefixCache.clear()
  }

  data class ClassInfo(
      val simpleName: String,
      val fullyQualifiedName: String,
      val packageName: String,
  )
}

