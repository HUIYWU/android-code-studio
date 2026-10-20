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

package com.tom.rv2ide.projects.internal

import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.java.JavaModule
import com.tom.rv2ide.tooling.api.IAndroidProject
import com.tom.rv2ide.tooling.api.IGradleProject
import com.tom.rv2ide.tooling.api.IJavaProject
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.ProjectType
import com.tom.rv2ide.tooling.api.models.AndroidProjectMetadata
import com.tom.rv2ide.tooling.api.models.BasicProjectMetadata
import com.tom.rv2ide.tooling.api.models.CompositeBuildDescriptor
import com.tom.rv2ide.tooling.api.models.GradleTask
import com.tom.rv2ide.tooling.api.models.JavaContentRoot
import com.tom.rv2ide.tooling.api.models.JavaModuleCompilerSettings
import com.tom.rv2ide.tooling.api.models.JavaProjectMetadata
import com.tom.rv2ide.tooling.api.models.JavaSourceDirectory
import com.tom.rv2ide.tooling.api.models.params.StringParameter
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Transforms project models from tooling API to the projects API.
 *
 * @author Akash Yadav
 */
internal object WorkspaceModelBuilder {

  private val log = LoggerFactory.getLogger(WorkspaceModelBuilder::class.java)

  fun build(
    projectDir: File,
    project: IProject
  ): WorkspaceImpl? {
    try {
      val allProjects = project.getProjects().get()
      val selectionResult = project.selectProject(StringParameter("")).get()
      check(selectionResult.isSuccessful) {
        "Cannot find root project"
      }

      val rootProject = when (project.getType().get()) {
        ProjectType.Gradle -> transform(project.asGradleProject())
        ProjectType.Android -> transform(project.asAndroidProject())
        else -> throw IllegalStateException(
          "Root project must be either an Android project or a Gradle project"
        )
      }

      val transformedProjects = CopyOnWriteArrayList(transform(allProjects, project))
      if (transformedProjects.none { it.path.startsWith(":buildDeps:") }) {
        // Prefer tooling-provided composite descriptors whenever available.
        // This keeps discovery responsibility in tooling while still allowing workspace to preserve
        // the existing pseudo-module materialization strategy. The filesystem scan remains as a
        // compatibility fallback until composite build modeling is fully formalized end-to-end.
        val compositeDescriptors = project.getCompositeBuildDescriptors().get()
        if (compositeDescriptors.isNotEmpty()) {
          augmentWithCompositeBuildDescriptors(compositeDescriptors, transformedProjects)
        } else {
          augmentWithCompositeBuildDeps(projectDir, transformedProjects)
        }
      }
    log.info(
      "WorkspaceModelBuilder.build workspaceDir={} rootProject={} allProjects={} transformedProjects={}",
      projectDir.canonicalPath,
      rootProject.path,
      allProjects.size,
      transformedProjects.size,
    )

      return WorkspaceImpl(
        projectDir,
        rootProject,
        transformedProjects,
        project.getProjectSyncIssues().get(),
        project.getGradleKotlinDslSnapshot().get(),
      )

    } catch (error: Throwable) {
      log.error("Unable to transform project", error)
      return null
    }
  }

  private fun transform(rootProject: IGradleProject): GradleProject {
    val metadata = rootProject.getMetadata().get()
    return GradleProject(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,

      // The list will never change, we could make these thread-safe with
      // CopyOnWriteArrayList
      tasks = CopyOnWriteArrayList(rootProject.getTasks().get() ?: listOf()),
    )
  }

  // Workspace-level fallback for composite build dependencies.
  //
  // The tooling/root model currently enumerates only the main build modules, so sources under
  // composite-builds/build-deps/* would otherwise be attributed to the root module ':' and lose
  // their own source index. To keep Java LSP diagnostics/source lookup working for these checked-in
  // dependency sources, synthesize lightweight Java modules from their src/main/java roots.
  private fun augmentWithCompositeBuildDeps(
    workspaceDir: File,
    projects: MutableList<GradleProject>,
  ) {
    val buildDepsDir = File(workspaceDir, "composite-builds/build-deps")
    if (!buildDepsDir.isDirectory) {
      log.info("WorkspaceModelBuilder.compositeBuildDeps skipped missingDir={}", buildDepsDir.path)
      return
    }

    val fallbackCompilerSettings = projects
      .filterIsInstance<JavaModule>()
      .firstOrNull { it.path == ":" }
      ?.compilerSettings
      ?: JavaModuleCompilerSettings()

    val descriptors = buildDepsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.mapNotNull { depDir ->
      val buildScript = File(depDir, "build.gradle.kts").takeIf { it.isFile }
        ?: File(depDir, "build.gradle").takeIf { it.isFile }
      val sourceRoots = discoverCompositeSourceRoots(depDir, buildScript)
      if (sourceRoots.isEmpty()) {
        return@mapNotNull null
      }
      val sourceRootKinds = sourceRoots.associate { it.canonicalPath to classifyCompositeSourceRoot(it, depDir.canonicalFile, buildScript) }
      CompositeBuildDescriptor(
        name = depDir.name,
        buildName = "buildDeps",
        projectPath = ":buildDeps:${depDir.name}",
        projectDir = depDir.canonicalFile,
        buildDir = File(depDir, "build"),
        buildScript = buildScript,
        sourceRoots = sourceRoots,
        sourceRootKinds = sourceRootKinds,
        classesJar = discoverCompositeClassesJar(depDir, File(depDir, "build")),
        javaSourceVersion = fallbackCompilerSettings.javaSourceVersion,
        javaBytecodeVersion = fallbackCompilerSettings.javaBytecodeVersion,
        isHeavy = isHeavyCompositeBuildDep(depDir.name),
      )
    } ?: emptyList()

    augmentWithCompositeBuildDescriptors(descriptors, projects, logLabel = "compositeBuildDeps")
  }

    // Materialize composite build descriptors as pseudo Java modules.
  // At this stage we intentionally reuse the existing workspace-side JavaModule shape and root
  // compiler settings so descriptor-based discovery can replace directory scanning without forcing
  // a larger identity / dependency graph refactor in the same change.
  private fun augmentWithCompositeBuildDescriptors(
    descriptors: List<CompositeBuildDescriptor>,
    projects: MutableList<GradleProject>,
    logLabel: String = "compositeBuildDescriptors",
  ) {
    val rootJavaModule = projects.filterIsInstance<JavaModule>().find { it.path == ":" }
    if (rootJavaModule == null) {
      log.warn("WorkspaceModelBuilder.${logLabel} skipped: root Java module not found")
      return
    }

    val existingDirs = projects.map { it.projectDir.canonicalFile }.toHashSet()
    val added = mutableListOf<String>()
    descriptors.forEach { descriptor ->
      val canonicalDepDir = descriptor.projectDir.canonicalFile
      if (existingDirs.contains(canonicalDepDir)) {
        return@forEach
      }

      val contentRoot = JavaContentRoot().apply {
        (sourceDirectories as MutableList).addAll(
          descriptor.sourceRoots.map { sourceRoot ->
            JavaSourceDirectory(sourceRoot, isGeneratedCompositeSourceRoot(sourceRoot, descriptor.projectDir))
          }
        )
      }
      val pseudoModule = JavaModule(
        name = descriptor.name,
        description = "Composite build dependency module",
        path = descriptor.projectPath,
        projectDir = descriptor.projectDir,
        buildDir = descriptor.buildDir,
        buildScript = descriptor.buildScript
          ?: File(descriptor.projectDir, "build.gradle.kts")
            .takeIf { it.isFile }
          ?: File(descriptor.projectDir, "build.gradle"),
        tasks = emptyList<GradleTask>(),
        compilerSettings = JavaModuleCompilerSettings(
          descriptor.javaSourceVersion,
          descriptor.javaBytecodeVersion,
        ),
        contentRoots = listOf(contentRoot),
        dependencies = emptyList(),
        classesJar = descriptor.classesJar,
        inheritedBootClassPaths = rootJavaModule.inheritedBootClassPaths,
      ).apply {
        markLazyCompositeBuildModule(descriptor.isHeavy)
      }
      projects.add(pseudoModule)
      existingDirs.add(canonicalDepDir)
      added.add("${pseudoModule.path} -> ${canonicalDepDir.path}")
    }

    val classesJarDescriptors = descriptors.filter { it.classesJar?.isFile == true }
    val sourceRootKindCounts = descriptors.flatMap { it.sourceRootKinds.values }.groupingBy { it }.eachCount()
    val sourceRootKindSamples = descriptors
      .flatMap { descriptor -> descriptor.sourceRootKinds.entries.map { entry -> "${descriptor.projectPath} -> ${entry.value} -> ${entry.key}" } }
      .take(20)
    val scriptMappedSamples = descriptors
      .flatMap { descriptor -> descriptor.sourceRootKinds.entries.map { entry -> "${descriptor.projectPath} -> ${entry.value} -> ${entry.key}" } }
      .filter { it.contains(" -> SCRIPT_MAPPED -> ") }
      .take(20)
    log.info(
      "WorkspaceModelBuilder.${logLabel} addedCount={} added={}",
      added.size,
      added,
    )
    log.info(
      "WorkspaceModelBuilder.${logLabel}.classesJarSummary count={} hits={}",
      classesJarDescriptors.size,
      classesJarDescriptors.take(20).map { "${it.projectPath} -> ${it.classesJar?.path}" },
    )
    log.info(
      "WorkspaceModelBuilder.${logLabel}.sourceRootKindSummary counts={} samples={} scriptMappedSamples={}",
      sourceRootKindCounts,
      sourceRootKindSamples,
      scriptMappedSamples,
    )
    // logback-core temporary debug removed after SCRIPT_MAPPED verification
  }
  private fun discoverCompositeClassesJar(depDir: File, buildDir: File): File? {
    val directJar = File(buildDir, "libs/${depDir.name}.jar")
    if (directJar.isFile) {
      return directJar.canonicalFile
    }

    File(buildDir, "libs")
      .listFiles()
      ?.filter { it.isFile && it.extension == "jar" }
      ?.sortedBy { it.name }
      ?.firstOrNull { it.nameWithoutExtension == depDir.name || it.name.startsWith("${depDir.name}-") }
      ?.let { return it.canonicalFile }

    buildDir.walkTopDown()
      .maxDepth(5)
      .filter { it.isFile && it.name == "classes.jar" && it.path.replace('\\', '/').contains("/intermediates/") }
      .firstOrNull()
      ?.let { return it.canonicalFile }

    return null
  }
  private fun discoverCompositeSourceRoots(depDir: File, buildScript: File?): List<File> {

    val roots = linkedSetOf<File>()

    listOf(
      File(depDir, "src/main/java"),
      File(depDir, "src/main/kotlin"),
    ).filterTo(roots) { it.isDirectory }

    val generatedBase = File(depDir, "build/generated")
    if (generatedBase.isDirectory) {
      generatedBase.walkTopDown()
        .maxDepth(4)
        .filter { it.isDirectory }
        .filter {
          val path = it.path.replace('\\', '/')
          path.contains("/build/generated/") &&
            (path.endsWith("/java") || path.endsWith("/kotlin") || path.contains("/ksp/") || path.contains("/kapt/") || path.contains("/annotationProcessor/"))
        }
        .forEach { roots.add(it) }
    }

    roots.addAll(extractCompositeSourceRootsFromScript(depDir, buildScript))
    return roots.toList()
  }
  private fun extractCompositeSourceRootsFromScript(depDir: File, buildScript: File?): List<File> {

    val script = buildScript?.takeIf { it.isFile }?.readText() ?: return emptyList()
    val resolvedVars = mutableMapOf<String, File>()

    val scriptLines = script.lineSequence().toList()
    var lineIndex = 0
    while (lineIndex < scriptLines.size) {
      val line = scriptLines[lineIndex]
      val match = Regex("""^\s*val\s+(\w+)\s*=\s*(.*)$""").find(line)
      if (match != null) {
        val varName = match.groupValues[1]
        val exprParts = mutableListOf<String>()
        val firstExpr = match.groupValues[2].trim()
        if (firstExpr.isNotBlank()) {
          exprParts.add(firstExpr)
        }

        var nextIndex = lineIndex + 1
        while (nextIndex < scriptLines.size) {
          val nextLineRaw = scriptLines[nextIndex]
          val nextLine = nextLineRaw.trim()
          if (nextLine.isBlank()) break
          if (Regex("""^(val\s+\w+\s*=|(?:java|kotlin)\.(?:srcDir|srcDirs|setSrcDirs)\(|if\s*\(|else\b|for\s*\(|while\s*\(|when\s*\(|[A-Za-z_][\w.]*\s*=|//)""").containsMatchIn(nextLine)) {
            break
          }
          exprParts.add(nextLine)
          nextIndex++
        }

        val expr = exprParts.joinToString(" ").trim()
        resolveCompositeSourceRootToken(depDir, expr, resolvedVars)?.let { resolvedVars[varName] = it }
      }
      lineIndex++
    }

    val roots = linkedSetOf<File>()
    Regex("""(?:java|kotlin)\.(?:srcDir|srcDirs|setSrcDirs)\((.*?)\)(?=\R\s*val\s+|\R\s*(?:java|kotlin)\.(?:srcDir|srcDirs|setSrcDirs)\(|\R\s*}|$)""", setOf(RegexOption.DOT_MATCHES_ALL))
      .findAll(script)
      .forEach { match ->
        match.groupValues[1]
          .lineSequence()
          .map { it.trim() }
          .filter { it.isNotBlank() }
          .joinToString(" ")
          .removePrefix("listOf(")
          .removeSuffix(")")
          .split(',')
          .map { it.trim() }
          .filter { it.isNotBlank() }
          .forEach { token ->
            resolveCompositeSourceRootToken(depDir, token, resolvedVars)
              ?.takeIf { it.isDirectory }
              ?.let { roots.add(it) }
          }
      }

    return roots.toList()
  }
  private fun resolveCompositeSourceRootToken(
    depDir: File,
    token: String,
    resolvedVars: Map<String, File>,
  ): File? {
    val normalized = token.trim()
    resolvedVars[normalized]?.let { return it }
    val compositeRootDir = depDir.parentFile?.canonicalFile ?: depDir.canonicalFile

    fun extractSingleArg(pattern: Regex): String? {
      val match = pattern.find(normalized) ?: return null
      return match.groupValues[1].ifBlank { match.groupValues[2] }
    }

    val rootProjectResolve = extractSingleArg(
      Regex("""rootProject\.projectDir\.resolve\((?:\"([^\"]+)\"|'([^']+)')\)""")
    )
    if (!rootProjectResolve.isNullOrBlank()) {
      return compositeRootDir.resolve(rootProjectResolve).canonicalFile
    }

    val rootProjectFile = extractSingleArg(
      Regex("""rootProject\.file\((?:\"([^\"]+)\"|'([^']+)')\)""")
    )
    if (!rootProjectFile.isNullOrBlank()) {
      return compositeRootDir.resolve(rootProjectFile).canonicalFile
    }


    val projectFile = extractSingleArg(
      Regex("""project\.file\((?:\"([^\"]+)\"|'([^']+)')\)""")
    )
    if (!projectFile.isNullOrBlank()) {
      return File(depDir, projectFile).canonicalFile
    }

    val localFile = extractSingleArg(
      Regex("""file\((?:\"([^\"]+)\"|'([^']+)')\)""")
    )
    if (!localFile.isNullOrBlank()) {
      return File(depDir, localFile).canonicalFile
    }

    val directLiteral = Regex("""^(?:\"([^\"]+)\"|'([^']+)')$""").find(normalized)
    val literalPath = directLiteral?.groupValues?.get(1).orEmpty().ifBlank { directLiteral?.groupValues?.get(2).orEmpty() }
    if (literalPath.isNotBlank()) {
      return File(depDir, literalPath).canonicalFile
    }

    return null
  }

  private fun classifyCompositeSourceRoot(sourceRoot: File, projectDir: File, buildScript: File?): String {
    if (isGeneratedCompositeSourceRoot(sourceRoot, projectDir)) {
      return "GENERATED"
    }
    val projectPath = projectDir.canonicalPath.replace('\\', '/') + "/"
    val sourcePath = sourceRoot.canonicalPath.replace('\\', '/')
    if (!sourcePath.startsWith(projectPath)) {
      return "SCRIPT_MAPPED"
    }
    return if (buildScript != null) "LOCAL" else "LOCAL"
  }

  private fun isGeneratedCompositeSourceRoot(sourceRoot: File, projectDir: File): Boolean {
    val normalized = sourceRoot.canonicalPath.replace('\\', '/')
    val projectPath = projectDir.canonicalPath.replace('\\', '/')
    return normalized.startsWith("${projectPath}/build/generated/")
  }

  private fun isHeavyCompositeBuildDep(moduleName: String): Boolean {
    return moduleName == "jdk-compiler"
      || moduleName == "java-compiler"
      || moduleName == "jdk-jdeps"
      || moduleName == "jaxp"
  }


  private fun transform(
    project: IAndroidProject
  ): AndroidModule {
    val metadata = project.getMetadata().get() as AndroidProjectMetadata
    val libraryMap = project.getLibraryMap().get()
    val variants = project.getVariants().get()
    val configuredVariant = project.getConfiguredVariant().get()
    return AndroidModule(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,
      tasks = project.getTasks().get(),
      resourcePrefix = metadata.resourcePrefix,
      namespace = metadata.namespace,
      androidTestNamespace = metadata.androidTestNamespace,
      testFixtureNamespace = metadata.testFixtureNamespace,
      projectType = metadata.androidType,
      mainSourceSet = project.getMainSourceSet().get(),
      flags = metadata.flags,
      compilerSettings = metadata.javaCompileOptions,
      viewBindingOptions = metadata.viewBindingOptions,
      bootClassPaths = project.getBootClasspaths().get(),
      libraries = libraryMap.keys,
      libraryMap = libraryMap,
      lintCheckJars = project.getLintCheckJars().get(),
      variants = variants,
      configuredVariant = variants.find { it.name == configuredVariant },
      classesJar = metadata.classesJar
    )
  }

  private fun transform(project: IJavaProject, inheritedBootClassPaths: Collection<File>): JavaModule {
    val metadata = project.getMetadata().get() as JavaProjectMetadata
    return JavaModule(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,
      tasks = project.getTasks().get(),
      contentRoots = project.getContentRoots().get(),
      dependencies = project.getDependencies().get(),
      compilerSettings = metadata.compilerSettings,
      classesJar = metadata.classesJar,
      inheritedBootClassPaths = inheritedBootClassPaths,
    )
  }

  private fun transform(modules: List<BasicProjectMetadata>, root: IProject): List<GradleProject> {
    val inheritedBootClassPaths = collectAndroidBootClassPaths(modules, root)
    return mutableListOf<GradleProject>().apply {
      for (module in modules) {
        add(createProject(module, root, inheritedBootClassPaths))
      }
    }
  }
  private fun collectAndroidBootClassPaths(
    modules: List<BasicProjectMetadata>,
    root: IProject
  ): List<File> {
    val bootClasspaths = linkedSetOf<File>()
    for (module in modules) {
      val selectionResult = root.selectProject(StringParameter(module.projectPath)).get()
      if (!selectionResult.isSuccessful) {
        continue
      }

      if (root.getType().get() == ProjectType.Android) {
        bootClasspaths.addAll(root.asAndroidProject().getBootClasspaths().get().filter { it.exists() })
      }
    }
    return bootClasspaths.toList()
  }

  private fun createProject(moduleMetadata: BasicProjectMetadata, root: IProject, inheritedBootClassPaths: Collection<File>): GradleProject {

    val selectionResult = root.selectProject(StringParameter(moduleMetadata.projectPath)).get()
    check(selectionResult.isSuccessful) {
      "Selection failed for project '${moduleMetadata.projectPath}' but it is included in all projects."
    }

    val type = root.getType().get() ?: throw java.lang.IllegalStateException("Invalid module data")

    return when (type) {
      ProjectType.Gradle,
      ProjectType.Unknown -> transform(root.asGradleProject())

      ProjectType.Android -> transform(root.asAndroidProject())
      ProjectType.Java -> transform(root.asJavaProject(), inheritedBootClassPaths)
    }
  }
}
