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

package com.tom.rv2ide.language.services.kotlin.workspace

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendContext
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projectdata.logs.LogStream
import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class KotlinBackendWorkspaceCoordinator(
    private val workspace: IWorkspace,
    private val backendContext: KotlinBackendContext,
    private val backendConfigurator: KotlinBackendConfigurator,
) {


  @Volatile private var closed = false
  private val classpathProvider = backendContext.classpathProvider

  private var buildWatcher: WatchService? = null
  private var watcherJob: Job? = null
  private val watchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val resolvedWorkspaceRootDir: File by lazy { backendConfigurator.resolveWorkspaceRoot() }

  fun setup(connection: KotlinBackendConnection, onInitialized: (Boolean) -> Unit = {}) {
    if (closed) return
    val alreadyInitialized = connection.isReady && connection.isInitialized

    val workspaceRootDir = resolvedWorkspaceRootDir
    val workspaceRoot = workspaceRootDir.toPath().toUri().toString()
    KlsLogs.infoThrottled(
        "kls:workspace-setup-root",
        5000L,
        "Setting up workspace with root: {} (projectRoot={})",
        workspaceRoot,
        workspace.getProjectDir().absolutePath,
    )
    val hasSupportedDocuments = hasSupportedKotlinDocuments(workspaceRootDir, connection)
    if (hasSupportedDocuments) {
      LogStream.emitLineBlocking("Starting Kotlin language server...")
    } else {
      KlsLogs.infoThrottled(
          "kls:no-kotlin-sources",
          5000L,
          "No Kotlin source files found under {}; symbol warm-up will be skipped",
          workspaceRootDir.absolutePath,
      )
    }



    startBuildWatcher(connection)

    backendConfigurator.beforeBackendStart(connection)
    if (!connection.startBackend(classpathProvider)) {
      val message = "Kotlin language server failed to start; initialize request was not sent. Check KLS logs for launcher stderr and exit code."
      KlsLogs.error(message)
      if (hasSupportedDocuments) {
        LogStream.emitLineBlocking(message)
      }
      onInitialized(false)
      return
    }

    if (alreadyInitialized && connection.isInitialized) {
      backendConfigurator.onEnvironmentRefreshed(connection)
      onInitialized(true)
      return
    }

    val initParams = createInitParams(workspaceRoot)

    KlsLogs.debugThrottled("kls:init-request", 5000L, "Sending initialize request...")

    connection.initialize(initParams) { result ->
      if (closed) return@initialize
      val capabilities = result?.get("capabilities")?.takeIf { it.isJsonObject }?.asJsonObject
      if (result == null || capabilities == null) {
        val message = "Kotlin language server initialize failed; backend did not return capabilities."
        KlsLogs.error(message)
        if (hasSupportedDocuments) {
          LogStream.emitLineBlocking(message)
        }
        connection.markInitializationFailed()
        onInitialized(false)
        return@initialize
      }

      KlsLogs.infoThrottled("kls:init-success", 5000L, "Server initialized successfully")
      connection.sendNotification("initialized", JsonObject())
      connection.markInitialized()
      onInitialized(true)

      backendConfigurator.afterBackendInitialized(connection, hasSupportedDocuments)
    }
  }

  private fun startBuildWatcher(connection: KotlinBackendConnection) {
    try {
      if (closed || buildWatcher != null) return
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
        KlsLogs.warn("No build directories found to watch")
        buildWatcher?.close()
        buildWatcher = null
        return
      }

      // Register directories to watch
      modulesToWatch.forEach { buildDir ->
        try {
          val generatedDir = File(buildDir, "generated")
          if (generatedDir.exists()) {
            generatedDir
                .toPath()
                .register(
                    buildWatcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
          KlsLogs.infoThrottled(
              "kls:watch-build-changes",
              3000L,
              "Watching for build changes: {}",
              generatedDir.absolutePath,
          )
          }
        } catch (e: Exception) {
          KlsLogs.warn("Failed to watch directory: {}", buildDir.absolutePath, e)
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
                    KlsLogs.debug("Build change detected: {} - {}", kind.name(), filename)
                  }

                  // Debounce: only reload after changes stop for 5 seconds
                  if (now - lastReloadTime > reloadDebounceMs) {
                    delay(reloadDebounceMs)

                    // Double check no new changes came in during delay
                    val checkKey = buildWatcher?.poll(100, TimeUnit.MILLISECONDS)
                    if (checkKey == null) {
              // No new changes, safe to reload
              KlsLogs.infoThrottled(
                  "kls:build-reload",
                  2000L,
                  "Build changes detected, refreshing Kotlin backend environment...",
              )
              refreshBackendEnvironment(connection)
              lastReloadTime = System.currentTimeMillis()
                    } else {
                      // New changes came in, reset timer
                      checkKey.reset()
                    }
                  }
                }

                key.reset()
              } catch (e: Exception) {
                if (e is CancellationException || e is ClosedWatchServiceException) break
                KlsLogs.warn("Error in build watcher", e)
              }
            }
          }

      KlsLogs.info("Build watcher started successfully")
    } catch (e: Exception) {
      KlsLogs.error("Failed to start build watcher", e)
    }
  }
  private suspend fun refreshBackendEnvironment(connection: KotlinBackendConnection) {
    withContext(Dispatchers.IO) {
      try {
        KlsLogs.infoThrottled("kls:reload-start", 3000L, "=== REFRESHING KOTLIN BACKEND ENVIRONMENT ===")
        classpathProvider.invalidateCache()

        val refreshed = connection.refreshEnvironment(classpathProvider)
        if (!refreshed) {
          KlsLogs.error("Kotlin backend environment refresh failed")
          return@withContext
        }

        backendConfigurator.onEnvironmentRefreshed(connection)
        KlsLogs.infoThrottled(
            "kls:reload-success",
            3000L,
            "Kotlin backend environment refreshed successfully",
        )
      } catch (e: Exception) {
        KlsLogs.error("Failed to refresh Kotlin backend environment", e)
      }
    }
  }

  fun cleanup() {
    if (closed) return
    closed = true
    try {
      // Stop build watcher
      watcherJob?.cancel()
      buildWatcher?.close()
      watchScope.cancel()

    } catch (e: Exception) {
      KlsLogs.warn("Error closing Kotlin workspace coordinator", e)
    }
  }

  private fun hasSupportedKotlinDocuments(root: File, connection: KotlinBackendConnection): Boolean {
    if (!root.exists() || !root.isDirectory) return false

    return try {
      root.walkTopDown()
          .onEnter { dir -> !shouldSkipKotlinSourceScanDir(dir) }
          .any { file -> file.isFile && connection.supportsDocument(file.toPath()) }
    } catch (e: Exception) {
      KlsLogs.warn("Failed to scan supported Kotlin files under {}", root.absolutePath, e)
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

  private fun createInitParams(workspaceRoot: String): JsonObject {
    KlsLogs.debugThrottled("kls:init-params-create", 5000L, "=== CREATING INIT PARAMS ===")
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

          backendConfigurator.initializationOptions()?.let { add("initializationOptions", it) }
        }

    KlsLogs.debugThrottled("kls:init-params-created", 5000L, "Full init params created with script support and formatting")
    return params
  }
}
