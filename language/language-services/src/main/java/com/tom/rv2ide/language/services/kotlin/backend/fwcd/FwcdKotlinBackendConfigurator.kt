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
package com.tom.rv2ide.language.services.kotlin.backend.fwcd

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendContext
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendId
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslBuildModel
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslEnvironment
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslModel
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslScriptModel
import java.io.File
import java.security.MessageDigest

/**
 * Configurator for the fwcd/kotlin-language-server backend.
 *
 * Owns fwcd-specific initializationOptions, runtime `workspace/didChangeConfiguration`
 * payloads and ktfmt formatting settings.
 */
class FwcdKotlinBackendConfigurator(
    private val context: KotlinBackendContext,
) : KotlinBackendConfigurator {

  private val workspace = context.workspace
  private val classpathProvider = context.classpathProvider

  override fun resolveWorkspaceRoot(): File {
    val projectRoot = workspace.getProjectDir()
    if (projectRoot.exists() && projectRoot.isDirectory) {
      KlsLogs.info(
          "Using project root as FWCD workspace root: {}",
          projectRoot.absolutePath,
      )
      return projectRoot
    }
    return context.mainModuleWorkspaceRoot(KotlinBackendId.FWCD)
  }

  override fun initializationOptions(): JsonObject {
    val effectiveClassPaths = classpathProvider.getClasspathList()
    val javaSourceRoots = classpathProvider.getJavaSourceRootsList()
    val gradleKotlinDslModel = workspace.getGradleKotlinDslModel()
    logGradleKotlinDslModel("initialize", gradleKotlinDslModel)
    val classpathArray = JsonArray()
    val javaSourceRootsArray = JsonArray()

    effectiveClassPaths.forEach { path -> classpathArray.add(path) }
    javaSourceRoots.forEach { path -> javaSourceRootsArray.add(path) }
    val acsMetadata = createAcsMetadata(effectiveClassPaths, javaSourceRoots)
    val initOptions =
        JsonObject().apply {
          addProperty("storagePath", workspace.getProjectDir().resolve(".acside").absolutePath)

          // Compatibility hints for KLS variants. Current fwcd primarily relies on
          // initializationOptions.classpath/usePredefinedClasspath/disableDependencyResolution
          // plus runtime settings.kotlin.indexing.enabled=true; these hints must not be
          // interpreted by ACS as permission to disable server-side indexing.
          addProperty("indexing", "auto")
          addProperty("externalSources", "auto")

          add(
              "completion",
              JsonObject().apply {
                add("snippets", JsonObject().apply { addProperty("enabled", true) })
              },
          )

          add(
              "scripts",
              JsonObject().apply {
                addProperty("enabled", true)
                addProperty("buildScriptsEnabled", true)
                add("model", createGradleKotlinDslModelJson(gradleKotlinDslModel))
                add(
                    "templates",
                    JsonArray().apply {
                      add("kotlin.script.templates.standard.ScriptTemplateWithArgs")
                    },
                )
              },
          )

          // First classpath delivery: initialize must provide it so FWCD can construct its
          // predefined compiler classpath during startup. createFwcdRuntimeConfig() sends the
          // same settings after initialization for runtime synchronization; do not move this
          // large payload back into process environment variables (Android execve can hit E2BIG).
          addProperty("usePredefinedClasspath", true)
          addProperty("disableDependencyResolution", true)
          add("classpath", classpathArray)
          add("javaSourceRoots", javaSourceRootsArray)
          add("acs", acsMetadata)
        }
    KlsLogs.debugThrottled("kls:configured-classpath-count", 5000L, "Configured KLS with {} classpath entries", effectiveClassPaths.size)
    KlsLogs.debugThrottled("kls:configured-java-source-roots", 5000L, "Configured KLS with {} java source roots", javaSourceRoots.size)
    return initOptions
  }

  override fun beforeBackendStart(connection: KotlinBackendConnection) = Unit

  override fun afterBackendInitialized(connection: KotlinBackendConnection, hasSupportedDocuments: Boolean) {
    KlsLogs.debug("FWCD initialized with complete classpath and Gradle Kotlin DSL model")
  }

  override fun onEnvironmentRefreshed(connection: KotlinBackendConnection) {
    sendFwcdRuntimeConfig(connection)
  }

  override fun applyFormattingStyle(connection: KotlinBackendConnection, style: String) {
    val indentSize =
        when (style) {
          "google",
          "facebook" -> 2
          "kotlinlang" -> 4
          else -> 4
        }

    val configParams =
        JsonObject().apply {
          add(
              "settings",
              JsonObject().apply {
                add(
                    "kotlin",
                    JsonObject().apply {
                      add(
                          "formatting",
                          JsonObject().apply {
                            addProperty("formatter", "ktfmt")
                            add(
                                "ktfmt",
                                JsonObject().apply {
                                  addProperty("style", style)
                                  addProperty("indent", indentSize)
                                  addProperty("maxWidth", 100)
                                  addProperty("removeUnusedImports", true)
                                },
                            )
                          },
                      )
                    },
                )
              },
          )
        }

    connection.sendNotification("workspace/didChangeConfiguration", configParams)
    KlsLogs.debug("Sent formatting configuration: style={}, indent={}", style, indentSize)
  }

  private fun sha256Hex(lines: Collection<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val content = lines.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString("\n")
    val hash = digest.digest(content.toByteArray())
    return hash.joinToString("") { "%02x".format(it) }
  }

  private fun filePathsArray(files: Collection<File>): JsonArray {
    val array = JsonArray()
    files.map { it.absolutePath }.distinct().sorted().forEach { array.add(it) }
    return array
  }

  private fun stringArray(values: Collection<String>): JsonArray {
    val array = JsonArray()
    values.distinct().sorted().forEach { array.add(it) }
    return array
  }

  private fun orderedStringArray(values: Collection<String>): JsonArray {
    val array = JsonArray()
    values.forEach { array.add(it) }
    return array
  }

  private fun inferGenerator(rootPath: String): String {
    val normalized = rootPath.replace('\\', '/').lowercase()
    return when {
      "/ksp/" in normalized -> "ksp"
      "/kapt/" in normalized -> "kapt"
      "buildconfig" in normalized -> "buildConfig"
      "data_binding" in normalized || "databinding" in normalized -> "databinding"
      "/aidl/" in normalized -> "aidl"
      "/renderscript/" in normalized -> "renderscript"
      else -> "unknown"
    }
  }

  private fun createDependencyIndexCandidates(classpaths: List<String>): JsonArray =
    JsonArray().apply {
      classpaths
          .asSequence()
          .map(::File)
          .filter { file ->
            val name = file.name.lowercase()
            name.startsWith("kotlin-compiler-embeddable-") ||
                name.startsWith("kotlin-scripting-compiler-embeddable-") ||
                name.startsWith("dokka-core-")
          }
          .sortedBy { file -> file.name }
          .forEach { file ->
            add(
                JsonObject().apply {
                  addProperty("path", file.absolutePath)
                  addProperty("artifact", file.name)
                },
            )
          }
    }

  private fun createAcsMetadata(
    effectiveClassPaths: List<String>,
    javaSourceRoots: List<String>,
  ): JsonObject {
    val workspaceRoot = workspace.getProjectDir().absolutePath
    val modules = workspace.getSubProjects().filterIsInstance<ModuleProject>().sortedBy { it.path }
    val variantSelections = workspace.getAndroidVariantSelections()
    val androidModules = modules.filterIsInstance<AndroidModule>()

    val workspaceSourceRootsEntries = mutableListOf<String>()
    val generatedSourceRootsEntries = mutableListOf<String>()
    val moduleEntries = mutableListOf<String>()

    val modulesArray = JsonArray()
    val sourceLayoutModulesArray = JsonArray()
    val generatedSourcesArray = JsonArray()
    val variantSelectionsObject = JsonObject()

    variantSelections.toSortedMap().forEach { (modulePath, info) ->
      variantSelectionsObject.addProperty(modulePath, info.selectedVariant)
    }

    modules.forEach { module ->
      moduleEntries += listOf(module.path, module.name, module.projectDir.absolutePath, module.buildDir.absolutePath)

      val moduleJson = JsonObject().apply {
        addProperty("path", module.path)
        addProperty("name", module.name)
        addProperty("projectDir", module.projectDir.absolutePath)
        addProperty("buildDir", module.buildDir.absolutePath)
        addProperty("type", if (module is AndroidModule) "android" else "java")
      }

      val workspaceRoots = mutableSetOf<File>()
      val generatedRoots = mutableSetOf<File>()
      val resourceRoots = mutableSetOf<File>()
      val javaRoots = mutableSetOf<File>()
      val kotlinRoots = mutableSetOf<File>()

      if (module is AndroidModule) {
        val selectedVariant = module.getSelectedVariant()
        moduleJson.addProperty("namespace", module.namespace)
        moduleJson.addProperty("selectedVariant", selectedVariant?.name)
        moduleJson.addProperty("isLibrary", module.isLibrary)
        moduleJson.addProperty("isApplication", module.isApplication)

        module.mainSourceSet?.sourceProvider?.javaDirectories?.forEach {
          workspaceRoots += it
          javaRoots += it
        }
        module.mainSourceSet?.sourceProvider?.kotlinDirectories?.forEach {
          workspaceRoots += it
          kotlinRoots += it
        }
        module.mainSourceSet?.sourceProvider?.resDirectories?.forEach { resourceRoots += it }
        selectedVariant?.mainArtifact?.generatedSourceFolders?.forEach {
          generatedRoots += it
        }
      } else {
        module.getSourceDirectories().forEach { workspaceRoots += it }
      }

      workspaceRoots.forEach { workspaceSourceRootsEntries += "${module.path}:${it.absolutePath}" }
      generatedRoots.forEach { generatedSourceRootsEntries += "${module.path}:${it.absolutePath}" }

      modulesArray.add(moduleJson)

      sourceLayoutModulesArray.add(
          JsonObject().apply {
            addProperty("path", module.path)
            add("workspaceSourceRoots", filePathsArray(workspaceRoots))
            add("generatedSourceRoots", filePathsArray(generatedRoots))
            add("resourceRoots", filePathsArray(resourceRoots))
            add("javaSourceRoots", filePathsArray(javaRoots))
            add("kotlinSourceRoots", filePathsArray(kotlinRoots))
          },
      )

      generatedRoots.sortedBy { it.absolutePath }.forEach { root ->
        generatedSourcesArray.add(
            JsonObject().apply {
              addProperty("modulePath", module.path)
              addProperty("variant", if (module is AndroidModule) module.getSelectedVariant()?.name else null)
              addProperty("root", root.absolutePath)
              addProperty("generator", inferGenerator(root.absolutePath))
            },
        )
      }
    }

    val generatedRootsOnly = androidModules
        .flatMap { module ->
          module.getSelectedVariant()?.mainArtifact?.generatedSourceFolders.orEmpty().map { root ->
            "${module.path}:${module.getSelectedVariant()?.name}:${root.absolutePath}"
          }
        }

    val environmentFingerprint = JsonObject().apply {
      addProperty("workspaceRoot", workspaceRoot)
      addProperty("workspaceRootHash", sha256Hex(listOf(workspaceRoot)))
      addProperty("classpathHash", sha256Hex(effectiveClassPaths))
      addProperty("javaSourceRootsHash", sha256Hex(javaSourceRoots))
      addProperty("generatedSourceRootsHash", sha256Hex(generatedRootsOnly))
      addProperty(
          "variantSelectionHash",
          sha256Hex(
              variantSelections.toSortedMap().map { (modulePath, info) -> "$modulePath=${info.selectedVariant}" },
          ),
      )
      addProperty("moduleGraphHash", sha256Hex(moduleEntries))
      addProperty("schemaVersion", 1)
    }

    return JsonObject().apply {
      addProperty("schemaVersion", 1)
      add("environmentFingerprint", environmentFingerprint)
      add("variantSelections", variantSelectionsObject)
      add("modules", modulesArray)
      add(
          "sourceLayout",
          JsonObject().apply {
            add("modules", sourceLayoutModulesArray)
            add("workspaceSourceRoots", stringArray(workspaceSourceRootsEntries))
            add("generatedSourceRoots", stringArray(generatedSourceRootsEntries))
          },
      )
      add("generatedSources", generatedSourcesArray)
      // Candidates constrain only dependency symbol enumeration. FWCD keeps the complete
      // classpath for compiler-backed source analysis, completion, and diagnostics.
      add("dependencyIndexCandidates", createDependencyIndexCandidates(effectiveClassPaths))
    }
  }

  private fun createFwcdRuntimeConfig(): JsonObject {
    val effectiveClassPaths = classpathProvider.getClasspathList()
    val javaSourceRoots = classpathProvider.getJavaSourceRootsList()
    val gradleKotlinDslModel = workspace.getGradleKotlinDslModel()
    logGradleKotlinDslModel("runtime", gradleKotlinDslModel)
    val classpathArray = JsonArray()
    val javaSourceRootsArray = JsonArray()
    effectiveClassPaths.forEach { path -> classpathArray.add(path) }
    javaSourceRoots.forEach { path -> javaSourceRootsArray.add(path) }
    val acsMetadata = createAcsMetadata(effectiveClassPaths, javaSourceRoots)

    return JsonObject().apply {
      // Send the classpath again after initialize via workspace/didChangeConfiguration.
      // initializationOptions establishes FWCD's compiler model before the server is ready;
      // this runtime notification keeps that model synchronized after startup, classpath reloads,
      // and cache-restore/indexing flows. The full list must stay in JSON-RPC rather than launcher
      // environment variables: on Android, their combined size can make execve fail with E2BIG.
      add(
          "settings",
          JsonObject().apply {
            add(
                "kotlin",
                JsonObject().apply {
                  addProperty("usePredefinedClasspath", true)
                  addProperty("disableDependencyResolution", true)
                  add("classpath", classpathArray)
                   add("javaSourceRoots", javaSourceRootsArray)
                   add(
                       "scripts",
                       JsonObject().apply {
                         addProperty("enabled", true)
                         addProperty("buildScriptsEnabled", true)
                         add("model", createGradleKotlinDslModelJson(gradleKotlinDslModel))
                       },
                   )
                  add(
                      "completion",
                      JsonObject().apply {
                        add(
                            "snippets",
                            JsonObject().apply { addProperty("enabled", true) },
                        )
                      },
                  )
                  add(
                      "indexing",
                      JsonObject().apply {
                        addProperty("enabled", true)
                        // Empty by default. Compiler/Dokka plugin projects may explicitly include
                        // a safety-filtered package tree without changing the compiler classpath.
                        add("includePackages", JsonArray())
                      },
                  )
                  add("acs", acsMetadata)
                },
            )
          },
      )
    }
  }

  private fun logGradleKotlinDslModel(source: String, model: GradleKotlinDslModel) {
    val scriptCount = model.builds.sumOf { it.scripts.size }
    val classpathCount = model.builds.sumOf { build -> build.scripts.sumOf { it.classPath.size } }
    KlsLogs.info(
        "FWCD Gradle Kotlin DSL model {}: state={}, builds={}, scripts={}, scriptClasspathEntries={}, fingerprint={}",
        source,
        model.state,
        model.builds.size,
        scriptCount,
        classpathCount,
        model.fingerprint ?: "<none>",
    )
  }

  private fun createGradleKotlinDslModelJson(model: GradleKotlinDslModel): JsonObject =
      JsonObject().apply {
        addProperty("schemaVersion", model.schemaVersion)
        addProperty("state", model.state.name)
        add("builds", JsonArray().apply { model.builds.forEach { add(createGradleKotlinDslBuildJson(it)) } })
        add("exceptions", stringArray(model.exceptions))
        model.fingerprint?.let { addProperty("fingerprint", it) }
      }

  private fun createGradleKotlinDslBuildJson(build: GradleKotlinDslBuildModel): JsonObject =
      JsonObject().apply {
        addProperty("buildRootPath", build.buildRootPath)
        addProperty("state", build.state.name)
        add("environment", createGradleKotlinDslEnvironmentJson(build.environment))
        add("scripts", JsonArray().apply { build.scripts.forEach { add(createGradleKotlinDslScriptJson(it)) } })
        add("exceptions", stringArray(build.exceptions))
        build.fingerprint?.let { addProperty("fingerprint", it) }
      }

  private fun createGradleKotlinDslEnvironmentJson(environment: GradleKotlinDslEnvironment): JsonObject =
      JsonObject().apply {
        addProperty("state", environment.state.name)
        environment.gradleVersion?.let { addProperty("gradleVersion", it) }
        environment.gradleHome?.let { addProperty("gradleHome", it) }
        environment.gradleUserHome?.let { addProperty("gradleUserHome", it) }
        environment.javaHome?.let { addProperty("javaHome", it) }
        add("jvmArguments", orderedStringArray(environment.jvmArguments))
        add("exceptions", orderedStringArray(environment.exceptions))
      }

  private fun createGradleKotlinDslScriptJson(script: GradleKotlinDslScriptModel): JsonObject =
      JsonObject().apply {
        addProperty("scriptPath", script.scriptPath)
        addProperty("kind", script.kind.name)
        script.projectPath?.let { addProperty("projectPath", it) }
        addProperty("state", script.state.name)
        addProperty("modelSource", script.modelSource)
        add("classPath", orderedStringArray(script.classPath))
        add("sourcePath", orderedStringArray(script.sourcePath))
        add("implicitImports", orderedStringArray(script.implicitImports))
        add("editorReports", JsonArray().apply {
          script.editorReports.forEach { report ->
            add(JsonObject().apply {
              addProperty("severity", report.severity.name)
              addProperty("message", report.message)
              report.position?.let {
                addProperty("line", it.line)
                addProperty("column", it.column)
              }
            })
          }
        })
        add("exceptions", orderedStringArray(script.exceptions))
        addProperty("inputState", script.inputState.name)
        script.inputFingerprint?.let { addProperty("inputFingerprint", it) }
        script.fingerprint?.let { addProperty("fingerprint", it) }
      }

  private fun sendFwcdRuntimeConfig(connection: KotlinBackendConnection) {
    val configParams = createFwcdRuntimeConfig()
    connection.sendNotification("workspace/didChangeConfiguration", configParams)
  }
}
