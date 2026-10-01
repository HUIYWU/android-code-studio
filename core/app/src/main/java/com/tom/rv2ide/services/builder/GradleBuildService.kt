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
package com.tom.rv2ide.services.builder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import com.blankj.utilcode.util.ResourceUtils
import com.blankj.utilcode.util.ZipUtils
import com.tom.rv2ide.managers.ToolsManager

import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.BuildConfig
import com.tom.rv2ide.R.*
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.preferences.internal.DevOpsPreferences
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.projects.internal.ProjectManagerImpl
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.services.ToolingServerNotStartedException
import com.tom.rv2ide.services.builder.ToolingServerRunner.OnServerStartListener
import com.tom.rv2ide.tasks.ifCancelledOrInterrupted
import com.tom.rv2ide.tasks.runOnUiThread
import com.tom.rv2ide.tooling.api.ForwardingToolingApiClient
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.IToolingApiClient
import com.tom.rv2ide.tooling.api.IToolingApiServer
import com.tom.rv2ide.tooling.api.LogSenderConfig.PROPERTY_LOGSENDER_ENABLED
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.LogMessageParams
import com.tom.rv2ide.tooling.api.messages.TaskExecutionMessage
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult
import com.tom.rv2ide.tooling.api.messages.result.BuildInfo
import com.tom.rv2ide.tooling.api.messages.result.BuildResult
import com.tom.rv2ide.tooling.api.messages.result.GradleWrapperCheckResult
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidation
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidationRequest
import com.tom.rv2ide.tooling.api.models.ProjectCreationCapabilities
import com.tom.rv2ide.tooling.api.models.ToolingServerMetadata
import com.tom.rv2ide.tooling.events.ProgressEvent
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * A foreground service that handles interaction with the Gradle Tooling API.
 *
 * @author Akash Yadav
 */
class GradleBuildService :
    Service(), BuildService, IToolingApiClient, ToolingServerRunner.Observer {

  private var mBinder: GradleServiceBinder? = null
  private var isToolingServerStarted = false
  override var isBuildInProgress = false
    private set

  /**
   * We do not provide direct access to GradleBuildService instance to the Tooling API launcher as
   * it may cause memory leaks. Instead, we create another client object which forwards all calls to
   * us. So, when the service is destroyed, we release the reference to the service from this
   * client.
   */
  private var _toolingApiClient: ForwardingToolingApiClient? = null
  private var toolingServerRunner: ToolingServerRunner? = null
  private var outputReaderJob: Job? = null
  private var notificationManager: NotificationManager? = null
  private var server: IToolingApiServer? = null
  private var eventListener: EventListener? = null

  private val buildServiceScope =
      CoroutineScope(Dispatchers.Default + CoroutineName("GradleBuildService"))

  private val isGradleWrapperAvailable: Boolean
    get() {
      val projectManager = ProjectManagerImpl.getInstance()
      val projectDir = projectManager.projectDirPath
      if (TextUtils.isEmpty(projectDir)) {
        return false
      }

      val projectRoot = Objects.requireNonNull(projectManager.projectDir)
      if (!projectRoot.exists()) {
        return false
      }

      val gradlew = File(projectRoot, "gradlew")
      val gradleWrapperJar = File(projectRoot, "gradle/wrapper/gradle-wrapper.jar")
      val gradleWrapperProps = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
      return gradlew.exists() && gradleWrapperJar.exists() && gradleWrapperProps.exists()
    }

  companion object {

    private val log = LoggerFactory.getLogger(GradleBuildService::class.java)
    private val NOTIFICATION_ID = R.string.app_name
    private val SERVER_System_err = LoggerFactory.getLogger("ToolingApiErrorStream")
  }

  override fun onCreate() {
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    showNotification(getString(R.string.build_status_idle), false)
    Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, this)
  }

  override fun isToolingServerStarted(): Boolean {
    return isToolingServerStarted && server != null
  }

  private fun showNotification(
      message: String,
      @Suppress("SameParameterValue") isProgress: Boolean,
  ) {
    log.info("Showing notification to user...")
    createNotificationChannels()
    startForeground(NOTIFICATION_ID, buildNotification(message, isProgress))
  }

  private fun createNotificationChannels() {
    val buildNotificationChannel =
        NotificationChannel(
            BaseApplication.NOTIFICATION_GRADLE_BUILD_SERVICE,
            getString(string.title_gradle_service_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
    NotificationManagerCompat.from(this).createNotificationChannel(buildNotificationChannel)
  }

  private fun buildNotification(message: String, isProgress: Boolean): Notification {
    val ticker = getString(R.string.title_gradle_service_notification_ticker)
    val title = getString(R.string.title_gradle_service_notification)
    val launch = packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
    val intent = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT)
    val builder =
        Notification.Builder(this, BaseApplication.NOTIFICATION_GRADLE_BUILD_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_notification)
            .setTicker(ticker)
            .setWhen(System.currentTimeMillis())
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(intent)

    // Checking whether to add a ProgressBar to the notification
    if (isProgress) {
      // Add ProgressBar to Notification
      builder.setProgress(100, 0, true)
    }
    return builder.build()
  }

  override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
    // No point in restarting the service if it really gets killed.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    mBinder?.release()
    mBinder = null

    log.info("Service is being destroyed. Dismissing the shown notification...")
    notificationManager!!.cancel(NOTIFICATION_ID)

    val lookup = Lookup.getDefault()
    lookup.unregister(BuildService.KEY_BUILD_SERVICE)
    lookup.unregister(BuildService.KEY_PROJECT_PROXY)

    server?.also { server ->
      try {
        log.info("Shutting down Tooling API server...")
        // send the shutdown request but do not wait for the server to respond
        // the service should not block the onDestroy call in order to avoid timeouts
        // the tooling server must release resources and exit automatically
        server.shutdown().get(1, TimeUnit.SECONDS)
      } catch (e: Throwable) {
        log.error("Failed to shutdown Tooling API server", e)
      }
    }

    log.debug("Cancelling tooling server runner...")
    toolingServerRunner?.release()
    toolingServerRunner = null

    _toolingApiClient?.client = null
    _toolingApiClient = null

    log.debug("Cancelling tooling server output reader job...")
    outputReaderJob?.cancel()
    outputReaderJob = null

    isToolingServerStarted = false
  }

  override fun onBind(intent: Intent): IBinder? {
    if (mBinder == null) {
      mBinder = GradleServiceBinder(this)
    }
    return mBinder
  }

  override fun onListenerStarted(
      server: IToolingApiServer,
      projectProxy: IProject,
      errorStream: InputStream,
  ) {
    startServerOutputReader(errorStream)
    this.server = server
    Lookup.getDefault().update(BuildService.KEY_PROJECT_PROXY, projectProxy)
    isToolingServerStarted = true
  }

  override fun onServerExited(exitCode: Int) {
    log.warn("Tooling API process terminated with exit code: {}", exitCode)
    stopForeground(STOP_FOREGROUND_REMOVE)
  }

  override fun getClient(): IToolingApiClient {
    if (_toolingApiClient == null) {
      _toolingApiClient = ForwardingToolingApiClient(this)
    }
    return _toolingApiClient!!
  }

  override fun logMessage(params: LogMessageParams) {
    val logger = LoggerFactory.getLogger(params.tag)
    when (params.level) {
      'D' -> logger.debug(params.message)
      'W' -> logger.warn(params.message)
      'E' -> logger.error(params.message)
      'I' -> logger.info(params.message)

      else -> logger.trace(params.message)
    }
  }

  override fun logOutput(line: String) {
    eventListener?.onOutput(line)
  }

  override fun prepareBuild(buildInfo: BuildInfo) {
    updateNotification(getString(R.string.build_status_in_progress), true)
    eventListener?.prepareBuild(buildInfo)
  }

  override fun onBuildSuccessful(result: BuildResult) {
    updateNotification(getString(R.string.build_status_sucess), false)
    eventListener?.onBuildSuccessful(result.tasks)
  }

  override fun onBuildFailed(result: BuildResult) {
    updateNotification(getString(R.string.build_status_failed), false)
    eventListener?.onBuildFailed(result.tasks)
  }

  override fun onProgressEvent(event: ProgressEvent) {
    eventListener?.onProgressEvent(event)
  }

  override fun getBuildArguments(): CompletableFuture<List<String>> {
    val extraArgs = ArrayList<String>()
    val initScript = Environment.INIT_SCRIPT

    if (initScript.isFile && initScript.canRead() &&
        Environment.TOOLING_API_JAR.isFile && Environment.TOOLING_API_JAR.canRead()) {
      extraArgs.add("-Dandroidide.tooling.api.jar=${Environment.TOOLING_API_JAR.absolutePath}")
      extraArgs.add("--init-script")
      extraArgs.add(initScript.absolutePath)
    } else {
      log.warn("AndroidIDE init script is unavailable; path={}", initScript.absolutePath)
    }

    // Override AAPT2 binary
    extraArgs.add("-Pandroid.aapt2FromMavenOverride=" + Environment.AAPT2.absolutePath)
    extraArgs.add("-P${PROPERTY_LOGSENDER_ENABLED}=${DevOpsPreferences.logsenderEnabled}")

    if (BuildPreferences.isStacktraceEnabled) {
      extraArgs.add("--stacktrace")
    }
    if (BuildPreferences.isInfoEnabled) {
      extraArgs.add("--info")
    }
    if (BuildPreferences.isDebugEnabled) {
      extraArgs.add("--debug")
    }
    if (BuildPreferences.isScanEnabled) {
      extraArgs.add("--scan")
    }
    if (BuildPreferences.isWarningModeAllEnabled) {
      extraArgs.add("--warning-mode")
      extraArgs.add("all")
    }
    if (BuildPreferences.isBuildCacheEnabled) {
      extraArgs.add("--build-cache")
    }
    if (BuildPreferences.isOfflineEnabled) {
      extraArgs.add("--offline")
    }

    return CompletableFuture.completedFuture(extraArgs)
  }

  override fun checkGradleWrapperAvailability(): CompletableFuture<GradleWrapperCheckResult> {
    return if (isGradleWrapperAvailable)
        CompletableFuture.completedFuture(GradleWrapperCheckResult(true))
    else installWrapper()
  }

  internal fun setServerListener(listener: OnServerStartListener?) {
    if (toolingServerRunner != null) {
      toolingServerRunner!!.setListener(listener)
    }
  }

  private fun installWrapper(): CompletableFuture<GradleWrapperCheckResult> {
    eventListener?.also { eventListener ->
      eventListener.onOutput("-------------------- NOTE --------------------")
      eventListener.onOutput(getString(R.string.msg_installing_gradlew))
      eventListener.onOutput("----------------------------------------------")
    }
    return CompletableFuture.supplyAsync { doInstallWrapper() }
  }

  private fun doInstallWrapper(): GradleWrapperCheckResult {
    val extracted = File(Environment.TMP_DIR, "gradle-wrapper.zip")
    if (
        !ResourceUtils.copyFileFromAssets(
            ToolsManager.getCommonAsset("gradle-wrapper.zip"),
            extracted.absolutePath,
        )
    ) {
      log.error("Unable to extract gradle-plugin.zip from IDE resources.")
      return GradleWrapperCheckResult(false)
    }
    try {
      val projectDir = ProjectManagerImpl.getInstance().projectDir
      val files = ZipUtils.unzipFile(extracted, projectDir)
      if (files != null && files.isNotEmpty()) {
        return GradleWrapperCheckResult(true)
      }
    } catch (e: IOException) {
      log.error("An error occurred while extracting Gradle wrapper", e)
    }
    return GradleWrapperCheckResult(false)
  }

  private fun updateNotification(message: String, isProgress: Boolean) {
    runOnUiThread { doUpdateNotification(message, isProgress) }
  }

  private fun doUpdateNotification(message: String, isProgress: Boolean) {
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
        NOTIFICATION_ID,
        buildNotification(message, isProgress),
    )
  }

  override fun metadata(): CompletableFuture<ToolingServerMetadata> {
    checkServerStarted()
    return server!!.metadata()
  }

  override fun getProjectCreationCapabilities(): CompletableFuture<ProjectCreationCapabilities> {
    checkServerStarted()
    return server!!.getProjectCreationCapabilities().thenApply { it }
  }

  override fun validateModuleCreation(
      request: ModuleCreationValidationRequest
  ): CompletableFuture<ModuleCreationValidation> {
    checkServerStarted()
    return server!!.validateModuleCreation(request)
  }

  override fun initializeProject(
      params: InitializeProjectParams
  ): CompletableFuture<InitializeResult> {
    checkServerStarted()
    Objects.requireNonNull(params)
    return performBuildTasks(server!!.initialize(params)).thenApply { result ->
      if (result != null) {
        buildServiceScope.launch {
          try {
            kotlinx.coroutines.delay(5000) // 5 seconds
            log.info("5 seconds elapsed after initialization, stopping Gradle daemons...")
            // stopGradleDaemons().get()
          } catch (e: Exception) {
            log.error("Error in post-initialization daemon cleanup", e)
          }
        }
      }
      result
    }
  }

  /**
   * Stops all Gradle daemons by executing gradlew --stop
   */
  private fun stopGradleDaemons(): CompletableFuture<Void> {
    return CompletableFuture.runAsync {
      try {
        val projectDir = ProjectManagerImpl.getInstance().projectDir
        val gradlewPath = File(projectDir, "gradlew").absolutePath

        log.info("Stopping Gradle daemons...")

        val command = listOf("sh", gradlewPath, "--stop")
        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(projectDir)

        configureGradleEnvironment(processBuilder)

        val process = processBuilder.start()
        val exitCode = process.waitFor()
        
        if (exitCode == 0) {
          log.info("Gradle daemons stopped successfully")
          eventListener?.onOutput("Gradle daemons stopped")
        } else {
          log.warn("Failed to stop Gradle daemons, exit code: $exitCode")
        }
      } catch (e: Exception) {
        log.error("Error stopping Gradle daemons", e)
      }
    }
  }
  
  /**
   * Configures a Gradle-only process environment.
   *
   * Gradle annotation processors can load JNI libraries through [System.load]. Passing the
   * Termux-wide [LD_LIBRARY_PATH] makes Android's linker resolve unrelated Prefix libraries
   * (for example, libjpeg) into that dependency graph. Room's sqlite-jdbc verifier then fails on
   * devices whose system-extension JPEG library is incompatible with the Prefix variant.
   *
   * [LD_LIBRARY_PATH] is removed and [LD_PRELOAD] is pointed at the Termux exec intercept
   * library. The two are deliberately treated differently:
   *
   * - `LD_LIBRARY_PATH` must stay unset, otherwise the linker resolves unrelated Prefix
   *   libraries (for example, libjpeg) into the JNI dependency graph and Room's sqlite-jdbc
   *   verifier fails on devices whose system-extension JPEG library is incompatible with the
   *   Prefix variant.
   * - `LD_PRELOAD` must be set, otherwise scripts whose shebang is `#!/usr/bin/env sh` cannot
   *   be executed at all, because Android has no `/usr/bin/env`. This is what breaks
   *   `$NDK/ndk-build` (via its `build/ndk-build` child) for `ndkBuild` projects.
   */
  private fun configureGradleEnvironment(processBuilder: ProcessBuilder) {
    val termuxEnv = TermuxShellEnvironment().getEnvironment(this, false)
    val customEnv = HashMap<String, String>()
    Environment.putEnvironment(customEnv, false)

    val environment = processBuilder.environment()
    environment.putAll(termuxEnv)
    environment.putAll(customEnv)

    val currentPath = environment["PATH"].orEmpty()
    val requiredBinPaths = listOf(Environment.BIN_DIR.absolutePath, File(Environment.PREFIX, "bin").absolutePath)
    environment["PATH"] =
        (requiredBinPaths + currentPath.split(':'))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(":")
    environment["TMPDIR"] = Environment.TMP_DIR.absolutePath

    // Keep generic Termux environment setup intact for other process types, but isolate Gradle
    // and its workers from the Prefix-wide native-library search path.
    environment.remove("LD_LIBRARY_PATH")

    // Re-arm the Termux exec intercept for Gradle and everything it spawns.
    //
    // The IDE process itself never has LD_PRELOAD set: the only place it is ever exported is
    // `$PREFIX/bin/login`, which the integrated terminal runs but the build service does not.
    // Without it, AGP's `ndk-build` invocation reaches the kernel, which then fails to resolve
    // the shebang of `$NDK/build/ndk-build` (`#!/usr/bin/env sh`) because Android has no
    // `/usr/bin/env` - reported as "No such file or directory" with exit code 126.
    //
    // `libtermux-exec-ld-preload.so` intercepts execve() and resolves shebangs in userspace,
    // which is exactly how the terminal path has always worked. It links against libc only,
    // so it does not contribute to the library-resolution problem described above.
    val termuxExecPreload = File(Environment.LIB_DIR, "libtermux-exec-ld-preload.so")
    if (termuxExecPreload.isFile) {
      environment["LD_PRELOAD"] = termuxExecPreload.absolutePath
    }

    log.info(
        "Gradle env isolated: LD_LIBRARY_PATH removed, LD_PRELOAD={}, PATH={}",
        environment["LD_PRELOAD"] ?: "<unset>",
        environment["PATH"],
    )
  }

  override fun executeTasks(vararg tasks: String): CompletableFuture<TaskExecutionResult> {
    checkServerStarted()
    val tasksList = tasks.toList()

    /*
    * @idea Mohammed-Baqer-Null @ https://github.com/Mohammed-baqer-null
    * ! THIS IS A TEMPORARY FIX ! gradually transforming acs lite compiler in here properly in v..04 or 05

    * - Using the local Gradle wrapper (gradlew) is significantly faster than using the Tooling API.
    * - Employing the Tooling API for compilation on Android is a poor choice this is a resource-limited Android environment, not a desktop one.
    * - The Tooling API consumes excessive JVM memory without delivering meaningful benefits.
    * - The implementation below resolves OutOfMemory exceptions seamlessly.
    */

    return performBuildTasks(
        CompletableFuture.supplyAsync {
          val buildInfo = BuildInfo(tasksList)
          prepareBuild(buildInfo)

          try {
            val projectDir = ProjectManagerImpl.getInstance().projectDir
            val gradlewPath = File(projectDir, "gradlew").absolutePath

            val command = mutableListOf("sh", gradlewPath)
            command.addAll(tasks)

            val buildArgs = getBuildArguments().get()
            command.addAll(buildArgs)

            log.info("Executing command: ${command.joinToString(" ")}")

            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(projectDir)

            configureGradleEnvironment(processBuilder)

            val process = processBuilder.start()

            val outputReader = process.inputStream.bufferedReader()
            val errorReader = process.errorStream.bufferedReader()

            buildServiceScope.launch { outputReader.forEachLine { line -> logOutput(line) } }

            buildServiceScope.launch { errorReader.forEachLine { line -> logOutput(line) } }

            val exitCode = process.waitFor()

            val result =
                if (exitCode == 0) {
                  TaskExecutionResult(true, null)
                } else {
                  TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_FAILED)
                }

            if (result.isSuccessful) {
              onBuildSuccessful(BuildResult(tasksList))
            } else {
              onBuildFailed(BuildResult(tasksList))
            }

            result
          } catch (e: Exception) {
            log.error("Failed to execute gradlew with sh", e)
            val result = TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_FAILED)
            onBuildFailed(BuildResult(tasksList))
            result
          }
        }
    )

  }

  /**
   * Kills any running gradlew processes forcefully
   */
  private fun killGradlewProcesses() {
    try {
      log.info("Attempting to kill running gradlew processes...")

      // Use pkill to kill gradlew processes
      val command = listOf("pkill", "-f", "gradlew")
      val processBuilder = ProcessBuilder(command)

      val process = processBuilder.start()
      val exitCode = process.waitFor()

      if (exitCode == 0) {
        log.info("Gradlew processes killed successfully")
        eventListener?.onOutput("All Gradle build processes terminated")
      } else {
        log.info("No gradlew processes found or already terminated")
      }
    } catch (e: Exception) {
      log.error("Error killing gradlew processes", e)
    }
  }

  override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
    checkServerStarted()
    val cancellationFuture = server!!.cancelCurrentBuild()

    buildServiceScope.launch {
      try {
        kotlinx.coroutines.delay(1000) // Wait 1 second for graceful cancellation
        log.info("Force stopping Gradle daemons after build cancellation...")
        // stopGradleDaemons().get()
        killGradlewProcesses()
      } catch (e: Exception) {
        log.error("Error during forced daemon shutdown", e)
      }
    }
    
    return cancellationFuture
  }

  private fun <T> performBuildTasks(future: CompletableFuture<T>): CompletableFuture<T> {
    return CompletableFuture.runAsync(this::onPrepareBuildRequest)
        .handleAsync { _, _ ->
          try {
            return@handleAsync future.get()
          } catch (e: Throwable) {
            throw CompletionException(e)
          }
        }
        .handle(this::markBuildAsFinished)
  }

  private fun onPrepareBuildRequest() {
    checkServerStarted()
    ensureTmpdir()
    if (isBuildInProgress) {
      logBuildInProgress()
      throw BuildInProgressException()
    }
    isBuildInProgress = true
  }

  @Throws(ToolingServerNotStartedException::class)
  private fun checkServerStarted() {
    if (!isToolingServerStarted()) {
      throw ToolingServerNotStartedException()
    }
  }

  private fun ensureTmpdir() {
    Environment.mkdirIfNotExits(Environment.TMP_DIR)
  }

  private fun logBuildInProgress() {
    log.warn("A build is already in progress!")
  }

  @Suppress("UNUSED_PARAMETER")
  private fun <T> markBuildAsFinished(result: T, throwable: Throwable?): T {
    isBuildInProgress = false
    return result
  }

  internal fun startToolingServer(listener: OnServerStartListener?) {
    if (toolingServerRunner?.isStarted != true) {
      val envs = TermuxShellEnvironment().getEnvironment(this, false)
      toolingServerRunner = ToolingServerRunner(listener, this).also { it.startAsync(envs) }
      return
    }

    if (toolingServerRunner!!.isStarted && listener != null) {
      listener.onServerStarted(toolingServerRunner!!.pid!!)
    } else {
      setServerListener(listener)
    }
  }

  fun setEventListener(eventListener: EventListener?): GradleBuildService {
    if (eventListener == null) {
      this.eventListener = null
      return this
    }
    this.eventListener = wrap(eventListener)
    return this
  }

  private fun wrap(listener: EventListener?): EventListener? {
    return if (listener == null) {
      null
    } else
        object : EventListener {
          override fun prepareBuild(buildInfo: BuildInfo) {
            runOnUiThread { listener.prepareBuild(buildInfo) }
          }

          override fun onBuildSuccessful(tasks: List<String?>) {
            runOnUiThread { listener.onBuildSuccessful(tasks) }
          }

          override fun onProgressEvent(event: ProgressEvent) {
            runOnUiThread { listener.onProgressEvent(event) }
          }

          override fun onBuildFailed(tasks: List<String?>) {
            runOnUiThread { listener.onBuildFailed(tasks) }
          }

          override fun onOutput(line: String?) {
            runOnUiThread { listener.onOutput(line) }
          }
        }
  }

  private fun startServerOutputReader(input: InputStream) {
    if (outputReaderJob?.isActive == true) {
      return
    }

    outputReaderJob =
        buildServiceScope.launch(Dispatchers.IO + CoroutineName("ToolingServerErrorReader")) {
          val reader = input.bufferedReader()
          try {
            reader.forEachLine { line -> SERVER_System_err.error(line) }
          } catch (e: Throwable) {
            e.ifCancelledOrInterrupted(suppress = true) {
              // will be suppressed
              return@launch
            }

            // log the error and fail silently
            log.error("Failed to read tooling server output", e)
          }
        }
  }

  /** Handles events received from a Gradle build. */
  interface EventListener {

    /**
     * Called just before a build is started.
     *
     * @param buildInfo The information about the build to be executed.
     * @see IToolingApiClient.prepareBuild
     */
    fun prepareBuild(buildInfo: BuildInfo)

    /**
     * Called when a build is successful.
     *
     * @param tasks The tasks that were run.
     * @see IToolingApiClient.onBuildSuccessful
     */
    fun onBuildSuccessful(tasks: List<String?>)

    /**
     * Called when a progress event is received from the Tooling API server.
     *
     * @param event The event model describing the event.
     */
    fun onProgressEvent(event: ProgressEvent)

    /**
     * Called when a build fails.
     *
     * @param tasks The tasks that were run.
     * @see IToolingApiClient.onBuildFailed
     */
    fun onBuildFailed(tasks: List<String?>)

    /**
     * Called when the output line is received.
     *
     * @param line The line of the build output.
     */
    fun onOutput(line: String?)
  }
}
