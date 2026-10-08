/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import java.io.File
import java.nio.file.Path

internal data class KotlinAnalysisModuleContext(
    val modulePath: String,
    val sourceRoots: List<Path>,
    val compileModulePaths: List<String>,
    val binaryRoots: List<Path>,
    val bootClasspaths: List<Path>,
)

internal data class KotlinAnalysisProjectContext(
    val modules: List<KotlinAnalysisModuleContext>,
) {
  private val modulesByPath = modules.associateBy { it.modulePath }

  fun module(modulePath: String): KotlinAnalysisModuleContext? = modulesByPath[modulePath]

  fun moduleFor(path: Path, workspace: IWorkspace): KotlinAnalysisModuleContext? {
    val project = workspace.findModuleForFile(path, false) ?: return null
    return module(project.path)
  }

  companion object {
    fun create(
        workspace: IWorkspace,
        classpathProvider: KotlinProjectClasspathProvider,
    ): KotlinAnalysisProjectContext {
      val modules = collectModules(workspace)
      val contexts = modules.map { module ->
        KotlinAnalysisModuleContext(
            modulePath = module.path,
            sourceRoots = existingPaths(module.getSourceDirectories()),
            compileModulePaths = module.getCompileModuleProjects()
                .map { it.path }
                .distinct()
                .sorted(),
            binaryRoots = classpathProvider.getModuleClasspath(module, modules)
                .map { it.toPath().normalize() }
                .distinct(),
            bootClasspaths = if (module is AndroidModule) {
              module.bootClassPaths.map { it.absoluteFile.toPath().normalize() }
                  .filter { it.toFile().exists() }
                  .distinct()
            } else {
              emptyList()
            },
        )
      }
      return KotlinAnalysisProjectContext(modules = contexts)
    }

    private fun collectModules(workspace: IWorkspace): List<ModuleProject> {
      val directModules = buildList {
        add(workspace.getRootProject())
        addAll(workspace.getSubProjects())
      }.filterIsInstance<ModuleProject>()
      val collected = linkedMapOf<String, ModuleProject>()

      fun collect(module: ModuleProject) {
        if (collected.containsKey(module.path)) return
        collected[module.path] = module
        module.getCompileModuleProjects().forEach(::collect)
      }

      directModules.forEach(::collect)
      return collected.values.sortedBy { it.path }
    }

    private fun existingPaths(files: Collection<File>): List<Path> =
        files.asSequence()
            .filter(File::isDirectory)
            .map { it.absoluteFile.toPath().normalize() }
            .distinct()
            .sortedBy(Path::toString)
            .toList()
  }
}
