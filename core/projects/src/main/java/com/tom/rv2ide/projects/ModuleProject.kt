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

package com.tom.rv2ide.projects

import android.text.TextUtils
import androidx.annotation.RestrictTo
import com.tom.rv2ide.builder.model.IJavaCompilerSettings
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.compiler.javac.fs.CacheFSInfoSingleton
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.classpath.ZipFileClasspathReader
import com.tom.rv2ide.projects.events.LazyModuleActivatedEvent
import com.tom.rv2ide.projects.events.LazyModuleEvictedEvent
import com.tom.rv2ide.projects.util.BootClasspathProvider
import com.tom.rv2ide.projects.util.RuntimeProbe
import com.tom.rv2ide.tooling.api.models.GradleTask
import com.tom.rv2ide.utils.ClassTrie
import com.tom.rv2ide.utils.DocumentUtils
import com.tom.rv2ide.utils.SourceClassTrie
import com.tom.rv2ide.utils.SourceClassTrie.SourceNode
import com.tom.rv2ide.utils.StopWatch
import java.io.File
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory

/**
 * A module project. Base class for [AndroidModule] and [JavaModule].
 *
 * @author Akash Yadav
 */
abstract class ModuleProject(
    name: String,
    description: String,
    path: String,
    projectDir: File,
    buildDir: File,
    buildScript: File,
    tasks: List<GradleTask>,
) : GradleProject(name, description, path, projectDir, buildDir, buildScript, tasks) {

  abstract val compilerSettings: IJavaCompilerSettings

  companion object {

    private val log = LoggerFactory.getLogger(ModuleProject::class.java)
    private val compositeIndexingScope =
        CoroutineScope(Dispatchers.Default.limitedParallelism(2))
 
    @JvmStatic val COMPLETION_MODULE_KEY = Lookup.Key<ModuleProject>()
  }

  @JvmField val compileJavaSourceClasses = SourceClassTrie()

  @Volatile private var sourceIndexVersion: Long = 0
  @Volatile private var indexedOnce: Boolean = false
  @Volatile private var lazyCompositeBuildModule: Boolean = false
  @Volatile private var heavyCompositeBuildModule: Boolean = false
  @Volatile private var backgroundIndexingStarted: Boolean = false
  @Volatile private var lastUsedAt: Long = 0L

  @JvmField val compileClasspathClasses = ClassTrie()

  /**
   * Get the source directories of this module (non-transitive i.e for this module only).
   *
   * @return The source directories.
   */
  abstract fun getSourceDirectories(): Set<File>

  /**
   * Get the source directories with compile scope. This must include source directories of
   * transitive project dependencies and this module.
   *
   * @return The source directories.
   */
  abstract fun getCompileSourceDirectories(): Set<File>

  /**
   * Get the classpaths for this module project. The returned list always included the
   * `classes.jar`.
   */
  abstract fun getClassPaths(): Set<File>

  /**
   * Get the JAR files for this module. This does not include JAR files of any dependencies.
   *
   * @return The classpaths of this project.
   */
  abstract fun getModuleClasspaths(): Set<File>

  /**
   * Get the classpaths with compile scope. This must include classpaths of transitive project
   * dependencies as well. This includes classpaths for this module as well.
   *
   * @return The source directories.
   */
  abstract fun getCompileClasspaths(): Set<File>

  /**
   * Get the list of module projects with compile scope. This includes transitive module projects as
   * well.
   */
  abstract fun getCompileModuleProjects(): List<ModuleProject>

  /**
   * Find the source root of the given [file].
   *
   * @param file The file to find the source root for.
   * @return The source root (directory) of the given file, or `null` if not found.
   */
  fun findSourceRoot(file: File): Path? {
    return getCompileSourceDirectories().find { file.path.startsWith(it.path) }?.toPath()
  }

  /** Finds the source files and classes from source directories and classpaths and indexes them. */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
  fun indexSourcesAndClasspaths() {
    log.info("Indexing sources and classpaths for project: {}", path)
    RuntimeProbe.mark("ModuleProject.indexSourcesAndClasspaths path=$path")
    indexSources()
    indexClasspaths()
    indexedOnce = true
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
  fun markLazyCompositeBuildModule(heavy: Boolean = false) {
    lazyCompositeBuildModule = true
    heavyCompositeBuildModule = heavy
  }

  fun isLazyCompositeBuildModule(): Boolean = lazyCompositeBuildModule

  fun isHeavyCompositeBuildModule(): Boolean = heavyCompositeBuildModule

  fun hasBeenIndexed(): Boolean = indexedOnce

  fun isBackgroundIndexingStarted(): Boolean = backgroundIndexingStarted

  fun markUsedNow() {
    lastUsedAt = System.currentTimeMillis()
  }

  fun getLastUsedAt(): Long = lastUsedAt

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
  fun ensureIndexed() {
    if (indexedOnce) {
      return
    }
    synchronized(this) {
      if (indexedOnce) {
        return
      }
      indexSourcesAndClasspaths()
    }
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
  fun triggerBackgroundIndexing() {
    if (indexedOnce || backgroundIndexingStarted) {
      return
    }
    markUsedNow()
    synchronized(this) {
      if (indexedOnce || backgroundIndexingStarted) {
        return
      }
      backgroundIndexingStarted = true
    }

    compositeIndexingScope.launch {
      log.info("Background indexing started for heavy composite module: {}", path)
      var activated = false
      val watch = StopWatch("Background composite indexing ${path}")
      try {
        ensureIndexed()
        activated = indexedOnce
      } finally {
        backgroundIndexingStarted = false
        watch.log()
      }
      if (activated) {
        log.info("Background indexing completed for heavy composite module: {}", path)
        EventBus.getDefault().post(LazyModuleActivatedEvent(this@ModuleProject))
      }
    }
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
  fun evictIfIdle(idleForMs: Long): Boolean {
    if (!heavyCompositeBuildModule || !indexedOnce || backgroundIndexingStarted) {
      return false
    }
    val lastUsed = lastUsedAt
    if (lastUsed <= 0L) {
      return false
    }
    if (System.currentTimeMillis() - lastUsed < idleForMs) {
      return false
    }

    synchronized(this) {
      if (!indexedOnce || backgroundIndexingStarted) {
        return false
      }
      if (System.currentTimeMillis() - lastUsedAt < idleForMs) {
        return false
      }
      compileJavaSourceClasses.clear()
      compileClasspathClasses.clear()
      indexedOnce = false
      bumpSourceIndexVersion()
    }
    log.info("Evicted idle heavy composite module: {}", path)
    EventBus.getDefault().post(LazyModuleEvictedEvent(this))
    return true
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
  fun indexClasspaths() {
 
    RuntimeProbe.mark("ModuleProject.indexClasspaths path=$path")

    this.compileClasspathClasses.clear()

    val watch = StopWatch("Indexing classpaths for project: $path")
    val paths = getCompileClasspaths().filter { it.exists() }

    for (path in paths) {
      // Use 'getCanonicalFile' just to be sure that caches are stored with correct keys
      // See JavacFileManager.getContainer(Path) for more details
      CacheFSInfoSingleton.cache(CacheFSInfoSingleton.getCanonicalFile(path.toPath()))
    }

    val topLevelClasses = ZipFileClasspathReader().listClasses(paths).filter { it.isTopLevel }
    topLevelClasses.forEach { this.compileClasspathClasses.append(it.name) }

    if (IdeLogConfig.shouldLogDebug()) {
      watch.log()
      log.debug("Found {} classpaths.", topLevelClasses.size)
    }

    if (this is AndroidModule) {
      BootClasspathProvider.update(bootClassPaths.map { it.path })
    }
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
  fun indexSources() {

    this.compileJavaSourceClasses.clear()

    val watch = StopWatch("Indexing sources for project: $path")
    var count = 0
    getCompileSourceDirectories().forEach {
      val sourceDir = it.toPath()
      it.walk()
          .filter { file ->
            file.isFile && file.exists() && DocumentUtils.isJavaFile(file.toPath())
          }
          .map { file -> file.toPath() }
          .forEach { file ->
            this.compileJavaSourceClasses.append(file, sourceDir)
            count++
          }
    }

    if (IdeLogConfig.shouldLogDebug()) {
      watch.log()
      log.debug("Found {} source files.", count)
    }

    bumpSourceIndexVersion()
  }

  fun getSourceIndexVersion(): Long = sourceIndexVersion

  fun bumpSourceIndexVersion() {
    sourceIndexVersion++
  }

  fun getSourceFilesInDir(dir: Path): List<SourceNode> =
      this.compileJavaSourceClasses.getSourceFilesInDir(dir)

  fun packageNameOrEmpty(file: Path?): String {
    if (file == null) {
      return ""
    }

    val sourceNode = searchSourceFileRelatively(file)
    if (sourceNode != null) {
      return sourceNode.packageName
    }

    return ""
  }

  private fun searchSourceFileRelatively(file: Path?): SourceNode? {
    for (source in getCompileSourceDirectories().map(File::toPath)) {
      val relative = source.relativize(file)
      if (relative.pathString.contains("..")) {
        // This is most probably not the one we're expecting
        continue
      }

      var name = relative.pathString.substringBeforeLast(".java")
      name = name.replace('/', '.')

      val node = this.compileJavaSourceClasses.findNode(name)
      if (node != null && node is SourceNode) {
        return node
      }
    }

    return null
  }

  fun suggestPackageName(file: Path): String {
    var dir = file.parent.normalize()
    while (dir != null) {
      for (sibling in getSourceFilesInDir(dir)) {
        if (DocumentUtils.isSameFile(sibling.file, file)) {
          continue
        }
        var packageName: String = packageNameOrEmpty(sibling.file)
        if (TextUtils.isEmpty(packageName.trim { it <= ' ' })) {
          continue
        }
        val relativePath = dir.relativize(file.parent)
        val relativePackage = relativePath.toString().replace(File.separatorChar, '.')
        if (relativePackage.isNotEmpty()) {
          packageName = "$packageName.$relativePackage"
        }
        return packageName
      }
      dir = dir.parent.normalize()
    }
    return ""
  }

  fun listClassesFromSourceDirs(packageName: String): List<SourceNode> {
    return compileJavaSourceClasses
        .findInPackage(packageName)
        .filterIsInstance(SourceNode::class.java)
  }

  open fun isFromThisModule(file: File): Boolean {
    return isFromThisModule(file.toPath())
  }

  open fun isFromThisModule(file: Path): Boolean {
    // TODO This can be probably improved
    return file.startsWith(this.projectDir.toPath())
  }

  override fun toString() = "${javaClass.simpleName}: ${this.path}"
}
