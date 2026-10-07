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

import com.tom.rv2ide.lsp.kotlin.compiler.KotlinCompilerService
import com.tom.rv2ide.projects.util.RuntimeProbe
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.classpath.ClassInfo
import com.tom.rv2ide.projects.classpath.IClasspathReader
import com.tom.rv2ide.projects.classpath.ZipFileClasspathReader
import java.io.File
import java.util.zip.ZipFile

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class KotlinClasspathProvider {

  private var compilerService: KotlinCompilerService? = null
  private val classpathReader: IClasspathReader = ZipFileClasspathReader()
  private val classpathReaderMarker = "ACS_MARKER_KOTLIN_CLASSPATH_PROVIDER_ZIP_V1"

  private var cachedClasspathList: List<String>? = null
  private var cachedClasspath: String? = null

  private val enableGradleCacheScriptingFallback: Boolean
    get() = System.getProperty("androidcodestudio.kls.enableGradleCacheScriptingFallback", "false").toBoolean()


  fun initialize(service: KotlinCompilerService?) {
    this.compilerService = service
    // Clear cache on re-initialization
    cachedClasspathList = null
    cachedClasspath = null
  }
  fun hasMissingCriticalAndroidGeneratedSources(): Boolean {
    return try {
      val workspace = IProjectManager.getInstance().getWorkspace() ?: return false
      val allProjects = mutableListOf(workspace.getRootProject())
      allProjects.addAll(workspace.getSubProjects())

      allProjects.filterIsInstance<AndroidModule>().any { module ->
        val variant = module.getSelectedVariant() ?: return@any true
        val buildDir = module.buildDir
        if (!buildDir.exists()) {
          KslLogs.info("Critical Android generated sources missing: build directory does not exist for module {} -> {}", module.path, buildDir.absolutePath)
          return@any true
        }

        val expectedJavaRoots = collectCriticalGeneratedJavaRoots(module, variant.name)
        if (expectedJavaRoots.isEmpty()) {
          KslLogs.info("Critical Android generated sources missing for module {}: no AGP generated source roots are currently available", module.path)
          return@any true
        }

        val missing = expectedJavaRoots.firstOrNull { !it.exists() }
        if (missing != null) {
          KslLogs.info("Critical Android generated sources missing for module {} -> {}", module.path, missing.absolutePath)
          true
        } else {
          false
        }
      }
    } catch (e: Exception) {
      KslLogs.warn("Failed to evaluate critical Android generated sources state", e)
      true
    }
  }

  private fun collectCriticalGeneratedJavaRoots(module: AndroidModule, variantName: String): List<File> {
    val selectedVariant = module.getSelectedVariant()
    val generatedFromModel = selectedVariant?.mainArtifact?.generatedSourceFolders
      ?.filter { it.exists() && it.isDirectory }
      ?.filter(::isLikelyAgpGeneratedSourceDir)
      .orEmpty()

    val generatedCandidates = collectGeneratedSourceCandidates(module, variantName)
      .filter(::isLikelyAgpGeneratedSourceDir)
      .filter { it.exists() && it.isDirectory }

    // Model-provided generated roots are authoritative when available, but they may still reflect
    // the pre-warmup project model during initialization. Merge them with filesystem candidates so
    // generated symbols such as ActivityMainBinding remain visible as soon as the warm-up tasks have
    // materialized their outputs on disk.
    return (generatedFromModel + generatedCandidates)
      .distinctBy { it.absolutePath }
  }

  private fun isLikelyAgpGeneratedSourceDir(dir: File): Boolean {
    val normalized = dir.absolutePath.replace('\\', '/').lowercase()
    return normalized.contains("/build/generated/") ||
      normalized.contains("/build/intermediates/")
  }

  private fun isClasspathRelevantGeneratedDir(dir: File): Boolean {
    val normalized = dir.absolutePath.replace('\\', '/').lowercase()
    return normalized.contains("/build/generated/source/") ||
      normalized.contains("/build/generated/data_binding_base_class_source_out/") ||
      normalized.contains("/build/generated/ap_generated_sources/") ||
      normalized.contains("/build/generated/aidl_source_output_dir/") ||
      normalized.contains("/build/generated/res/resvalues/") ||
      normalized.contains("/build/intermediates/packaged_res/") ||
      normalized.contains("/build/intermediates/merged_res/") ||
      normalized.contains("/build/tmp/kapt3/classes/")
  }

  private fun collectGeneratedSourceCandidates(module: AndroidModule, variantName: String): List<File> {
    val buildDir = module.buildDir
    val variantLower = variantName.lowercase()
    val variantCapitalized = variantName.replaceFirstChar {
      if (it.isLowerCase()) it.titlecase() else it.toString()
    }

    return listOf(
      // R / resource-generated Java sources
      File(buildDir, "generated/source/r/$variantLower"),
      File(buildDir, "generated/not_namespaced_r_class_sources/$variantLower/r"),
      File(buildDir, "generated/not_namespaced_r_class_sources/$variantLower/process${variantCapitalized}Resources/r"),

      // BuildConfig
      File(buildDir, "generated/source/buildConfig/$variantLower"),

      // Data/View binding and general generated Java sources
      File(buildDir, "generated/data_binding_base_class_source_out/$variantLower/out"),
      File(buildDir, "generated/source/dataBinding/$variantLower"),
      File(buildDir, "generated/source/viewBinding/$variantLower"),
      File(buildDir, "generated/ap_generated_sources/$variantLower/out"),

      // AIDL / KAPT / annotation processing
      File(buildDir, "generated/aidl_source_output_dir/$variantLower/out"),
      File(buildDir, "generated/source/aidl/$variantLower"),
      File(buildDir, "generated/source/kapt/$variantLower"),
      File(buildDir, "generated/source/kaptKotlin/$variantLower"),
      File(buildDir, "tmp/kapt3/classes/$variantLower"),

      // Other AGP generators
      File(buildDir, "generated/source/rs/$variantLower"),
      File(buildDir, "generated/source/navigation-args/$variantLower"),
      File(buildDir, "generated/res/resValues/$variantLower"),
      File(buildDir, "intermediates/packaged_res/$variantLower/package${variantCapitalized}Resources"),
      File(buildDir, "intermediates/merged_res/$variantLower/merge${variantCapitalized}Resources"),
      File(buildDir, "generated/assets"),
    ).distinct()
  }
  fun getClasspath(): String {
    if (cachedClasspath != null) {
      return cachedClasspath!!
    }
    cachedClasspath = getClasspathList().joinToString(":")
    return cachedClasspath!!
  }

  fun getJavaSourceRootsList(): List<String> {
    return try {
      val workspace = IProjectManager.getInstance().getWorkspace() ?: return emptyList()
      val sourceRoots = linkedSetOf<String>()

      val allProjects = mutableListOf(workspace.getRootProject())
      allProjects.addAll(workspace.getSubProjects())

      allProjects.filterIsInstance<ModuleProject>().forEach { project ->
        when (project) {
          is AndroidModule -> {
            project.mainSourceSet?.sourceProvider?.javaDirectories
              ?.filter(File::exists)
              ?.forEach { sourceRoots.add(it.absolutePath) }

            val variantName = project.getSelectedVariant()?.name
            val generatedFromModel = project.getSelectedVariant()?.mainArtifact?.generatedSourceFolders
              ?.filter(File::exists)
              ?.filter(::isLikelyAgpGeneratedSourceDir)
              .orEmpty()

            val generatedCandidates = if (variantName != null) {
              collectGeneratedSourceCandidates(project, variantName)
                .filter(File::exists)
                .filter(::isLikelyAgpGeneratedSourceDir)
            } else {
              emptyList()
            }

            (generatedFromModel + generatedCandidates)
              .distinctBy { it.absolutePath }
              .forEach { sourceRoots.add(it.absolutePath) }
          }
          is com.tom.rv2ide.projects.java.JavaModule -> {
            project.getCompileSourceDirectories()
              .filter(File::exists)
              .forEach { sourceRoots.add(it.absolutePath) }
          }
          else -> {
            project.getCompileSourceDirectories()
              .filter(File::exists)
              .filter(::containsJavaSources)
              .forEach { sourceRoots.add(it.absolutePath) }
          }
        }
      }

      sourceRoots.toList()
    } catch (e: Exception) {
      KslLogs.error("Failed to get Java source roots from project system", e)
      emptyList()
    }
  }

  private fun containsJavaSources(dir: File): Boolean =
    dir.isDirectory && dir.walkTopDown().maxDepth(2).any { it.isFile && it.extension.equals("java", ignoreCase = true) }

  fun getClasspathList(): List<String> {
    if (cachedClasspathList != null) {
      return cachedClasspathList!!
    }


    val classpaths = mutableSetOf<String>()
    val compilerServicePaths = mutableListOf<String>()
    val projectDerivedFallbackAdded = mutableSetOf<String>()
    // Keep provenance only for this collection pass. It is diagnostic data used to
    // distinguish project dependencies from Kotlin compiler/tooling implementation jars.
    val classpathOrigins = mutableMapOf<String, MutableSet<String>>()

    fun recordClasspathOrigin(file: File, origin: String) {
      classpathOrigins.getOrPut(file.absolutePath) { mutableSetOf() }.add(origin)
    }

    fun recordNewClasspathOrigins(before: Set<String>, origin: String) {
      (classpaths - before).forEach { path ->
        classpathOrigins.getOrPut(path) { mutableSetOf() }.add(origin)
      }
    }

    // First, try to get classpaths from the compiler service
    val service = compilerService
    if (service != null) {
      try {
        val allClassPaths = service.getFileManager().getAllClassPaths()
        for (cp in allClassPaths) {
          compilerServicePaths.add(cp.absolutePath)
          addClasspathEntry(cp, classpaths)
          recordClasspathOrigin(cp, "compilerService")
        }
      } catch (e: Exception) {
        KslLogs.error("Failed to get classpath from compiler service", e)
      }
    }

    // Then, enhance with project system classpaths
    try {
      val projectManager = IProjectManager.getInstance()
      val workspace = projectManager.getWorkspace()

      if (workspace != null) {
        // Get all projects (root + subprojects)
        val allProjects = mutableListOf(workspace.getRootProject())
        allProjects.addAll(workspace.getSubProjects())

        for (project in allProjects) {
          if (project is ModuleProject) {
            // Add compile classpaths from each module
            val compileClasspaths = project.getCompileClasspaths()
            for (cp in compileClasspaths) {
              addClasspathEntry(cp, classpaths)
              recordClasspathOrigin(cp, "moduleCompile:${project.path}")
            }

            // Add module-specific classpaths (includes external dependencies)
            val moduleClasspaths = project.getModuleClasspaths()
            for (cp in moduleClasspaths) {
              addClasspathEntry(cp, classpaths)
              recordClasspathOrigin(cp, "moduleDirect:${project.path}")
            }

            // If it's an Android module, add additional Android-specific classpaths
            if (project is AndroidModule) {
              // Add boot classpaths (android.jar, etc.)
              for (bootCp in project.bootClassPaths) {
                addClasspathEntry(bootCp, classpaths)
                recordClasspathOrigin(bootCp, "androidBoot:${project.path}")
              }

              // resolveVersionCatalogDependencies(project, classpaths)

              // Add generated jar
              val generatedJar = project.getGeneratedJar()
              if (generatedJar.exists()) {
                addClasspathEntry(generatedJar, classpaths)
                recordClasspathOrigin(generatedJar, "androidGeneratedJar:${project.path}")
                KslLogs.info("Added generated JAR: {}", generatedJar.absolutePath)
              }

              // Add selected variant's class jars
              val variant = project.getSelectedVariant()
              if (variant != null) {
                for (classJar in variant.mainArtifact.classJars) {
                  addClasspathEntry(classJar, classpaths)
                  recordClasspathOrigin(classJar, "androidVariantJar:${project.path}")
                }
              }

              val beforeAndroidGenerated = classpaths.toSet()
              addAndroidGeneratedSources(project, classpaths)
              recordNewClasspathOrigins(beforeAndroidGenerated, "androidGenerated:${project.path}")
              projectDerivedFallbackAdded.addAll(classpaths.toSet() - beforeAndroidGenerated)
            }
          }
        }
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to get classpath from project system", e)
    }

    val beforeScriptingFallback = classpaths.toSet()
    if (enableGradleCacheScriptingFallback) {
      addKotlinScriptingJarsFromGradleCache(classpaths)
      recordNewClasspathOrigins(beforeScriptingFallback, "gradleScriptingFallback")
    } else {
      KslLogs.debug("Gradle cache scripting fallback disabled")
    }
    val scriptingFallbackAdded = classpaths.toSet() - beforeScriptingFallback

    val existingPaths = classpaths.filter { File(it).exists() }.toList()
    logCompilerServiceClasspathDiff(compilerServicePaths, existingPaths)
    logClasspathLayerSummary(existingPaths, projectDerivedFallbackAdded, scriptingFallbackAdded)
    logCompilerToolingClasspathOrigins(existingPaths, classpathOrigins)
    KslLogs.info("Total classpath entries: {}, existing: {}", classpaths.size, existingPaths.size)

    cachedClasspathList = existingPaths
    return existingPaths
  }

  private fun logCompilerServiceClasspathDiff(
      compilerServicePaths: List<String>,
      providerPaths: List<String>,
  ) {
    val compilerSet = compilerServicePaths.toSet()
    val providerSet = providerPaths.toSet()
    val compilerOnly = compilerSet - providerSet
    val providerOnly = providerSet - compilerSet

    fun interesting(paths: Set<String>): List<String> =
        paths.filter { path ->
          val normalized = path.lowercase()
          normalized.contains("android") ||
              normalized.contains("androidx") ||
              normalized.contains("kotlin") ||
              normalized.contains("gradle") ||
              normalized.contains("cache")
        }

    val compilerOnlyInteresting = interesting(compilerOnly)
    val providerOnlyInteresting = interesting(providerOnly)

    KslLogs.info(
        "Compiler/provider classpath diff: compilerServiceCount={}, providerCount={}, compilerOnly={}, providerOnly={}",
        compilerServicePaths.size,
        providerPaths.size,
        compilerOnly.size,
        providerOnly.size,
    )
    KslLogs.info(
        "Compiler-only interesting paths: count={}, preview={}",
        compilerOnlyInteresting.size,
        compilerOnlyInteresting.take(20).joinToString(prefix = "[", postfix = if (compilerOnlyInteresting.size > 20) ", ...]" else "]"),
    )
    KslLogs.info(
        "Provider-only interesting paths: count={}, preview={}",
        providerOnlyInteresting.size,
        providerOnlyInteresting.take(20).joinToString(prefix = "[", postfix = if (providerOnlyInteresting.size > 20) ", ...]" else "]"),
    )
  }

  private fun logCompilerToolingClasspathOrigins(
      existingPaths: List<String>,
      classpathOrigins: Map<String, Set<String>>,
  ) {
    val toolingPaths = existingPaths.filter(::isKotlinCompilerToolingArtifact)
    val preview = toolingPaths.take(24).joinToString(prefix = "[", postfix = if (toolingPaths.size > 24) ", ...]" else "]") { path ->
      val origins = classpathOrigins[path].orEmpty().sorted().joinToString("|").ifBlank { "unknown" }
      "${File(path).name} <= $origins"
    }
    KslLogs.info(
        "KLS compiler/tooling classpath audit: entries={}, preview={}",
        toolingPaths.size,
        preview,
    )
  }

  private fun isKotlinCompilerToolingArtifact(path: String): Boolean {
    val name = File(path).name.lowercase()
    return name.contains("kotlin-compiler") ||
        name.contains("kotlin-daemon") ||
        name.contains("kotlin-scripting-compiler") ||
        name.contains("kotlin-gradle-plugin") ||
        name.contains("dokka") ||
        name.startsWith("asm-")
  }

  private fun logClasspathLayerSummary(
      existingPaths: List<String>,
      projectDerivedFallbackAdded: Set<String>,
      scriptingFallbackAdded: Set<String>,
  ) {
    val existingSet = existingPaths.toSet()
    val projectDerivedExisting = projectDerivedFallbackAdded.filter { it in existingSet }
    val scriptingExisting = scriptingFallbackAdded.filter { it in existingSet }
    val authoritativeCount =
        existingSet.size - projectDerivedExisting.size - scriptingExisting.size

    fun preview(paths: Collection<String>): String =
        if (paths.isEmpty()) {
          "[]"
        } else {
          paths.take(12).joinToString(prefix = "[", postfix = if (paths.size > 12) ", ...]" else "]")
        }

    KslLogs.info(
        "Classpath layer summary: authoritativeExisting={}, projectDerivedFallbackExisting={}, scriptingFallbackExisting={}",
        authoritativeCount,
        projectDerivedExisting.size,
        scriptingExisting.size,
    )
    KslLogs.info(
        "Project-derived fallback existing preview: {}",
        preview(projectDerivedExisting),
    )
    KslLogs.info(
        "Scripting fallback existing preview: {}",
        preview(scriptingExisting),
    )
  }


  private fun gradleHomeCandidates(): List<File> {
    val raw =
        listOf(
            File(System.getProperty("user.home", ""), ".gradle"),
            File("/data/data/com.tom.rv2ide/files/home/.gradle"),
            File("/storage/emulated/0/.gradle"),
            // Android app's own gradle cache fallback
            File(System.getProperty("user.home", ""), "../../.gradle"),
        )

    val seen = mutableSetOf<String>()
    val unique = mutableListOf<File>()
    raw.forEach { dir ->
      val key = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
      if (seen.add(key)) {
        unique.add(dir)
      }
    }
    return unique
  }

  private fun gradleModulesCacheDirs(gradleHome: File): List<File> =
      listOf(File(gradleHome, "caches/modules-2/files-2.1"))

  private fun isRuntimeJarCandidate(file: File): Boolean =
      file.isFile &&
          file.extension == "jar" &&
          !file.name.contains("sources") &&
          !file.name.contains("javadoc")

  private fun collectArtifactJarsFromGradleModulesCache(
      modulesCache: File,
      group: String,
      artifact: String,
  ): Set<String> {
    val artifactDir = File(modulesCache, "${group.replace('.', File.separatorChar)}/$artifact")
    if (!artifactDir.exists()) return emptySet()

    return artifactDir
        .walkTopDown()
        .filter(::isRuntimeJarCandidate)
        .map { it.absolutePath }
        .toSet()
  }

  private fun collectVersionedKotlinArtifactJars(
      artifactBaseDir: File,
      preferredVersion: String?,
  ): Set<String> {
    if (!artifactBaseDir.exists()) return emptySet()

    val versionDirs = artifactBaseDir.listFiles()?.filter { it.isDirectory } ?: return emptySet()
    val versionDir =
        if (preferredVersion != null) {
          versionDirs.find { it.name == preferredVersion }
        } else {
          null
        } ?: versionDirs.maxByOrNull { it.name } ?: return emptySet()

    return versionDir
        .listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.flatMap { hashDir -> hashDir.listFiles().orEmpty().asSequence() }
        ?.filter(::isRuntimeJarCandidate)
        ?.map { it.absolutePath }
        ?.toSet()
        ?: emptySet()
  }

  private fun addClasspathEntry(file: File, classpaths: MutableSet<String>) {
    if (!file.exists()) return
    if (file.isFile && file.extension.equals("aar", ignoreCase = true)) {
      val extractedJar = extractClassesJarFromAar(file)
      if (extractedJar != null && extractedJar.exists()) {
        classpaths.add(extractedJar.absolutePath)
      } else {
        KslLogs.warn("Could not extract classes.jar from AAR, keeping original path: {}", file.absolutePath)
        classpaths.add(file.absolutePath)
      }
      return
    }
    classpaths.add(file.absolutePath)
  }

  private fun extractClassesJarFromAar(aarFile: File): File? {
    return try {
      val extractDir = File(aarFile.parentFile, "${aarFile.nameWithoutExtension}-extracted")
      val classesJar = File(extractDir, "classes.jar")
      if (classesJar.exists()) return classesJar
      extractDir.mkdirs()
      ZipFile(aarFile).use { zip ->
        val classesEntry = zip.getEntry("classes.jar") ?: return null
        zip.getInputStream(classesEntry).use { input ->
          classesJar.outputStream().use { output -> input.copyTo(output) }
        }
      }
      classesJar.takeIf { it.exists() }
    } catch (e: Exception) {
      KslLogs.warn("Failed to extract classes.jar from AAR: {}", aarFile.absolutePath, e)
      null
    }
  }

  /** Resolves Maven coordinates to JAR file in Gradle cache. */
  private fun resolveFromGradleCache(coordinates: String): File? {
    val parts = coordinates.split(":")
    if (parts.size != 3) return null

    val (group, name, version) = parts
    val groupPath = group.replace(".", "/")

    // Check Gradle cache
    val userHome = System.getProperty("user.home")
    val cacheDir = File(userHome, ".gradle/caches/modules-2/files-2.1")
    val artifactDir = File(cacheDir, "$groupPath/$name/$version")

    if (!artifactDir.exists()) return null

    // Find the JAR (skip sources/javadoc)
    return artifactDir
        .walkTopDown()
        .filter { it.extension == "jar" }
        .filterNot { it.name.contains("-sources") }
        .filterNot { it.name.contains("-javadoc") }
        .firstOrNull()
  }
  private fun addAndroidGeneratedSources(module: AndroidModule, classpaths: MutableSet<String>) {
    try {
      val buildDir = module.buildDir

      if (!buildDir.exists()) {
        KslLogs.warn("Build directory not found for module: {}", buildDir.absolutePath)
        return
      }

      KslLogs.infoThrottled(
          "kls:scan-generated-sources:${buildDir.absolutePath}",
          5000L,
          "Scanning for generated sources in: {}",
          buildDir.absolutePath,
      )
      addExternalLibraryJars(buildDir, classpaths)

      val variantName = module.getSelectedVariant()?.name ?: "debug"
      val generatedPaths = collectGeneratedSourceCandidates(module, variantName)
        .filter(::isClasspathRelevantGeneratedDir)

      var addedCount = 0
      for (dir in generatedPaths) {
        if (dir.exists() && dir.isDirectory) {
          classpaths.add(dir.absolutePath)
          addedCount++
          val relative = dir.relativeToOrNull(buildDir)?.path ?: dir.absolutePath
          KslLogs.infoThrottled(
              "kls:added-generated-source:${relative}",
              5000L,
              "✓ Added generated source: {}",
              relative,
          )
        } else {
          val relative = dir.relativeToOrNull(buildDir)?.path ?: dir.absolutePath
          KslLogs.debugThrottled(
              "kls:missing-generated-source:${relative}",
              5000L,
              "✗ Not found: {}",
              relative,
          )
        }
      }

      KslLogs.infoThrottled(
          "kls:generated-source-paths:${module.projectDir.absolutePath}",
          5000L,
          "Added {} generated source paths for module: {}",
          addedCount,
          module.projectDir.absolutePath,
      )
    } catch (e: Exception) {
      KslLogs.error("Failed to add Android generated sources for module: {}", module.projectDir.absolutePath, e)
    }
  }

  /**
   * Adds Kotlin scripting JARs from Gradle's cache These are needed for .kts file support and are
   * already downloaded by Gradle
   */
  private fun addKotlinScriptingJarsFromGradleCache(classpaths: MutableSet<String>) {
    try {
      val gradleHomeDirs = gradleHomeCandidates()

      val kotlinVersion = getKotlinVersionFromProject()

      val scriptingArtifacts =
          listOf(
              "kotlin-script-runtime",
              "kotlin-scripting-common",
              "kotlin-scripting-jvm",
              "kotlin-scripting-compiler-embeddable",
          )

      var foundCount = 0

      for (gradleHome in gradleHomeDirs) {
        if (!gradleHome.exists()) continue

        val modulesCaches =
            gradleModulesCacheDirs(gradleHome).map { File(it, "org.jetbrains.kotlin") }

        var foundInThisGradleHome = 0
        modulesCaches.forEach { modulesCache ->
          if (!modulesCache.exists()) {
            KslLogs.debug("Gradle cache not found at: {}", modulesCache.absolutePath)
            return@forEach
          }

          scriptingArtifacts.forEach { artifactName ->
            val artifactDir = File(modulesCache, artifactName)
            val jars = collectVersionedKotlinArtifactJars(artifactDir, kotlinVersion)
            jars.forEach { jarPath ->
              if (classpaths.add(jarPath)) {
                foundCount++
                foundInThisGradleHome++
              }
            }
          }
        }

        if (foundInThisGradleHome > 0) {
          KslLogs.info("Added {} Kotlin scripting JARs from Gradle cache fallback", foundCount)
          break
        }
      }

      if (foundCount == 0) {
        KslLogs.info("Gradle cache scripting fallback found no matching Kotlin scripting JARs")
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to add Kotlin scripting JARs from Gradle cache", e)
    }
  }

  /** Attempts to detect the Kotlin version used in the project */
  private fun getKotlinVersionFromProject(): String? {
    try {
      val projectManager = IProjectManager.getInstance()
      val workspace = projectManager.getWorkspace() ?: return null

      // Check build.gradle.kts for kotlin version
      val rootProject = workspace.getRootProject()
      val buildFile = File(rootProject.path, "build.gradle.kts")

      if (buildFile.exists()) {
        val content = buildFile.readText()

        // Look for kotlin("jvm") version or kotlin plugin version
        val versionRegex = """kotlin\("jvm"\)\s+version\s+"([^"]+)"""".toRegex()
        val match = versionRegex.find(content)
        if (match != null) {
          val version = match.groupValues[1]
          KslLogs.info("Detected Kotlin version from build.gradle.kts: {}", version)
          return version
        }

        // Alternative pattern: id("org.jetbrains.kotlin.jvm") version "x.y.z"
        val altRegex = """id\("org\.jetbrains\.kotlin\.[^"]+"\)\s+version\s+"([^"]+)"""".toRegex()
        val altMatch = altRegex.find(content)
        if (altMatch != null) {
          val version = altMatch.groupValues[1]
          KslLogs.info("Detected Kotlin version: {}", version)
          return version
        }
      }

      // Fallback: check gradle.properties or libs.versions.toml
      val propertiesFile = File(rootProject.path, "gradle.properties")
      if (propertiesFile.exists()) {
        val props = java.util.Properties()
        propertiesFile.inputStream().use { props.load(it) }
        val version = props.getProperty("kotlin.version") ?: props.getProperty("kotlinVersion")
        if (version != null) {
          KslLogs.info("Detected Kotlin version from gradle.properties: {}", version)
          return version
        }
      }
    } catch (e: Exception) {
      KslLogs.debug("Could not detect Kotlin version", e)
    }

    return null
  }

  /**
   * Adds external library JARs from Gradle's resolved dependencies This includes
   * kotlin-script-runtime and other Gradle dependencies
   */
  private fun addExternalLibraryJars(buildDir: File, classpaths: MutableSet<String>) {
    try {
      // AGP stores resolved external JARs in these locations:
      val externalLibLocations =
          listOf(
              // AGP 7.0+
              "intermediates/external_libs_dex/debug",
              "intermediates/external_file_lib_dex_archives/debug",

              // Compile classpath JARs
              "intermediates/compile_library_classes_jar/debug",
              "intermediates/compile_app_classes_jar/debug",

              // Runtime classpath
              "intermediates/runtime_library_classes_jar/debug",

              // Transforms (older AGP versions)
              "intermediates/transforms/mergeJavaRes/debug",

              // AAR extracted JARs
              "intermediates/aar_libs_jars/debug",
            )

      var foundScriptRuntime = false
      var addedExternalJarCount = 0

      externalLibLocations.forEach { location ->
        val dir = File(buildDir, location)
        if (dir.exists() && dir.isDirectory) {
          // Recursively find all JARs
          dir.walkTopDown().forEach { file ->
            if (file.isFile && file.extension == "jar") {
              addClasspathEntry(file, classpaths)
              addedExternalJarCount++

              // Check if this is the script runtime
              if (
                  file.name.contains("kotlin-script-runtime") ||
                      file.name.contains("kotlin-scripting")
              ) {
                foundScriptRuntime = true
              }
            }
          }
        }
      }

      val transformsDir = File(buildDir, "intermediates/transforms")
      if (transformsDir.exists() && transformsDir.isDirectory) {
        var addedTransformCount = 0
        transformsDir.walkTopDown().forEach { file ->
          if (!file.exists()) return@forEach

          val normalizedPath = file.absolutePath.lowercase()
          val isInterestingTransformPath =
              normalizedPath.contains("/transformed/") ||
                  normalizedPath.contains("/transforms/")

          if (!isInterestingTransformPath) return@forEach

          if (file.isFile && file.extension == "jar") {
            addClasspathEntry(file, classpaths)
            addedTransformCount++
          } else if (file.isFile && file.name == "classes.jar") {
            addClasspathEntry(file, classpaths)
            addedTransformCount++
          } else if (file.isDirectory && file.name == "classes") {
            classpaths.add(file.absolutePath)
            addedTransformCount++
          }
        }
        KslLogs.info(
            "Scanned AGP transforms for external libraries: added {} candidate entries from {}",
            addedTransformCount,
            transformsDir.absolutePath,
        )
      }

      KslLogs.info(
          "Added {} external library jar entries from build intermediates for {}",
          addedExternalJarCount,
          buildDir.absolutePath,
      )

      if (!foundScriptRuntime) {
        KslLogs.debug("kotlin-script-runtime not found in build artifacts")
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to add external library JARs", e)
    }
  }

  /** Recursively scans directories for Java/Kotlin source files */
  private fun scanForSourceDirectories(dir: File, classpaths: MutableSet<String>, maxDepth: Int) {
    if (maxDepth <= 0) return

    try {
      val files = dir.listFiles() ?: return

      // Check if current directory contains source files
      val hasSourceFiles =
          files.any { it.isFile && (it.extension == "java" || it.extension == "kt") }

      if (hasSourceFiles && !classpaths.contains(dir.absolutePath)) {
        classpaths.add(dir.absolutePath)
        KslLogs.debug("Discovered source directory: {}", dir.absolutePath)
      }

      // Recurse into subdirectories
      files
          .filter { it.isDirectory }
          .forEach { subDir -> scanForSourceDirectories(subDir, classpaths, maxDepth - 1) }
    } catch (e: Exception) {
      KslLogs.debug("Error scanning directory: {}", dir.absolutePath, e)
    }
  }

  /** Find compiled .class directories in intermediates */
  private fun findCompiledClassDirectories(intermediatesDir: File, classpaths: MutableSet<String>) {
    try {
      val classDirectories =
          listOf(
              "compile_library_classes_jar/debug/classes.jar",
              "compile_app_classes_jar/debug/classes.jar",
              "transforms/classes/debug",
              "javac/debug/classes",
              "kotlin-classes/debug",
          )

      classDirectories.forEach { path ->
        val dir = File(intermediatesDir, path)
        if (dir.exists()) {
          classpaths.add(dir.absolutePath)
          KslLogs.info("✓ Added compiled classes: {}", path)
        }
      }
    } catch (e: Exception) {
      KslLogs.debug("Error finding compiled class directories", e)
    }
  }

  fun getAndroidSdkPath(): String {
    // First try from compiler service
    val serviceResult =
        try {
          compilerService?.let { service ->
            val bootClassPaths = service.getFileManager().getBootClassPaths()
            val androidJar = bootClassPaths.find { it.name == "android.jar" }
            androidJar?.parentFile?.parentFile?.parentFile?.absolutePath
          }
        } catch (e: Exception) {
          KslLogs.error("Failed to get Android SDK path from compiler service", e)
          null
        }

    if (!serviceResult.isNullOrEmpty()) {
      return serviceResult
    }

    // Fallback to project system
    return try {
      val projectManager = IProjectManager.getInstance()
      val workspace = projectManager.getWorkspace()

      if (workspace != null) {
        val androidModules = workspace.androidProjects()
        val firstModule = androidModules.firstOrNull()

        if (firstModule != null) {
          val androidJar = firstModule.bootClassPaths.find { it.name == "android.jar" }
          if (androidJar != null) {
            val platformDir = androidJar.parentFile
            if (platformDir != null) {
              val sdkRoot = platformDir.parentFile
              if (sdkRoot != null) {
                return sdkRoot.absolutePath
              }
            }
          }
        }
      }
      ""
    } catch (e: Exception) {
      KslLogs.error("Failed to get Android SDK path from project system", e)
      ""
    }
  }

  /** Lists all classes available in the current classpath. */
  fun listClassesInClasspath(): Set<ClassInfo> {
    val classpathFiles = getClasspathList().map { File(it) }.filter { it.exists() }
    RuntimeProbe.mark("KotlinClasspathProvider.listClassesInClasspath files=${classpathFiles.size}")
    KslLogs.info("{} files={}", classpathReaderMarker, classpathFiles.size)
    return try {
      classpathReader.listClasses(classpathFiles).toSet()
    } catch (e: Exception) {
      KslLogs.error("Failed to list classes in classpath", e)
      emptySet()
    }
  }

  /** Gets the module classpath for a specific project path. */
  fun getModuleClasspath(projectPath: String): List<String> {
    return try {
      val projectManager = IProjectManager.getInstance()
      val workspace = projectManager.getWorkspace() ?: return emptyList()

      val project = workspace.findProject(projectPath) as? ModuleProject ?: return emptyList()
      val classpaths = mutableSetOf<String>()

      // Add module-specific classpaths
      project.getModuleClasspaths().forEach { cp ->
        if (cp.exists()) classpaths.add(cp.absolutePath)
      }

      // Add compile classpaths
      project.getCompileClasspaths().forEach { cp ->
        if (cp.exists()) classpaths.add(cp.absolutePath)
      }

      classpaths.toList()
    } catch (e: Exception) {
      KslLogs.error("Failed to get module classpath for: {}", projectPath, e)
      emptyList()
    }
  }

  /** Invalidate the classpath cache - call this when build completes */
  fun invalidateCache() {
    cachedClasspathList = null
    cachedClasspath = null
    KslLogs.info("Classpath cache invalidated")
  }
}
