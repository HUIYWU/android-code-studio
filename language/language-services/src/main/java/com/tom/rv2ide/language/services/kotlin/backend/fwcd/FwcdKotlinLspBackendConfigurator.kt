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
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendContext
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendId
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspConnection
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import java.io.File
import java.security.MessageDigest

/**
 * Configurator for the fwcd/kotlin-language-server backend.
 *
 * Owns fwcd-specific initializationOptions, runtime `workspace/didChangeConfiguration`
 * payloads, the ACS workspace-symbol snapshot cache and ktfmt formatting settings.
 */
class FwcdKotlinLspBackendConfigurator(
    private val context: KotlinLspBackendContext,
) : KotlinLspBackendConfigurator {

  private val workspace = context.workspace
  private val classpathProvider = context.classpathProvider
  private val indexCache = KotlinIndexCache(workspace.getProjectDir().absolutePath)

  override fun resolveWorkspaceRoot(): File {
    val projectRoot = workspace.getProjectDir()
    if (projectRoot.exists() && projectRoot.isDirectory) {
      KlsLogs.info(
          "Using project root as FWCD workspace root: {}",
          projectRoot.absolutePath,
      )
      return projectRoot
    }
    return context.mainModuleWorkspaceRoot(KotlinLspBackendId.FWCD)
  }

  override fun initializationOptions(): JsonObject {
    val effectiveClassPaths = classpathProvider.getClasspathList()
    val javaSourceRoots = classpathProvider.getJavaSourceRootsList()
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
                // Disable Gradle Kotlin script support for Android source editing. The current KLS
                // build crashes while analyzing settings.gradle.kts, which prevents reliable
                // diagnostics for normal .kt files.
                addProperty("enabled", false)
                addProperty("buildScriptsEnabled", false)
                // Keep template metadata present for compatibility, but scripts remain disabled.
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

  @Volatile private var startupClasspathHash: String = ""
  @Volatile private var startupCacheValid = false

  override fun beforeServerStart(connection: KotlinLspConnection) {
    val currentClasspath = classpathProvider.getClasspathList()
    startupClasspathHash = indexCache.computeClasspathHash(currentClasspath)
    startupCacheValid = indexCache.isCacheValid(startupClasspathHash)

    KlsLogs.infoThrottled("kls:cache-status", 5000L, "Cache status: {}", if (startupCacheValid) "VALID" else "INVALID/MISSING")
    KlsLogs.debugThrottled("kls:cache-stats", 5000L, "{}", indexCache.getCacheStats())
  }

  override fun afterServerInitialized(connection: KotlinLspConnection, hasKotlinSources: Boolean) {
    if (startupCacheValid) {
      restoreCachedIndex(connection, hasKotlinSources)
    } else {
      triggerIndexing(connection, startupClasspathHash, hasKotlinSources)
    }
  }

  override fun onClasspathReloaded(connection: KotlinLspConnection) {
    indexCache.clearCache()
    val currentClasspath = classpathProvider.getClasspathList()
    val currentHash = indexCache.computeClasspathHash(currentClasspath)
    triggerIndexing(connection, currentHash, hasKotlinSources = true)
  }

  override fun applyFormattingStyle(connection: KotlinLspConnection, style: String) {
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

  private fun restoreCachedIndex(connection: KotlinLspConnection, hasKotlinSources: Boolean) {
    if (!hasKotlinSources) {
      sendFwcdRuntimeConfig(connection)
      return
    }

    KlsLogs.infoThrottled("kls:restore-cache-start", 5000L, "Restoring cached workspace-symbol snapshot...")

    val cachedSymbols = indexCache.loadCache()
    if (cachedSymbols != null && cachedSymbols.size() > 0) {
      // This does not restore fwcd's internal SymbolIndex. It only reuses ACS' last
      // workspace/symbol snapshot. Server-side indexing intentionally remains enabled so
      // completion, standard-library symbols and diagnostics stay correct.
      sendFwcdRuntimeConfig(connection)
      KlsLogs.infoThrottled(
          "kls:restore-cache-success",
          5000L,
          "Cache restored with {} symbols",
          cachedSymbols.size(),
      )
    } else {
      val currentClasspath = classpathProvider.getClasspathList()
      val currentHash = indexCache.computeClasspathHash(currentClasspath)
      triggerIndexing(connection, currentHash, hasKotlinSources)
    }
  }

  private fun triggerIndexing(
      connection: KotlinLspConnection,
      classpathHash: String,
      hasKotlinSources: Boolean,
  ) {
    KlsLogs.infoThrottled("kls:trigger-indexing", 3000L, "Triggering classpath indexing...")
    sendFwcdRuntimeConfig(connection)
    if (!hasKotlinSources) {
      return
    }

    // Request symbols to warm up and cache the index
    val symbolParams = JsonObject().apply { addProperty("query", "") }

    connection.sendRequest("workspace/symbol", symbolParams) { result ->
      try {
        val symbols =
            when {
              result == null -> JsonArray()
              result.has("symbols") -> result.getAsJsonArray("symbols") ?: JsonArray()
              result.has("result") -> result.getAsJsonArray("result") ?: JsonArray()
              else -> JsonArray()
            }
        val symbolCount = symbols.size()
        KlsLogs.infoThrottled("kls:indexing-complete", 3000L, "Indexing warm-up complete, found {} symbols", symbolCount)

        // Save to cache
        if (symbolCount > 0) {
          indexCache.saveCache(symbols, classpathHash)
        }
      } catch (e: Exception) {
        KlsLogs.warn("Failed to warm up Kotlin symbols", e)
      }
    }
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
                        addProperty("enabled", false)
                        addProperty("buildScriptsEnabled", false)
                        // Keep legacy fwcd runtime compatibility: older parsing only reads
                        // predefined classpath updates from settings.kotlin.scripts.classpath.
                        add("classpath", classpathArray.deepCopy())
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
                  // ACS keeps fwcd indexing enabled even when restoring the local workspace-symbol
                  // cache. The local cache is only a startup optimization/UI hint and must not be
                  // treated as a request to disable the server-side symbol index, because completion,
                  // standard-library symbols, diagnostics and Android/Compose classpath scenarios rely
                  // on fwcd maintaining its own index.
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

  private fun sendFwcdRuntimeConfig(connection: KotlinLspConnection) {
    val configParams = createFwcdRuntimeConfig()
    connection.sendNotification("workspace/didChangeConfiguration", configParams)
  }
}
