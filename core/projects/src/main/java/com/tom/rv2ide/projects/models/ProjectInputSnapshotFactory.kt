package com.tom.rv2ide.projects.models

import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.java.JavaModule
import com.tom.rv2ide.builder.model.DefaultLibrary
import com.android.builder.model.v2.ide.LibraryType.ANDROID_LIBRARY
import com.android.builder.model.v2.ide.LibraryType.JAVA_LIBRARY
import com.android.builder.model.v2.ide.LibraryType.PROJECT
import com.tom.rv2ide.tooling.api.models.BuildVariantInfo
import com.tom.rv2ide.tooling.api.models.JavaModuleExternalDependency
import com.tom.rv2ide.tooling.api.models.JavaModuleProjectDependency
import java.io.File
import java.nio.file.Path

public object ProjectInputSnapshotFactory {
  private val SYNTHETIC_MISSING_JAR_NAMES = setOf(
      "does-not-exist.jar",
      "module-jar-does-not-exist.jar",
  )

  public fun capture(workspace: IWorkspace): ProjectInputSnapshot {
    val modules = collectModules(workspace)
    val variantSelections = workspace.getAndroidVariantSelections()
    val outputPaths = modules
        .flatMap { module -> moduleOutputFiles(module, variantSelections) }
        .map { it.snapshotPath() }
        .toSet()
    val bootPaths = modules.flatMap { bootFiles(it) }.map { it.snapshotPath() }.toSet()

    val snapshots = modules.map { module ->
      captureModule(module, variantSelections, outputPaths, bootPaths)
    }

    return ProjectInputSnapshot(
        workspaceRoot = workspace.getProjectDir().snapshotPath(),
        modules = snapshots.sortedBy { it.identity.modulePath },
        gradleKotlinDslModel = workspace.getGradleKotlinDslModel().immutableCopy(),
    )
  }

  private fun captureModule(
      module: ModuleProject,
      variantSelections: Map<String, BuildVariantInfo>,
      allOutputPaths: Set<Path>,
      allBootPaths: Set<Path>,
  ): ProjectModuleInputSnapshot {
    val identity = ProjectModuleIdentity(
        modulePath = module.path,
        kind = moduleKind(module),
        projectDirectory = module.projectDir.snapshotPath(),
        buildDirectory = module.buildDir.snapshotPath(),
    )
    val variant = selectedVariant(module, variantSelections)
    val selectionSnapshot = variantSelection(module, variantSelections)
    val variantArtifact = variant?.let { captureVariantArtifact(it) }

    val roots = captureRoots(module, variant)
    val moduleOutputs = moduleOutputDescriptors(module, variant)
    val externalBinaries = externalBinaryDescriptors(module, allOutputPaths, allBootPaths)
    val bootBinaries = bootBinaryDescriptors(module)
    val libraryAttachments = libraryAttachmentDescriptors(module, externalBinaries.map { it.path }.toSet())

    return ProjectModuleInputSnapshot(
        identity = identity,
        compilerSettings = ProjectCompilerSettingsSnapshot(
            javaSourceVersion = module.compilerSettings.javaSourceVersion,
            javaBytecodeVersion = module.compilerSettings.javaBytecodeVersion,
        ),
        ordinarySourceRoots = roots.ordinary,
        generatedSourceRoots = roots.generated,
        javaSourceRoots = roots.java,
        moduleOutputs = moduleOutputs,
        externalBinaries = externalBinaries,
        bootBinaries = bootBinaries,
        libraryAttachments = libraryAttachments,
        compileModulePaths = module.getCompileModuleProjects()
            .map(ModuleProject::path)
            .distinct()
            .sorted(),
        externalDependencyIdentities = externalDependencyIdentities(module),
        variantSelection = selectionSnapshot,
        variantArtifact = variantArtifact,
    )
  }

  private data class CapturedRoots(
      val ordinary: List<ProjectRootDescriptor>,
      val generated: List<ProjectRootDescriptor>,
      val java: List<ProjectRootDescriptor>,
  )

  private fun captureRoots(
      module: ModuleProject,
      variant: com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata?,
  ): CapturedRoots {
    val ordinary = linkedSetOf<ProjectRootDescriptor>()
    val generated = linkedSetOf<ProjectRootDescriptor>()
    val java = linkedSetOf<ProjectRootDescriptor>()

    when (module) {
      is AndroidModule -> {
        module.mainSourceSet?.sourceProvider?.javaDirectories.orEmpty().forEach { directory ->
          val descriptor = root(
              directory = directory,
              ownerModulePath = module.path,
              role = ProjectRootRole.JAVA_SOURCE,
              origin = ProjectRootOrigin.MAIN_SOURCE_SET,
          )
          java += descriptor
        }
        module.mainSourceSet?.sourceProvider?.kotlinDirectories.orEmpty().forEach { directory ->
          ordinary += root(
              directory = directory,
              ownerModulePath = module.path,
              role = ProjectRootRole.ORDINARY_SOURCE,
              origin = ProjectRootOrigin.MAIN_SOURCE_SET,
          )
        }
        variant?.let { selectedVariant ->
          selectedVariant.mainArtifact.generatedSourceFolders.forEach { directory ->
            generated += root(
                directory = directory,
                ownerModulePath = module.path,
                role = ProjectRootRole.GENERATED_SOURCE,
                origin = ProjectRootOrigin.VARIANT_MAIN_ARTIFACT,
                variantName = selectedVariant.name,
            )
          }
        }
      }
      is JavaModule -> {
        val origin = if (module.path.startsWith(":buildDeps:")) {
          ProjectRootOrigin.COMPOSITE_BUILD
        } else {
          ProjectRootOrigin.JAVA_CONTENT_ROOT
        }
        module.contentRoots.flatMap { it.sourceDirectories }.forEach { sourceDirectory ->
          val descriptor = root(
              directory = sourceDirectory.directory,
              ownerModulePath = module.path,
              role = if (sourceDirectory.isGenerated) {
                ProjectRootRole.GENERATED_SOURCE
              } else {
                ProjectRootRole.JAVA_SOURCE
              },
              origin = origin,
          )
          if (sourceDirectory.isGenerated) {
            generated += descriptor
          } else {
            java += descriptor
          }
        }
      }
      else -> {
        module.getSourceDirectories().forEach { directory ->
          ordinary += root(
              directory = directory,
              ownerModulePath = module.path,
              role = ProjectRootRole.ORDINARY_SOURCE,
              origin = ProjectRootOrigin.UNKNOWN,
          )
        }
      }
    }

    return CapturedRoots(
        ordinary = ordinary.sortedWith(rootComparator),
        generated = generated.sortedWith(rootComparator),
        java = java.sortedWith(rootComparator),
    )
  }

  private fun root(
      directory: File,
      ownerModulePath: String,
      role: ProjectRootRole,
      origin: ProjectRootOrigin,
      variantName: String? = null,
  ): ProjectRootDescriptor {
    return ProjectRootDescriptor(
        path = directory.snapshotPath(),
        ownerModulePath = ownerModulePath,
        role = role,
        origin = origin,
        variantName = variantName,
        exists = directory.exists(),
    )
  }

  private fun captureVariantArtifact(
      variant: com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata,
  ): ProjectVariantArtifactSnapshot {
    val artifact = variant.mainArtifact
    return ProjectVariantArtifactSnapshot(
        variantName = variant.name,
        generatedSourceRoots = artifact.generatedSourceFolders
            .map { it.snapshotPath() }
            .distinct()
            .sortedBy(Path::toString),
        classJars = artifact.classJars
            .map { it.snapshotPath() }
            .distinct()
            .sortedBy(Path::toString),
        sourceGenerationTask = artifact.sourceGenTaskName,
        resourceGenerationTask = artifact.resGenTaskName,
        compileTask = artifact.compileTaskName,
        outputListingFile = artifact.assembleTaskOutputListingFile?.snapshotPath(),
    )
  }

  private fun variantSelection(
      module: ModuleProject,
      selections: Map<String, BuildVariantInfo>,
  ): ProjectVariantSelectionSnapshot? {
    val selection = selections[module.path] ?: return null
    return ProjectVariantSelectionSnapshot(
        projectPath = selection.projectPath,
        selectedVariant = selection.selectedVariant,
        availableVariants = selection.buildVariants.distinct().sorted(),
        versionName = selection.versionName,
        versionCode = selection.versionCode,
        minSdk = selection.minSdk,
        targetSdk = selection.targetSdk,
        compileSdk = selection.compileSdk,
    )
  }

  private fun selectedVariant(
      module: ModuleProject,
      selections: Map<String, BuildVariantInfo>,
  ): com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata? {
    if (module !is AndroidModule) return null
    val selectedName = selections[module.path]?.selectedVariant
    return when {
      selectedName != null -> module.getVariant(selectedName)
      else -> module.configuredVariant
    }
  }

  private fun moduleOutputFiles(
      module: ModuleProject,
      selections: Map<String, BuildVariantInfo>,
  ): List<File> = when (module) {
    is AndroidModule -> buildList {
      module.classesJar?.takeUnless(::isSyntheticMissingJar)?.let(::add)
      selectedVariant(module, selections)?.mainArtifact?.classJars.orEmpty().forEach(::add)
    }
    is JavaModule -> listOfNotNull(module.classesJar?.takeUnless(::isSyntheticMissingJar))
    else -> module.getModuleClasspaths().filter { it.exists() && !isSyntheticMissingJar(it) }
  }

  private fun moduleOutputDescriptors(
      module: ModuleProject,
      variant: com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata?,
  ): List<ProjectBinaryDescriptor> = when (module) {
    is AndroidModule -> buildList {
      if (module.isLibrary) {
        module.classesJar?.takeUnless(::isSyntheticMissingJar)?.let {
          add(binary(it, module.path, ProjectBinaryOrigin.ANDROID_GENERATED_JAR, variant?.name))
        }
      }
      // Application variants still provide R.jar through mainArtifact.classJars; do not filter these with application outputs.
      variant?.let { selected ->
        selected.mainArtifact.classJars.forEach {
          add(binary(it, module.path, ProjectBinaryOrigin.ANDROID_VARIANT_CLASS_JAR, selected.name))
        }
      }
    }
    is JavaModule -> listOfNotNull(
        module.classesJar?.takeUnless(::isSyntheticMissingJar)?.let {
          binary(it, module.path, ProjectBinaryOrigin.JAVA_CLASSES_JAR, null)
        },
    )
    else -> module.getModuleClasspaths()
        .filter { it.exists() && !isSyntheticMissingJar(it) }
        .map { binary(it, module.path, ProjectBinaryOrigin.UNKNOWN, null) }
  }.distinctBy { it.path to it.origin }
      .sortedWith(binaryComparator)

  private fun isSyntheticMissingJar(file: File): Boolean =
      !file.exists() && file.name in SYNTHETIC_MISSING_JAR_NAMES

  private fun externalBinaryDescriptors(
      module: ModuleProject,
      allOutputPaths: Set<Path>,
      allBootPaths: Set<Path>,
  ): List<ProjectBinaryDescriptor> {
    val binaryOrigins = externalBinaryOrigins(module)
    val binaries = runCatching { module.getCompileClasspaths() }.getOrDefault(emptySet())
    return binaries.asSequence()
        .map { it.snapshotPath() }
        .distinct()
        .filter { it !in allOutputPaths && it !in allBootPaths }
        .filterNot { isSyntheticMissingJar(it.toFile()) }
        .map { path ->
          ProjectBinaryDescriptor(
              path = path,
              ownerModulePath = module.path,
              role = ProjectBinaryRole.EXTERNAL_BINARY,
              origin = binaryOrigins[path] ?: ProjectBinaryOrigin.EXTERNAL_JAR,
              variantName = null,
              exists = path.toFile().exists(),
              size = path.toFile().takeIf(File::isFile)?.length(),
              lastModifiedMillis = path.toFile().takeIf(File::exists)?.lastModified(),
          )
        }
        .sortedWith(binaryComparator)
        .toList()
  }

  private fun externalBinaryOrigins(module: ModuleProject): Map<Path, ProjectBinaryOrigin> = when (module) {
    is AndroidModule -> androidBinaryOrigins(module)
    is JavaModule -> module.dependencies
        .filterIsInstance<JavaModuleExternalDependency>()
        .mapNotNull { dependency ->
          dependency.jarFile?.let { it.snapshotPath() to ProjectBinaryOrigin.JAVA_LIBRARY }
        }
        .toMap()
    else -> emptyMap()
  }

  private fun androidBinaryOrigins(module: AndroidModule): Map<Path, ProjectBinaryOrigin> {
    val result = linkedMapOf<Path, ProjectBinaryOrigin>()
    val visited = mutableSetOf<String>()

    fun visit(key: String) {
      if (!visited.add(key)) return
      val library = module.libraryMap[key] ?: return
      when (library.type) {
        PROJECT -> Unit
        ANDROID_LIBRARY -> {
          val artifact = library.artifact
          if (artifact?.name?.endsWith(".aar", ignoreCase = true) == true) {
            result[File(artifact.parentFile, "${artifact.nameWithoutExtension}-acs-extracted/classes.jar").snapshotPath()] =
                ProjectBinaryOrigin.AAR_CLASSES_JAR
          } else {
            artifact?.let { result.putIfAbsent(it.snapshotPath(), ProjectBinaryOrigin.ANDROID_LIBRARY) }
          }
          library.androidLibraryData?.compileJarFiles.orEmpty().forEach { file ->
            result.putIfAbsent(file.snapshotPath(), ProjectBinaryOrigin.ANDROID_LIBRARY)
          }
        }
        JAVA_LIBRARY -> {
          library.artifact?.let { result.putIfAbsent(it.snapshotPath(), ProjectBinaryOrigin.JAVA_LIBRARY) }
        }
        else -> Unit
      }
      library.dependencies.sorted().forEach(::visit)
    }

    module.libraries.sorted().forEach(::visit)
    return result
  }

  private fun libraryAttachmentDescriptors(
      module: ModuleProject,
      externalBinaryPaths: Set<Path>,
  ): List<ProjectLibraryAttachmentDescriptor> {
    val result = mutableListOf<ProjectLibraryAttachmentDescriptor>()
    val visitedModules = mutableSetOf<String>()

    fun addAttachment(
        currentModulePath: String,
        identity: String,
        binaries: Collection<File>,
        sources: Collection<File>,
        javadoc: Collection<File>,
    ) {
      val binaryPaths = binaries.map { it.snapshotPath() }
          .filter { it in externalBinaryPaths }
          .distinct()
          .sortedBy(Path::toString)
      if (binaryPaths.isEmpty() || (sources.isEmpty() && javadoc.isEmpty())) return
      result += ProjectLibraryAttachmentDescriptor(
          libraryIdentity = identity,
          binaryPaths = binaryPaths,
          sourceArchives = libraryArchives(sources),
          javadocArchives = libraryArchives(javadoc),
          ownerModulePath = currentModulePath,
      )
    }

    fun collect(current: ModuleProject) {
      if (!visitedModules.add(current.path)) return
      when (current) {
        is AndroidModule -> {
          val visitedLibraries = mutableSetOf<String>()
          fun visit(key: String) {
            if (!visitedLibraries.add(key)) return
            val library = current.libraryMap[key] ?: return
            if (library.type == ANDROID_LIBRARY || library.type == JAVA_LIBRARY) {
              addAttachment(
                  currentModulePath = current.path,
                  identity = "android|$key",
                  binaries = androidLibraryBinaryFiles(library),
                  sources = listOfNotNull(library.srcJar) + library.srcJars,
                  javadoc = listOfNotNull(library.docJar),
              )
            }
            library.dependencies.sorted().forEach(::visit)
          }
          current.libraries.sorted().forEach(::visit)
        }
        is JavaModule -> current.dependencies.filterIsInstance<JavaModuleExternalDependency>().forEach { dependency ->
          val binary = dependency.jarFile ?: return@forEach
          addAttachment(
              currentModulePath = current.path,
              identity = "java|${binary.snapshotPath()}",
              binaries = listOf(binary),
              sources = listOfNotNull(dependency.sources),
              javadoc = listOfNotNull(dependency.javadoc),
          )
        }
      }
      current.getCompileModuleProjects().sortedBy { it.path }.forEach(::collect)
    }

    collect(module)
    return result.distinct().sortedWith(compareBy({ it.libraryIdentity }, { it.binaryPaths.joinToString("|") }))
  }

  private fun androidLibraryBinaryFiles(library: DefaultLibrary): List<File> = when (library.type) {
    ANDROID_LIBRARY -> buildList {
      library.artifact?.let { artifact ->
        if (artifact.extension.equals("aar", ignoreCase = true)) {
          add(File(artifact.parentFile, "${artifact.nameWithoutExtension}-acs-extracted/classes.jar"))
        } else {
          add(artifact)
        }
      }
      addAll(library.androidLibraryData?.compileJarFiles.orEmpty())
    }
    JAVA_LIBRARY -> listOfNotNull(library.artifact)
    else -> emptyList()
  }

  private fun libraryArchives(files: Collection<File>): List<ProjectLibraryArchiveDescriptor> =
      files.map { it.snapshotPath() }.distinct().sortedBy(Path::toString).map { path ->
        val file = path.toFile()
        ProjectLibraryArchiveDescriptor(
            path = path,
            exists = file.exists(),
            size = file.takeIf(File::isFile)?.length(),
            lastModifiedMillis = file.takeIf(File::exists)?.lastModified(),
        )
      }

  private fun bootBinaryDescriptors(module: ModuleProject): List<ProjectBinaryDescriptor> = when (module) {
    is AndroidModule -> module.bootClassPaths.map {
      binary(it, module.path, ProjectBinaryOrigin.ANDROID_BOOT, null, ProjectBinaryRole.BOOT_BINARY)
    }
    is JavaModule -> module.inheritedBootClassPaths.map {
      binary(it, module.path, ProjectBinaryOrigin.JAVA_INHERITED_BOOT, null, ProjectBinaryRole.BOOT_BINARY)
    }
    else -> emptyList()
  }.distinctBy(ProjectBinaryDescriptor::path)
      .sortedWith(binaryComparator)

  private fun binary(
      file: File,
      ownerModulePath: String?,
      origin: ProjectBinaryOrigin,
      variantName: String?,
      role: ProjectBinaryRole = ProjectBinaryRole.MODULE_OUTPUT,
  ): ProjectBinaryDescriptor {
    val path = file.snapshotPath()
    val actualFile = path.toFile()
    return ProjectBinaryDescriptor(
        path = path,
        ownerModulePath = ownerModulePath,
        role = role,
        origin = origin,
        variantName = variantName,
        exists = actualFile.exists(),
        size = actualFile.takeIf(File::isFile)?.length(),
        lastModifiedMillis = actualFile.takeIf(File::exists)?.lastModified(),
    )
  }

  private fun externalDependencyIdentities(module: ModuleProject): List<String> = when (module) {
    is AndroidModule -> androidDependencyIdentities(module)
    is JavaModule -> module.dependencies.map { dependency ->
      when (dependency) {
        is JavaModuleProjectDependency ->
            "java|project|${dependency.projectPath}|scope=${dependency.scope}|exported=${dependency.exported}"
        is JavaModuleExternalDependency ->
            "java|external|${dependency.jarFile?.snapshotPath()}|scope=${dependency.scope}|exported=${dependency.exported}|catalog=${dependency.catalogReference.orEmpty()}"
        else ->
            "java|dependency|${dependency.jarFile?.snapshotPath()}|scope=${dependency.scope}|exported=${dependency.exported}"
      }
    }.distinct().sorted()
    else -> emptyList()
  }

  private fun androidDependencyIdentities(module: AndroidModule): List<String> {
    val result = linkedSetOf<String>()
    val visited = mutableSetOf<String>()

    fun visit(key: String) {
      if (!visited.add(key)) return
      val library = module.libraryMap[key] ?: return
      val projectPath = library.projectInfo?.projectPath.orEmpty()
      result += "android|${library.type.name}|$key|project=$projectPath|artifact=${library.artifact?.snapshotPath()}"
      library.dependencies.sorted().forEach(::visit)
    }

    module.libraries.sorted().forEach(::visit)
    return result.sorted()
  }

  private fun collectModules(workspace: IWorkspace): List<ModuleProject> {
    val directModules = buildList {
      add(workspace.getRootProject())
      addAll(workspace.getSubProjects())
    }.filterIsInstance<ModuleProject>()
    val collected = linkedMapOf<String, ModuleProject>()

    fun collect(module: ModuleProject) {
      if (collected.putIfAbsent(module.path, module) != null) return
      module.getCompileModuleProjects().forEach(::collect)
    }

    directModules.forEach(::collect)
    return collected.values.sortedBy(ModuleProject::path)
  }

  private fun bootFiles(module: ModuleProject): List<File> = when (module) {
    is AndroidModule -> module.bootClassPaths.toList()
    is JavaModule -> module.inheritedBootClassPaths.toList()
    else -> emptyList()
  }

  private fun moduleKind(module: ModuleProject): ProjectModuleKind = when (module) {
    is AndroidModule -> ProjectModuleKind.ANDROID
    is JavaModule -> ProjectModuleKind.JAVA
    else -> ProjectModuleKind.OTHER
  }

  private val rootComparator = compareBy<ProjectRootDescriptor>(
      { it.path.toString() },
      { it.ownerModulePath },
      { it.role.name },
      { it.origin.name },
      { it.variantName.orEmpty() },
  )

  private val binaryComparator = compareBy<ProjectBinaryDescriptor>(
      { it.path.toString() },
      { it.ownerModulePath.orEmpty() },
      { it.role.name },
      { it.origin.name },
      { it.variantName.orEmpty() },
  )

  private fun File.snapshotPath(): Path = runCatching {
    canonicalFile.toPath().normalize()
  }.getOrElse {
    absoluteFile.toPath().normalize()
  }
}
