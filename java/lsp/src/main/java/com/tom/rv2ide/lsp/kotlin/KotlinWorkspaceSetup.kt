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

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.kotlin.compiler.KotlinCompilerService
import com.tom.rv2ide.lsp.kotlin.etc.LspFeatures
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projectdata.state.lsp.Index
import com.tom.rv2ide.projectdata.logs.LogStream
import java.io.File
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class KotlinWorkspaceSetup(
    private val context: Context,
    private val workspace: IWorkspace,
    private val backendConfigurator: KotlinLspBackendConfigurator,
    private val backendId: KotlinLspBackendId = KotlinLspBackendId.FWCD,
) {


  private var compilerService: KotlinCompilerService? = null
  private val classpathProvider = KotlinClasspathProvider()

  // Use project directory path for cache identification
  private val indexCache = KotlinIndexCache(workspace.getProjectDir().absolutePath)

  private var buildWatcher: WatchService? = null
  private var watcherJob: Job? = null
  private val watchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val resolvedWorkspaceRootDir: File by lazy { resolveKlsWorkspaceRootDir() }

  fun setup(processManager: KotlinLspConnection) {
    val workspaceRootDir = resolvedWorkspaceRootDir
    val workspaceRoot = workspaceRootDir.toPath().toUri().toString()
    KslLogs.infoThrottled(
        "kls:workspace-setup-root",
        5000L,
        "Setting up workspace with root: {} (projectRoot={})",
        workspaceRoot,
        workspace.getProjectDir().absolutePath,
    )
    val hasKotlinSources = hasKotlinSourceFiles(workspaceRootDir)
    if (hasKotlinSources) {
      Index.setKotlinStartupSession(true)
      Index.setIsIndexing(true)
      Index.setProgressMessage("Starting Kotlin language server...")
      LogStream.emitLineBlocking("Starting Kotlin language server...")
    } else {
      Index.setKotlinStartupSession(false)
      Index.setIsIndexing(false)
      KslLogs.infoThrottled(
          "kls:no-kotlin-sources",
          5000L,
          "No Kotlin source files found under {}; startup banner and symbol warm-up will be skipped",
          workspaceRootDir.absolutePath,
      )
    }


    LspFeatures.setProcessManager(processManager)
    initializeCompilerService()
    classpathProvider.initialize(compilerService)

    startBuildWatcher(processManager)

    val currentClasspath = classpathProvider.getClasspathList()
    val currentHash = indexCache.computeClasspathHash(currentClasspath)
    val cacheValid = indexCache.isCacheValid(currentHash)

    KslLogs.infoThrottled("kls:cache-status", 5000L, "Cache status: {}", if (cacheValid) "VALID" else "INVALID/MISSING")
    KslLogs.debugThrottled("kls:cache-stats", 5000L, "{}", indexCache.getCacheStats())

    backendConfigurator.beforeServerStart(processManager, classpathProvider)
    if (!processManager.startServer(classpathProvider)) {
      val message = "Kotlin language server failed to start; initialize request was not sent. Check KLS logs for launcher stderr and exit code."
      KslLogs.error(message)
      if (hasKotlinSources) {
        Index.setIsIndexing(false)
        Index.setProgressMessage("Kotlin language server failed to start")
        LogStream.emitLineBlocking(message)
      }
      return
    }

    val initParams = createInitParams(workspaceRoot)

    KslLogs.debugThrottled("kls:init-request", 5000L, "Sending initialize request...")

    processManager.sendRequest("initialize", initParams) { result ->
      KslLogs.infoThrottled("kls:init-success", 5000L, "Server initialized successfully")
      processManager.sendNotification("initialized", JsonObject())

      backendConfigurator.afterServerInitialized(processManager, classpathProvider)

      if (cacheValid) {
        restoreCachedIndex(processManager, hasKotlinSources)
      } else {
        triggerIndexing(processManager, workspaceRoot, currentHash, hasKotlinSources)
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

  private fun startBuildWatcher(processManager: KotlinLspConnection) {
    try {
      buildWatcher = FileSystems.getDefault().newWatchService()

      // Watch all Android module build directories
      val modulesToWatch = mutableListOf<File>()
      workspace.getSubProjects().filterIsInstance<AndroidModule>().forEach { module ->
        val buildDir = module.buildDir
        if (buildDir.exists()) {
          modulesToWatch.add(buildDir)
        }
      }

      if (modulesToWatch.isEmpty()) {
        KslLogs.warn("No build directories found to watch")
        return
      }

      // Register directories to watch
      val watchKeys = mutableMapOf<WatchKey, File>()
      modulesToWatch.forEach { buildDir ->
        try {
          val generatedDir = File(buildDir, "generated")
          if (generatedDir.exists()) {
            val key =
                generatedDir
                    .toPath()
                    .register(
                        buildWatcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE,
                    )
            watchKeys[key] = generatedDir
          KslLogs.infoThrottled(
              "kls:watch-build-changes",
              3000L,
              "Watching for build changes: {}",
              generatedDir.absolutePath,
          )
          }
        } catch (e: Exception) {
          KslLogs.warn("Failed to watch directory: {}", buildDir.absolutePath, e)
        }
      }

      // Start watcher coroutine
      watcherJob =
          watchScope.launch {
            var lastReloadTime = 250L
            val reloadDebounceMs = 5000L // Wait 5 seconds after last change

            while (isActive) {
              try {
                val key = buildWatcher?.poll(1, TimeUnit.SECONDS) ?: continue

                val events = key.pollEvents()
                if (events.isNotEmpty()) {
                  val now = System.currentTimeMillis()

                  // Log the changes
                  events.forEach { event ->
                    val kind = event.kind()
                    val filename = event.context()
                    KslLogs.debug("Build change detected: {} - {}", kind.name(), filename)
                  }

                  // Debounce: only reload after changes stop for 5 seconds
                  if (now - lastReloadTime > reloadDebounceMs) {
                    delay(reloadDebounceMs)

                    // Double check no new changes came in during delay
                    val checkKey = buildWatcher?.poll(100, TimeUnit.MILLISECONDS)
                    if (checkKey == null) {
              // No new changes, safe to reload
              KslLogs.infoThrottled(
                  "kls:build-reload",
                  2000L,
                  "Build changes detected, reloading classpath and index...",
              )
              reloadClasspathAndIndex(processManager)
              lastReloadTime = System.currentTimeMillis()
                    } else {
                      // New changes came in, reset timer
                      checkKey.reset()
                    }
                  }
                }

                key.reset()
              } catch (e: Exception) {
                if (e is CancellationException) break
                KslLogs.warn("Error in build watcher", e)
              }
            }
          }

      KslLogs.info("Build watcher started successfully")
    } catch (e: Exception) {
      KslLogs.error("Failed to start build watcher", e)
    }
  }
  private suspend fun reloadClasspathAndIndex(processManager: KotlinLspConnection) {
    withContext(Dispatchers.IO) {
      try {
        Index.setIsIndexing(true)  // Set flag when reload starts
        Index.setProgressMessage("Refreshing classpath and reindexing Kotlin symbols...")
        KslLogs.infoThrottled("kls:reload-start", 3000L, "=== RELOADING CLASSPATH AND INDEX ===")

        // Invalidate classpath cache
        classpathProvider.invalidateCache()

        // Re-run backend-specific startup preparation with refreshed classpath
        backendConfigurator.beforeServerStart(processManager, classpathProvider)

        // Clear index cache
        indexCache.clearCache()

        // Compute new classpath hash
        val currentClasspath = classpathProvider.getClasspathList()
        val currentHash = indexCache.computeClasspathHash(currentClasspath)

        // Trigger reindexing (this will also manage the Index flag)
        val workspaceRoot = resolvedWorkspaceRootDir.toPath().toUri().toString()
        triggerIndexing(processManager, workspaceRoot, currentHash)

        KslLogs.infoThrottled("kls:reload-success", 3000L, "Classpath and index reloaded successfully")
      } catch (e: Exception) {
        KslLogs.error("Failed to reload classpath and index", e)
        Index.setIsIndexing(false)  // Reset flag on error
      }
    }
  }


  private fun sendFwcdRuntimeConfig(processManager: KotlinLspConnection) {
    val configParams = createFwcdRuntimeConfig()
    processManager.sendNotification("workspace/didChangeConfiguration", configParams)
  }

  private fun restoreCachedIndex(processManager: KotlinLspConnection, showStartupBanner: Boolean) {
    if (!showStartupBanner) {
      sendFwcdRuntimeConfig(processManager)
      return
    }

    KslLogs.infoThrottled("kls:restore-cache-start", 5000L, "Restoring cached workspace-symbol snapshot...")
    Index.setIsIndexing(true)  // Set indexing flag when starting cache restoration
    Index.setProgressMessage("Restoring cached Kotlin symbols...")

    val cachedSymbols = indexCache.loadCache()
    if (cachedSymbols != null && cachedSymbols.size() > 0) {
      // This does not restore fwcd's internal SymbolIndex. It only reuses ACS' last
      // workspace/symbol snapshot for progress/UI purposes. Server-side indexing intentionally
      // remains enabled so completion, standard-library symbols and diagnostics stay correct.
      val configParams = createFwcdRuntimeConfig()

      processManager.sendNotification("workspace/didChangeConfiguration", configParams)
      KslLogs.infoThrottled(
          "kls:restore-cache-success",
          5000L,
          "Cache restored with {} symbols",
          cachedSymbols.size(),
      )
      Index.setProgressMessage("Restored cached index: ${cachedSymbols.size()} symbols")
      Index.setIsIndexing(false)  // Reset flag after cache restoration
    } else {
      // Cache load failed, trigger fresh indexing
      // Index flag will be managed by triggerIndexing
      val currentClasspath = classpathProvider.getClasspathList()
      val currentHash = indexCache.computeClasspathHash(currentClasspath)
      triggerIndexing(processManager, resolvedWorkspaceRootDir.toPath().toUri().toString(), currentHash, showStartupBanner)
    }
  }

  private fun triggerIndexing(
      processManager: KotlinLspConnection,
      workspaceRoot: String,
      classpathHash: String,
      showStartupBanner: Boolean = true,
  ) {
    KslLogs.infoThrottled("kls:trigger-indexing", 3000L, "Triggering classpath indexing...")
    if (!showStartupBanner) {
      sendFwcdRuntimeConfig(processManager)
      return
    }

    Index.setIsIndexing(true)  // Set indexing flag when starting
    Index.setProgressMessage("Indexing Kotlin symbols...")

    val configParams = createFwcdRuntimeConfig()

    processManager.sendNotification("workspace/didChangeConfiguration", configParams)

    // Request symbols to warm up and cache the index
    val symbolParams = JsonObject().apply { addProperty("query", "") }

    processManager.sendRequest("workspace/symbol", symbolParams) { result ->
      try {
        val symbols =
            when {
              result == null -> JsonArray()
              result.has("symbols") -> result.getAsJsonArray("symbols") ?: JsonArray()
              result.has("result") -> result.getAsJsonArray("result") ?: JsonArray()
              else -> JsonArray()
            }
        val symbolCount = symbols.size()
        KslLogs.infoThrottled("kls:indexing-complete", 3000L, "Indexing warm-up complete, found {} symbols", symbolCount)
        Index.setProgressMessage("Indexed ${symbolCount} symbols")

        // Save to cache
        if (symbolCount > 0) {
          indexCache.saveCache(symbols, classpathHash)
        }
      } catch (e: Exception) {
        KslLogs.warn("Failed to warm up Kotlin symbols", e)
        if (!Index.isKotlinStartupSessionActive()) {
          Index.setIsIndexing(false)
        }
      }
    }
  }

  fun cleanup() {
    try {
      // Stop build watcher
      watcherJob?.cancel()
      buildWatcher?.close()
      watchScope.cancel()

      compilerService?.destroy()
      KotlinCompilerProvider.getInstance().destroy()
      com.tom.rv2ide.lsp.kotlin.compiler.KotlinSourceFileManager.clearCache()
    } catch (e: Exception) {
      KslLogs.warn("Error cleaning up compiler service", e)
    }
  }

  // Add method to get cache instance for manual operations
  fun getIndexCache(): KotlinIndexCache = indexCache

  private fun hasKotlinSourceFiles(root: File): Boolean {
    if (!root.exists() || !root.isDirectory) return false

    return try {
      root.walkTopDown()
          .onEnter { dir -> !shouldSkipKotlinSourceScanDir(dir) }
          .any { file -> file.isFile && (file.extension == "kt" || file.extension == "kts") }
    } catch (e: Exception) {
      KslLogs.warn("Failed to scan Kotlin source files under {}", root.absolutePath, e)
      true
    }
  }

  private fun shouldSkipKotlinSourceScanDir(dir: File): Boolean {
    val name = dir.name
    return name == ".git" ||
        name == ".gradle" ||
        name == ".idea" ||
        name == "build" ||
        name == ".acside"
  }

  // Add method to manually trigger reload
  fun manualReloadClasspath(processManager: KotlinLspConnection) {
    watchScope.launch { reloadClasspathAndIndex(processManager) }
  }

  private fun initializeCompilerService() {
    try {
      val mainModule = findMainAndroidModule()
      if (mainModule != null) {
        compilerService = KotlinCompilerProvider.get(mainModule)
        KslLogs.info("Initialized compiler service for: {}", mainModule.path)
        LogStream.emitLineBlocking("Initialized compiler service for: ${mainModule.path}")
      } else {
        KslLogs.warn("No Android module found, using default compiler")
        compilerService = KotlinCompilerService.NO_MODULE_COMPILER
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to initialize compiler service", e)
      compilerService = KotlinCompilerService.NO_MODULE_COMPILER
    }
  }
  private fun resolveKlsWorkspaceRootDir(): File {
    val projectRoot = workspace.getProjectDir()
    if (backendId == KotlinLspBackendId.FWCD && projectRoot.exists() && projectRoot.isDirectory) {
      KslLogs.info(
          "Using project root as FWCD workspace root: {}",
          projectRoot.absolutePath,
      )
      return projectRoot
    }

    val mainModule = findMainAndroidModule()
    if (mainModule != null) {
      val moduleDir = mainModule.projectDir
      if (moduleDir.exists() && moduleDir.isDirectory) {
        if (backendId == KotlinLspBackendId.FWCD) {
          KslLogs.info(
              "Using main Android module as FWCD workspace root: {} (gradlePath={})",
              moduleDir.absolutePath,
              mainModule.path,
          )
          return moduleDir
        }

        KslLogs.info(
            "Using main Android module as KLS workspace root: {} (gradlePath={}, backend={})",
            moduleDir.absolutePath,
            mainModule.path,
            backendId.name.lowercase(),
        )
        return moduleDir
      }

      KslLogs.warn(
          "Main Android module directory is invalid, fallback to project root: dir={}, gradlePath={}, backend={}",
          moduleDir.absolutePath,
          mainModule.path,
          backendId.name.lowercase(),
      )
    }

    return workspace.getProjectDir()
  }

  private fun findMainAndroidModule(): ModuleProject? {
    val subProjects = workspace.getSubProjects()

    for (subProject in subProjects) {
      if (subProject is AndroidModule && subProject.isApplication) {
        return subProject
      }
    }

    for (subProject in subProjects) {
      if (subProject is AndroidModule) {
        return subProject
      }
    }

    return null
  }

  private fun createInitParams(workspaceRoot: String): JsonObject {
    KslLogs.debugThrottled("kls:init-params-create", 5000L, "=== CREATING INIT PARAMS ===")
    val workspaceRootPath = try {
      File(java.net.URI(workspaceRoot)).absolutePath
    } catch (_: Exception) {
      workspace.getProjectDir().absolutePath
    }

    val params =
        JsonObject().apply {
          addProperty("processId", android.os.Process.myPid())
          addProperty("rootUri", workspaceRoot)
          addProperty("rootPath", workspaceRootPath)
          add(
              "workspaceFolders",
              JsonArray().apply {
                add(
                    JsonObject().apply {
                      addProperty("uri", workspaceRoot)
                      addProperty("name", File(workspaceRootPath).name.ifEmpty { "workspace" })
                    },
                )
              },
          )

          add(
              "capabilities",
              JsonObject().apply {
                add(
                    "textDocument",
                    JsonObject().apply {
                      add(
                          "completion",
                          JsonObject().apply {
                            add(
                                "completionItem",
                                JsonObject().apply {
                                  addProperty("snippetSupport", true)
                                  addProperty("commitCharactersSupport", true)
                                  add(
                                      "documentationFormat",
                                      JsonArray().apply {
                                        add("markdown")
                                        add("plaintext")
                                      },
                                  )
                                  addProperty("deprecatedSupport", true)
                                  addProperty("preselectSupport", true)

                                  add(
                                      "resolveSupport",
                                      JsonObject().apply {
                                        add(
                                            "properties",
                                            JsonArray().apply {
                                              add("documentation")
                                              add("detail")
                                              add("additionalTextEdits")
                                            },
                                        )
                                      },
                                  )
                                },
                            )
                            addProperty("contextSupport", true)
                          },
                      )
                      add(
                          "hover",
                          JsonObject().apply {
                            add(
                                "contentFormat",
                                JsonArray().apply {
                                  add("markdown")
                                  add("plaintext")
                                },
                            )
                          },
                      )
                      add("definition", JsonObject().apply { addProperty("linkSupport", true) })
                      add("references", JsonObject())
                      add("signatureHelp", JsonObject())
                    },
                )

                add(
                    "workspace",
                    JsonObject().apply {
                      addProperty("applyEdit", true)
                      add(
                          "workspaceEdit",
                          JsonObject().apply { addProperty("documentChanges", true) },
                      )
                      add(
                          "didChangeConfiguration",
                          JsonObject().apply { addProperty("dynamicRegistration", true) },
                      )
                      add("symbol", JsonObject().apply { addProperty("dynamicRegistration", true) })
                    },
                )
              },
          )

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


          add("initializationOptions", initOptions)

          KslLogs.debugThrottled("kls:configured-classpath-count", 5000L, "Configured KLS with {} classpath entries", effectiveClassPaths.size)
          KslLogs.debugThrottled("kls:configured-java-source-roots", 5000L, "Configured KLS with {} java source roots", javaSourceRoots.size)
        }

    KslLogs.debugThrottled("kls:init-params-created", 5000L, "Full init params created with script support and formatting")
    return params
  }
}