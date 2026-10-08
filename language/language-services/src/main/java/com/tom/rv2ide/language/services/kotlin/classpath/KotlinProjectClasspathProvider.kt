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

package com.tom.rv2ide.language.services.kotlin.classpath

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import java.io.File
import java.util.zip.ZipFile

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class KotlinProjectClasspathProvider(val workspace: IWorkspace? = null) {


  private var cachedClasspathList: List<String>? = null
  private var cachedClasspath: String? = null

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
      val currentWorkspace = workspace ?: return emptyList()
      val sourceRoots = linkedSetOf<String>()

      val allProjects = mutableListOf(currentWorkspace.getRootProject())
      allProjects.addAll(currentWorkspace.getSubProjects())

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
      KlsLogs.error("Failed to get Java source roots from project system", e)
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

    // Then, enhance with project system classpaths
    try {
      val currentWorkspace = workspace

      if (currentWorkspace != null) {
        // Get all projects (root + subprojects)
        val allProjects = mutableListOf(currentWorkspace.getRootProject())
        allProjects.addAll(currentWorkspace.getSubProjects())

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
                KlsLogs.info("Added generated JAR: {}", generatedJar.absolutePath)
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
      KlsLogs.error("Failed to get classpath from project system", e)
    }

    val existingPaths = classpaths.filter { File(it).exists() }.toList()
    logClasspathLayerSummary(existingPaths, projectDerivedFallbackAdded)
    logCompilerToolingClasspathOrigins(existingPaths, classpathOrigins)
    KlsLogs.info("Total classpath entries: {}, existing: {}", classpaths.size, existingPaths.size)

    cachedClasspathList = existingPaths
    return existingPaths
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
    KlsLogs.info(
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
  ) {
    val existingSet = existingPaths.toSet()
    val projectDerivedExisting = projectDerivedFallbackAdded.filter { it in existingSet }
    val authoritativeCount =
        existingSet.size - projectDerivedExisting.size

    fun preview(paths: Collection<String>): String =
        if (paths.isEmpty()) {
          "[]"
        } else {
          paths.take(12).joinToString(prefix = "[", postfix = if (paths.size > 12) ", ...]" else "]")
        }

    KlsLogs.info(
        "Classpath layer summary: authoritativeExisting={}, projectDerivedFallbackExisting={}, ",
        authoritativeCount,
        projectDerivedExisting.size,
    )
    KlsLogs.info(
        "Project-derived fallback existing preview: {}",
        preview(projectDerivedExisting),
    )

  }


  private fun addClasspathEntry(file: File, classpaths: MutableSet<String>) {
    if (!file.exists()) return
    if (file.isFile && file.extension.equals("aar", ignoreCase = true)) {
      val extractedJar = extractClassesJarFromAar(file)
      if (extractedJar != null && extractedJar.exists()) {
        classpaths.add(extractedJar.absolutePath)
      } else {
        KlsLogs.warn("Could not extract classes.jar from AAR, keeping original path: {}", file.absolutePath)
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
      KlsLogs.warn("Failed to extract classes.jar from AAR: {}", aarFile.absolutePath, e)
      null
    }
  }

  private fun addAndroidGeneratedSources(module: AndroidModule, classpaths: MutableSet<String>) {
    try {
      val buildDir = module.buildDir

      if (!buildDir.exists()) {
        KlsLogs.warn("Build directory not found for module: {}", buildDir.absolutePath)
        return
      }

      KlsLogs.infoThrottled(
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
          KlsLogs.infoThrottled(
              "kls:added-generated-source:${relative}",
              5000L,
              "✓ Added generated source: {}",
              relative,
          )
        } else {
          val relative = dir.relativeToOrNull(buildDir)?.path ?: dir.absolutePath
          KlsLogs.debugThrottled(
              "kls:missing-generated-source:${relative}",
              5000L,
              "✗ Not found: {}",
              relative,
          )
        }
      }

      KlsLogs.infoThrottled(
          "kls:generated-source-paths:${module.projectDir.absolutePath}",
          5000L,
          "Added {} generated source paths for module: {}",
          addedCount,
          module.projectDir.absolutePath,
      )
    } catch (e: Exception) {
      KlsLogs.error("Failed to add Android generated sources for module: {}", module.projectDir.absolutePath, e)
    }
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
        KlsLogs.info(
            "Scanned AGP transforms for external libraries: added {} candidate entries from {}",
            addedTransformCount,
            transformsDir.absolutePath,
        )
      }

      KlsLogs.info(
          "Added {} external library jar entries from build intermediates for {}",
          addedExternalJarCount,
          buildDir.absolutePath,
      )

      if (!foundScriptRuntime) {
        KlsLogs.debug("kotlin-script-runtime not found in build artifacts")
      }
    } catch (e: Exception) {
      KlsLogs.error("Failed to add external library JARs", e)
    }
  }

  fun getAndroidSdkPath(): String {
    return try {
      val currentWorkspace = workspace
      val androidModule = currentWorkspace?.androidProjects()?.firstOrNull()
      val androidJar = androidModule?.bootClassPaths?.find { it.name == "android.jar" }
      val platformDir = androidJar?.parentFile
      platformDir?.parentFile?.parentFile?.absolutePath ?: ""
    } catch (e: Exception) {
      KlsLogs.error("Failed to get Android SDK path from project system", e)
      ""
    }
  }

  /** Invalidate the classpath cache - call this when build completes */
  fun invalidateCache() {
    cachedClasspathList = null
    cachedClasspath = null
    KlsLogs.info("Classpath cache invalidated")
  }
}
