/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.models.ProjectBinaryOrigin
import com.tom.rv2ide.projects.models.ProjectBinaryRole
import com.tom.rv2ide.projects.models.ProjectInputSnapshotFactory
import com.tom.rv2ide.projects.models.ProjectModuleInputSnapshot
import com.tom.rv2ide.projects.models.ProjectRootDescriptor
import java.io.File
import java.nio.file.Path

internal data class KotlinAnalysisBinaryRootContext(
    val path: Path,
    val role: ProjectBinaryRole,
    val origin: ProjectBinaryOrigin,
    val ownerModulePath: String?,
    val variantName: String?,
    val exists: Boolean,
)

internal data class KotlinAnalysisLibraryAttachmentContext(
    val libraryIdentity: String,
    val binaryPaths: List<Path>,
    val sourcePaths: List<Path>,
    val javadocPaths: List<Path>,
    val ownerModulePath: String,
)

internal data class KotlinAnalysisModuleContext(
    val modulePath: String,
    val sourceRootDescriptors: List<ProjectRootDescriptor>,
    val sourceRoots: List<Path>,
    val javaSourceRoots: List<Path>,
    val generatedSourceRoots: List<Path>,
    val moduleOutputRoots: List<Path>,
    val compileModulePaths: List<String>,
    val binaryRoots: List<Path>,
    val bootClasspaths: List<Path>,
    val binaryRootContexts: List<KotlinAnalysisBinaryRootContext>,
    val libraryAttachments: List<KotlinAnalysisLibraryAttachmentContext>,
    val javaSourceVersion: String,
    val javaBytecodeVersion: String,
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
    ): KotlinAnalysisProjectContext {
      val modules = collectModules(workspace)
      val snapshot = ProjectInputSnapshotFactory.capture(workspace)
      val snapshotsByPath = snapshot.modules.associateBy { it.identity.modulePath }
      val contexts = modules.mapNotNull { module ->
        snapshotsByPath[module.path]?.let(::createContext)
      }
      return KotlinAnalysisProjectContext(modules = contexts)
    }

    private fun createContext(
        snapshot: ProjectModuleInputSnapshot,
    ): KotlinAnalysisModuleContext {
      val sourceRootDescriptors = (snapshot.ordinarySourceRoots + snapshot.generatedSourceRoots + snapshot.javaSourceRoots)
          .filter { it.path.toFile().isDirectory }
          .distinctBy { it.path.normalize() to it.role to it.origin to it.variantName }
          .sortedWith(compareBy({ it.path.toString() }, { it.role.name }, { it.origin.name }, { it.variantName.orEmpty() }))
      val sourceRoots = sourceRootDescriptors.map { it.path }.distinct().sortedBy(Path::toString)
      return KotlinAnalysisModuleContext(
          modulePath = snapshot.identity.modulePath,
          sourceRootDescriptors = sourceRootDescriptors,
          sourceRoots = sourceRoots,
          javaSourceRoots = existingPaths(snapshot.javaSourceRoots.map { it.path.toFile() }),
          generatedSourceRoots = existingPaths(snapshot.generatedSourceRoots.map { it.path.toFile() }),
          moduleOutputRoots = snapshot.moduleOutputs.map { it.path }.filter { it.toFile().exists() }.distinct().sortedBy(Path::toString),
          compileModulePaths = snapshot.compileModulePaths,
          binaryRoots = (snapshot.moduleOutputs + snapshot.externalBinaries)
              .filter { it.role != ProjectBinaryRole.BOOT_BINARY }
              .map { it.path }
              .filter { it.toFile().exists() }
              .distinct()
              .sortedBy(Path::toString),
          bootClasspaths = snapshot.bootBinaries.map { it.path }
              .filter { it.toFile().exists() }
              .distinct()
              .sortedBy(Path::toString),
          binaryRootContexts = (snapshot.moduleOutputs + snapshot.externalBinaries + snapshot.bootBinaries)
              .map { binary ->
                KotlinAnalysisBinaryRootContext(
                    path = binary.path.toAbsolutePath().normalize(),
                    role = binary.role,
                    origin = binary.origin,
                    ownerModulePath = binary.ownerModulePath,
                    variantName = binary.variantName,
                    exists = binary.exists && binary.path.toFile().exists(),
                )
              }
              .distinctBy { it.path to it.role to it.origin to it.ownerModulePath to it.variantName }
              .sortedWith(compareBy({ it.path.toString() }, { it.role.name }, { it.origin.name })),
          libraryAttachments = snapshot.libraryAttachments.map { attachment ->
            KotlinAnalysisLibraryAttachmentContext(
                libraryIdentity = attachment.libraryIdentity,
                binaryPaths = attachment.binaryPaths.map { it.toAbsolutePath().normalize() },
                sourcePaths = attachment.sourceArchives.map { it.path.toAbsolutePath().normalize() },
                javadocPaths = attachment.javadocArchives.map { it.path.toAbsolutePath().normalize() },
                ownerModulePath = attachment.ownerModulePath,
            )
          }.sortedBy { it.libraryIdentity },
          javaSourceVersion = snapshot.compilerSettings.javaSourceVersion,
          javaBytecodeVersion = snapshot.compilerSettings.javaBytecodeVersion,
      )
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
