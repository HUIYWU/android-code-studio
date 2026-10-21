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

package com.tom.rv2ide.tooling.impl

import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.IToolingApiClient
import com.tom.rv2ide.tooling.api.IToolingApiServer
import com.tom.rv2ide.tooling.api.messages.GradleDistributionParams
import com.tom.rv2ide.tooling.api.messages.GradleDistributionType
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.TaskExecutionMessage
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult.Reason.CANCELLATION_ERROR
import com.tom.rv2ide.tooling.api.messages.result.BuildInfo
import com.tom.rv2ide.tooling.api.messages.result.BuildResult
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.BUILD_CANCELLED
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.BUILD_FAILED
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.CONNECTION_CLOSED
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.CONNECTION_ERROR
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_DIRECTORY_INACCESSIBLE
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_DIRECTORY
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_FOUND
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_INITIALIZED
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.UNKNOWN
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.UNSUPPORTED_BUILD_ARGUMENT
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.UNSUPPORTED_CONFIGURATION
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult.Failure.UNSUPPORTED_GRADLE_VERSION
import com.tom.rv2ide.tooling.api.models.DefaultApplicationProjectInfo
import com.tom.rv2ide.tooling.api.models.DefaultProjectCreationCapabilities
import com.tom.rv2ide.tooling.api.models.GradleTask
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidation
import com.tom.rv2ide.tooling.api.models.ModuleCreationValidationRequest
import com.tom.rv2ide.tooling.api.models.ProjectCreationCapabilities
import com.tom.rv2ide.tooling.api.models.ToolingServerMetadata

import com.tom.rv2ide.tooling.impl.internal.AndroidProjectImpl
import com.tom.rv2ide.tooling.impl.internal.ProjectImpl
import com.tom.rv2ide.tooling.impl.net.SimpleHttpProxy
import com.tom.rv2ide.tooling.impl.sync.ModelBuilderException
import com.tom.rv2ide.tooling.impl.sync.KotlinDslModelReader
import com.tom.rv2ide.tooling.impl.sync.RootModelBuilder
import com.tom.rv2ide.tooling.impl.sync.RootProjectModelBuilderParams
import com.tom.rv2ide.utils.StopWatch
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.BuildException
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.tooling.exceptions.UnsupportedBuildArgumentException
import org.gradle.tooling.exceptions.UnsupportedOperationConfigurationException
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.slf4j.LoggerFactory

/**
 * Implementation for the Gradle Tooling API server.
 *
 * @author Akash Yadav
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
internal class ToolingApiServerImpl(private val project: ProjectImpl) : IToolingApiServer {

  private var client: IToolingApiClient? = null
  private var connector: GradleConnector? = null
  private var connection: ProjectConnection? = null
  private var lastInitParams: InitializeProjectParams? = null
  private var _buildCancellationToken: CancellationTokenSource? = null
  private var httpProxy: SimpleHttpProxy? = null

  private val cancellationTokenAccessLock = ReentrantLock(/* fair= */ true)
  private var buildCancellationToken: CancellationTokenSource?
    get() = cancellationTokenAccessLock.withLock { _buildCancellationToken }
    set(value) = cancellationTokenAccessLock.withLock { _buildCancellationToken = value }

  /** Whether the project has been initialized or not. */
  var isInitialized: Boolean = false
    private set

  /** Whether a build or project synchronization is in progress. */
  private var isBuildInProgress: Boolean = false

  /** Whether the server has a live connection to Gradle. */
  val isConnected: Boolean
    get() = connector != null || connection != null

  companion object {

    private val log = LoggerFactory.getLogger(ToolingApiServerImpl::class.java)

    /**
     * Time duration for which the the Tooling API server waits after calling
     * [DefaultGradleConnector.close] and before exiting the server's process.
     *
     * This delay should be long enough to let the tooling API stop the daemon but short enough so
     * that the server's process is not kept alive for longer duration.
     */
    const val DELAY_BEFORE_EXIT_MS = 1000L
  }

  override fun metadata(): CompletableFuture<ToolingServerMetadata> {
    return CompletableFuture.supplyAsync {
      ToolingServerMetadata(ProcessHandle.current().pid().toInt())
    }
  }

  override fun initialize(params: InitializeProjectParams): CompletableFuture<InitializeResult> {
    return runBuild {
      try {
        log.debug("Received project initialization request with params: {}", params)

        Main.checkGradleWrapper()

        if (buildCancellationToken != null) {
          cancelCurrentBuild().get()
        }

        val projectDirectory = File(params.directory)
        val failureReason = validateProjectDirectory(projectDirectory)

        if (failureReason != null) {
          log.error("Cannot initialize project: {}", failureReason)
          return@runBuild InitializeResult(false, failureReason)
        }

        // Ensure Gradle sees UTF-8/locale early via project gradle.properties
        ensureProjectGradleProperties(projectDirectory)

        val stopWatch = StopWatch("Connection to project")
        val isReinitializing = connector != null && connection != null && params == lastInitParams

        if (isReinitializing) {
          log.info("Project is being reinitialized")
          log.info("Reusing connector instance...")
        } else {
          // a new project is being initialized
          // or the project is being initialized with different parameters
          connector?.disconnect()

          connector = GradleConnector.newConnector().forProjectDirectory(projectDirectory)
          setupConnectorForGradleInstallation(this.connector!!, params.gradleDistribution)
          stopWatch.lap("Connector created")
        }

        lastInitParams = params

        val connector =
            checkNotNull(connector) {
              "Unable to create gradle connector for project directory: ${params.directory}"
            }

        notifyBeforeBuild(BuildInfo(emptyList()))

        if (isReinitializing) {
          log.info("Reusing project connection...")
        } else {
          connection = connector.connect()
        }

        val connection =
            checkNotNull(this.connection) {
              "Unable to create project connection for project directory: ${params.directory}"
            }

        stopWatch.lapFromLast("Project connection established")

        this.buildCancellationToken = GradleConnector.newCancellationTokenSource()

        // start local HTTP proxy for Gradle network (before exposing port)
        if (httpProxy == null) {
          httpProxy = SimpleHttpProxy(0).also { it.start() }
        }
        // Expose proxy port to child process via system property
        val proxyPort = httpProxy?.port ?: -1
        if (proxyPort > 0) {
          try {
            System.setProperty("ANDROIDIDE_PROXY_PORT", proxyPort.toString())
          } catch (_: Throwable) {}
        }

        var project =
            try {
              val modelBuilderParams =
                  RootProjectModelBuilderParams(connection, this.buildCancellationToken!!.token())
              val impl =
                   RootModelBuilder(params).build(modelBuilderParams) as? ProjectImpl?
                       ?: throw ModelBuilderException("Failed to build project model")
               val kotlinDslModel =
KotlinDslModelReader.read(
                        connection = connection,
                        cancellationToken = this.buildCancellationToken!!.token(),
                        buildRoot = projectDirectory,
                        gradleHome = params.gradleDistribution
                            .takeIf { it.type == GradleDistributionType.GRADLE_INSTALLATION }
                            ?.value
                            ?.let(::File)
                            ?.takeIf { it.isDirectory },
                    )
               impl.setGradleKotlinDslModel(kotlinDslModel)
               impl
             } catch (err: Throwable) {
              throw err
            }

        stopWatch.lapFromLast("Project read successful")
        val warmupTasks = collectAndroidWarmupTasks(project)
        if (warmupTasks.isNotEmpty()) {
          log.info("Running Android source warm-up as part of initialize: {}", warmupTasks)
          try {
            runWarmupBuild(connection, warmupTasks)
            stopWatch.lapFromLast("Android source warm-up completed")
            // Intentionally keep using the model that was read at the start of initialize.
            // A second full RootModelBuilder pass after warm-up proved unstable in practice,
            // while KLS can still recover generated sources from the original model roots plus
            // filesystem candidates under build/generated and build/intermediates.
            log.info("Using pre-warmup project model after Android source warm-up; generated sources are supplied via model outputs and filesystem fallback")
          } catch (warmupError: Throwable) {
            log.error("Android source warm-up failed during initialize. Continuing with pre-warmup model. tasks={}", warmupTasks, warmupError)
          }
        } else {
          log.info("Skipping Android source warm-up during initialize: critical generated outputs already exist")
        }

        val nativeWarmupTasks = collectAndroidNativeCompileCommandsWarmupTasks(project)
        if (nativeWarmupTasks.isNotEmpty()) {
          try {
            runAndroidNativeCompileCommandsWarmup(connection, project, nativeWarmupTasks)
          } catch (nativeWarmupError: Throwable) {
            log.error(
                "Android native configuration warm-up failed; continuing project initialization. task={}",
                nativeWarmupTasks.first(),
                nativeWarmupError,
            )
          }
        }

        stopWatch.log()

        this.project.setFrom(project)
        this.isInitialized = true

        notifyBuildSuccess(emptyList())
        return@runBuild InitializeResult(true)
      } catch (err: Throwable) {
        log.error(
          "Failed to initialize project: type={} message={} causeType={} causeMessage={} rootCauseType={} rootCauseMessage={}",
          err.javaClass.name,
          err.message,
          err.cause?.javaClass?.name,
          err.cause?.message,
          generateSequence(err as Throwable?) { it.cause }.lastOrNull()?.javaClass?.name,
          generateSequence(err as Throwable?) { it.cause }.lastOrNull()?.message,
          err,
        )
        notifyBuildFailure(emptyList())
        return@runBuild InitializeResult(false, getTaskFailureType(err))
      }

    }
  }

  private fun ensureProjectGradleProperties(projectDir: File) {
    try {
      val propsFile = File(projectDir, "gradle.properties")
      val props = java.util.Properties()
      if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }

      val jvmArgsKey = "org.gradle.jvmargs"
      val enforced =
          "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Duser.language=en -Duser.country=US"
      val current = props.getProperty(jvmArgsKey)?.trim().orEmpty()
      if (!current.contains("file.encoding")) {
        props.setProperty(jvmArgsKey, if (current.isBlank()) enforced else "$current $enforced")
      }
      props.setProperty("systemProp.file.encoding", "UTF-8")
      props.setProperty("systemProp.sun.jnu.encoding", "UTF-8")
      props.setProperty("systemProp.user.language", "en")
      props.setProperty("systemProp.user.country", "US")

      propsFile.outputStream().use { out ->
        props.store(out, "AndroidIDE: enforce UTF-8 & locale for Gradle daemon")
      }
    } catch (_: Throwable) {
      // best-effort; ignore
    }
  }

  private fun validateProjectDirectory(projectDirectory: File) =
      when {
        !projectDirectory.exists() -> PROJECT_NOT_FOUND
        !projectDirectory.isDirectory -> PROJECT_NOT_DIRECTORY
        !projectDirectory.canRead() -> PROJECT_DIRECTORY_INACCESSIBLE
        else -> null
      }

  override fun isServerInitialized(): CompletableFuture<Boolean> {
    return CompletableFuture.supplyAsync { isInitialized }
  }

  override fun getRootProject(): CompletableFuture<IProject> {
    return CompletableFuture.supplyAsync {
      assertProjectInitialized()
      return@supplyAsync this.project
    }
  }

  override fun getProjectCreationCapabilities(): CompletableFuture<DefaultProjectCreationCapabilities> {
    return runBuild {
      assertProjectInitialized()
      val connection =
          checkNotNull(this.connection) {
            "ProjectConnection has not been initialized. Cannot inspect module creation capabilities."
          }
      val executor = connection.action { controller -> controller.getModel(ProjectCreationCapabilities::class.java) }
      Main.finalizeLauncher(executor)
      this.buildCancellationToken = GradleConnector.newCancellationTokenSource()
      executor.withCancellationToken(this.buildCancellationToken!!.token())
      try {
        val capabilities = executor.run()
        // Gradle returns custom models as dynamic proxies. Copy every interface model into RPC DTOs
        // so Gson never receives a Gradle-owned proxy.
        DefaultProjectCreationCapabilities(
            projectRoot = capabilities.projectRoot,
            settingsDsl = capabilities.settingsDsl,
            applicationProjects = capabilities.applicationProjects.toList(),
            applicationProjectDetails = capabilities.applicationProjectDetails.map { application ->
              DefaultApplicationProjectInfo(
                  gradlePath = application.gradlePath,
                  projectDirectory = application.projectDirectory,
                  buildFile = application.buildFile,
              )
            },
        )
      } catch (error: Throwable) {
        log.warn("Failed to read project creation capabilities from Gradle", error)
        throw error
      } finally {
        this.buildCancellationToken = null
      }
    }
  }

  override fun validateModuleCreation(
      request: ModuleCreationValidationRequest
  ): CompletableFuture<ModuleCreationValidation> {
    return runBuild {
      assertProjectInitialized()
      val connection = checkNotNull(this.connection) { "ProjectConnection has not been initialized." }
      val probePath = request.modulePath.normalizedProbePath()
      val probe = createModuleCreationProbe(request)
      try {
        val executor =
            connection.action { controller ->
              controller.getModel(ProjectCreationCapabilities::class.java)
            }
        Main.finalizeLauncher(executor)
        executor.addArguments(
            "-Pandroidide.moduleCreationProbePath=$probePath",
            "-Pandroidide.moduleCreationProbeDirectory=${probe.rootDirectory.absolutePath}",
        )
        this.buildCancellationToken = GradleConnector.newCancellationTokenSource()
        executor.withCancellationToken(this.buildCancellationToken!!.token())
        executor.run()
        ModuleCreationValidation(isValid = true)
      } catch (error: Throwable) {
        ModuleCreationValidation(isValid = false, message = error.rootCauseMessage())
      } finally {
        this.buildCancellationToken = null
        // Probe files are owned by tooling and must not survive cancellation or a Gradle failure.
        if (!probe.rootDirectory.deleteRecursively()) {
          log.warn("Unable to delete module creation probe directory: {}", probe.rootDirectory)
        }
      }
    }
  }

  private fun String.normalizedProbePath(): String {
    val segments = trim().trim(':').split(':').filter(String::isNotBlank)
    require(segments.isNotEmpty() && segments.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) }) {
      "Module path must contain Gradle-safe path segments."
    }
    return ":${segments.joinToString(":")}"
  }

  private data class ModuleCreationProbe(
      val rootDirectory: File,
  )

  private fun createModuleCreationProbe(request: ModuleCreationValidationRequest): ModuleCreationProbe {
    val rootDirectory =
        File(System.getProperty("java.io.tmpdir"), "androidide-module-probes/${System.nanoTime()}")
    check(rootDirectory.mkdirs()) { "Unable to create module creation probe directory." }
    val segments = request.modulePath.trim().trim(':').split(':').filter(String::isNotBlank)
    require(segments.isNotEmpty() && segments.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) }) {
      "Module path must contain Gradle-safe path segments."
    }
    val moduleDirectory = segments.fold(rootDirectory) { directory, segment -> File(directory, segment) }
    check(moduleDirectory.mkdirs()) { "Unable to create module creation probe module directory." }
    val buildFile = File(moduleDirectory, request.buildFileName)
    buildFile.writeText(request.buildScript)
    return ModuleCreationProbe(rootDirectory)
  }
  /** The probe validates the exact build script prepared by the application. */


  private fun Throwable.rootCauseMessage(): String {
    var cause = this
    while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
    return cause.message ?: cause.javaClass.simpleName
  }

  override fun executeTasks(message: TaskExecutionMessage): CompletableFuture<TaskExecutionResult> {
    return runBuild {
      if (!isServerInitialized().get()) {
        log.error("Cannot execute tasks: {}", PROJECT_NOT_INITIALIZED)
        return@runBuild TaskExecutionResult(false, PROJECT_NOT_INITIALIZED)
      }

      val lastInitParams = this.lastInitParams
      if (lastInitParams != null) {
        val projectDirectory = File(lastInitParams.directory)
        val failureReason = validateProjectDirectory(projectDirectory)
        if (failureReason != null) {
          log.error("Cannot execute tasks: {}", failureReason)
          return@runBuild TaskExecutionResult(isSuccessful = false, failureReason)
        }
      }

      log.debug("Received request to run tasks: {}", message)

      Main.checkGradleWrapper()

      val connection =
          checkNotNull(this.connection) {
            "ProjectConnection has not been initialized. Cannot execute tasks."
          }

      val builder = connection.newBuild()

      // System.in and System.out are used for communication between this server and the
      // client.
      val out = LoggingOutputStream()
      builder.setStandardInput("NoOp".byteInputStream())
      builder.setStandardError(out)
      builder.setStandardOutput(out)
      builder.forTasks(*message.tasks.filter { it.isNotBlank() }.toTypedArray())
      Main.finalizeLauncher(builder)

      this.buildCancellationToken = GradleConnector.newCancellationTokenSource()
      builder.withCancellationToken(this.buildCancellationToken!!.token())

      notifyBeforeBuild(BuildInfo(message.tasks))

      try {
        builder.run()
        this.buildCancellationToken = null
        notifyBuildSuccess(message.tasks)
        return@runBuild TaskExecutionResult.SUCCESS
      } catch (error: Throwable) {
        notifyBuildFailure(message.tasks)
        return@runBuild TaskExecutionResult(false, getTaskFailureType(error))
      }
    }
  }
  private enum class AndroidGeneratedOutputCategory {
    CORE_RESOURCES,
    CORE_SOURCES,
    BINDING_SOURCES,
    AIDL_SOURCES,
    KAPT_SOURCES,
    NAVIGATION_SOURCES,
  }

  private data class AndroidWarmupContext(
      val projectPath: String,
      val variantNameCapitalized: String,
      val metadata: com.tom.rv2ide.tooling.api.models.AndroidProjectMetadata?,
      val artifact: com.tom.rv2ide.tooling.api.models.AndroidArtifactMetadata,
      val availableTasks: List<com.tom.rv2ide.tooling.api.models.GradleTask>,
      val generatedRootsOnDisk: Set<String>,
  )

  private fun collectAndroidWarmupTasks(project: ProjectImpl): List<String> {
    val tasks = linkedSetOf<String>()

    project.projects.filterIsInstance<AndroidProjectImpl>().forEach { androidProject ->
      val configuredVariant = androidProject.getConfiguredVariant().get().orEmpty().ifBlank { "debug" }
      val variant =
          androidProject
              .getVariant(com.tom.rv2ide.tooling.api.models.params.StringParameter(configuredVariant))
              .get() ?: return@forEach
      val rawMetadata = androidProject.getMetadata().get()
      val metadata = rawMetadata as? com.tom.rv2ide.tooling.api.models.AndroidProjectMetadata
      val projectPath = rawMetadata.projectPath
      val variantNameCapitalized =
          configuredVariant.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

      val context =
          AndroidWarmupContext(
              projectPath = projectPath,
              variantNameCapitalized = variantNameCapitalized,
              metadata = metadata,
artifact = variant.mainArtifact,
               availableTasks = androidProject.getTasks().get().orEmpty(),
               generatedRootsOnDisk = collectExistingGeneratedRoots(variant.mainArtifact),
          )

      // Warm-up is driven by generated output categories instead of a single databinding task.
      // Core resource/source tasks stay generic, while binding and other generators are treated as
      // conditional additions when their outputs are missing.
      val missingCategories = determineMissingGeneratedOutputCategories(context)
      if (missingCategories.isEmpty()) {
        return@forEach
      }

      collectCoreWarmupTasks(context, missingCategories, tasks)
      collectConditionalWarmupTasks(context, missingCategories, tasks)
    }

    return tasks.toList()
  }

  private fun determineMissingGeneratedOutputCategories(
      context: AndroidWarmupContext,
  ): Set<AndroidGeneratedOutputCategory> {
    val missing = linkedSetOf<AndroidGeneratedOutputCategory>()

    if (!hasCoreResourceOutputs(context.generatedRootsOnDisk)) {
      missing.add(AndroidGeneratedOutputCategory.CORE_RESOURCES)
    }
    if (!hasCoreSourceOutputs(context.generatedRootsOnDisk)) {
      missing.add(AndroidGeneratedOutputCategory.CORE_SOURCES)
    }
    if (shouldCheckBindingOutputs(context) && !hasBindingOutputs(context.generatedRootsOnDisk)) {
      missing.add(AndroidGeneratedOutputCategory.BINDING_SOURCES)
    }
    if (shouldCheckAidlOutputs(context) && !hasAidlOutputs(context.generatedRootsOnDisk)) {
      missing.add(AndroidGeneratedOutputCategory.AIDL_SOURCES)
    }
    if (shouldCheckKaptOutputs(context) && !hasKaptOutputs(context.generatedRootsOnDisk)) {
      missing.add(AndroidGeneratedOutputCategory.KAPT_SOURCES)
    }
    if (shouldCheckNavigationOutputs(context) && !hasNavigationOutputs(context.generatedRootsOnDisk)) {
      missing.add(AndroidGeneratedOutputCategory.NAVIGATION_SOURCES)
    }

    return missing
  }

  private fun collectCoreWarmupTasks(
      context: AndroidWarmupContext,
      missingCategories: Set<AndroidGeneratedOutputCategory>,
      tasks: MutableSet<String>,
  ) {
    if (AndroidGeneratedOutputCategory.CORE_RESOURCES in missingCategories) {
      context.exactTaskPath(context.artifact.resGenTaskName)?.let(tasks::add)
    }

    if (AndroidGeneratedOutputCategory.CORE_SOURCES in missingCategories) {
      context.exactTaskPath(context.artifact.sourceGenTaskName)?.let(tasks::add)
    }
  }

  private fun collectConditionalWarmupTasks(
      context: AndroidWarmupContext,
      missingCategories: Set<AndroidGeneratedOutputCategory>,
      tasks: MutableSet<String>,
  ) {
    if (AndroidGeneratedOutputCategory.BINDING_SOURCES in missingCategories) {
      context.exactTaskPath("dataBindingGenBaseClasses${context.variantNameCapitalized}")?.let(tasks::add)
    }
  }

  /** Returns a task only when the tooling model confirms the exact task name for this module. */
  private fun AndroidWarmupContext.exactTaskPath(taskName: String?): String? {
    if (taskName.isNullOrBlank()) {
      return null
    }
    return availableTasks.firstOrNull { it.name == taskName }?.path
  }
  private data class AndroidNativeWarmupContext(
      val projectPath: String,
      val projectDir: File,
      val variantName: String,
      val tasks: List<GradleTask>,
  )

  private fun collectAndroidNativeCompileCommandsWarmupTasks(project: ProjectImpl): List<String> {
    val tasks = linkedSetOf<String>()

    project.projects.filterIsInstance<AndroidProjectImpl>().forEach { androidProject ->
      val configuredVariant = androidProject.getConfiguredVariant().get().orEmpty().ifBlank { "debug" }
      val rawMetadata = androidProject.getMetadata().get()
      val context =
          AndroidNativeWarmupContext(
              projectPath = rawMetadata.projectPath,
              projectDir = rawMetadata.projectDir,
              variantName = configuredVariant,
              tasks = androidProject.getTasks().get().orEmpty(),
          )
      if (!isLikelyAndroidNativeProject(context.projectDir)) {
        return@forEach
      }

      // Task paths originate from Gradle's Tooling API model. The selector only ranks those
      // paths for the configured Android variant; it never synthesizes a task name.
      selectAndroidNativeWarmupTasks(context).firstOrNull()?.let(tasks::add)
    }

    return tasks.toList()
  }

  /**
   * Generates native compilation metadata before clangd starts when an Android native module has
   * not been configured yet. The selected task is an existing Gradle task path, normally a CMake
   * or ndk-build configure task; legacy JSON-model and external-native-build tasks are fallbacks.
   */
  private fun runAndroidNativeCompileCommandsWarmup(
      connection: ProjectConnection,
      project: ProjectImpl,
      discoveredTasks: List<String>,
  ) {
    val targetTask = discoveredTasks.firstOrNull() ?: return
    if (projectHasNativeCompileCommands(project)) {
      return
    }

    log.info("Configuring Android native build metadata: {}", targetTask)
    runWarmupBuild(connection, listOf(targetTask))
    if (!projectHasNativeCompileCommands(project)) {
      log.warn("Android native configuration finished without compile_commands metadata: {}", targetTask)
    }
  }

  private fun projectHasNativeCompileCommands(project: ProjectImpl): Boolean {
    // Native build metadata is not always laid out directly under <module>/.cxx.
    // AGP often places both CMake and ndk-build outputs under <module>/build/.cxx or
    // <module>/build/.externalNativeBuild, and traditional ndk-build projects may only
    // emit compile_commands.json.bin until ACS reconstructs a text compile_commands.json later.
    return project.projects
        .filterIsInstance<AndroidProjectImpl>()
        .mapNotNull { androidProject ->
          runCatching { androidProject.getMetadata().get().projectDir }.getOrNull()
        }
        .filter(::isLikelyAndroidNativeProject)
        .any { moduleDir ->
          hasNativeCompileCommandsMetadata(File(moduleDir, ".cxx"), maxDepth = 8) ||
              hasNativeCompileCommandsMetadata(File(moduleDir, ".externalNativeBuild"), maxDepth = 8) ||
              hasNativeCompileCommandsMetadata(File(moduleDir, "build/.cxx"), maxDepth = 8) ||
              hasNativeCompileCommandsMetadata(File(moduleDir, "build/.externalNativeBuild"), maxDepth = 8)
        }
  }

  private fun hasNativeCompileCommandsMetadata(
      dir: File,
      maxDepth: Int,
      currentDepth: Int = 0,
  ): Boolean {
    if (currentDepth > maxDepth || !dir.exists() || !dir.isDirectory) {
      return false
    }

    dir.listFiles()?.forEach { file ->
      when {
        file.isFile &&
            (file.name == "compile_commands.json" || file.name == "compile_commands.json.bin") -> {
          return true
        }
        file.isDirectory && hasNativeCompileCommandsMetadata(file, maxDepth, currentDepth + 1) -> {
          return true
        }
      }
    }
    return false
  }

  private fun isLikelyAndroidNativeProject(projectDir: File): Boolean {
    if (!projectDir.exists() || !projectDir.isDirectory) {
      return false
    }

    val conventionalCppDir = File(projectDir, "src/main/cpp")
    if (conventionalCppDir.isDirectory) {
      return true
    }

    val directCMakeLists = File(projectDir, "CMakeLists.txt")
    if (directCMakeLists.isFile) {
      return true
    }

    if (findFilesByName(projectDir, "CMakeLists.txt", maxDepth = 5).isNotEmpty()) {
      return true
    }

    val buildGradleKts = File(projectDir, "build.gradle.kts")
    val buildGradle = File(projectDir, "build.gradle")
    return sequenceOf(buildGradleKts, buildGradle)
        .filter { it.isFile }
        .mapNotNull {
          runCatching { it.readText() }.getOrNull()
        }
        .any { script ->
          script.contains("externalNativeBuild") || script.contains("cmake") || script.contains("ndkBuild")
        }
  }

  private fun findFilesByName(
      dir: File,
      name: String,
      maxDepth: Int,
      currentDepth: Int = 0,
  ): List<File> {
    if (currentDepth > maxDepth || !dir.exists() || !dir.isDirectory) {
      return emptyList()
    }

    val matches = mutableListOf<File>()
    dir.listFiles()?.forEach { file ->
      when {
        file.isFile && file.name == name -> matches.add(file)
        file.isDirectory -> matches.addAll(findFilesByName(file, name, maxDepth, currentDepth + 1))
      }
    }
    return matches
  }

  private enum class AndroidNativeTaskKind(val priority: Int) {
    CMAKE_CONFIGURE(0),
    NDK_BUILD_CONFIGURE(1),
    JSON_MODEL(2),
    EXTERNAL_NATIVE_BUILD(3),
  }

  private data class AndroidNativeWarmupTask(
      val task: GradleTask,
      val kind: AndroidNativeTaskKind,
      val buildType: String?,
      val abi: String?,
  )

  private fun selectAndroidNativeWarmupTasks(
      context: AndroidNativeWarmupContext,
  ): List<String> {
    val moduleTasks =
        context.tasks.filter { task ->
          task.path.startsWith("${context.projectPath}:") || task.projectPath == context.projectPath
        }
    val configureTasks =
        moduleTasks
            .mapNotNull(::parseAndroidNativeWarmupTask)
            .filter { it.kind == AndroidNativeTaskKind.CMAKE_CONFIGURE || it.kind == AndroidNativeTaskKind.NDK_BUILD_CONFIGURE }
            .filter { candidate -> nativeBuildTypeCompatibility(context.variantName, candidate.buildType) != null }
            .sortedWith(
                compareBy<AndroidNativeWarmupTask> {
                      nativeBuildTypeCompatibility(context.variantName, it.buildType) ?: Int.MAX_VALUE
                    }
                    .then(nativeWarmupTaskComparator())
            )
    // AGP names CMake and ndk-build configure tasks by their native build type (for example,
    // configureCMakeDebug[arm64-v8a]), not necessarily by the full Android flavor variant
    // (genericDebug). The path came from Gradle's task model; ACS only ranks it here.
    if (configureTasks.isNotEmpty()) {
      return listOf(configureTasks.first().task.path)
    }

    // Older AGP versions can expose only variant-scoped metadata/build tasks. Keep this fallback
    // reachable instead of selecting it and silently discarding it in the execution layer.
    return moduleTasks
        .mapNotNull(::parseAndroidNativeWarmupTask)
        .filter { candidate ->
          (candidate.kind == AndroidNativeTaskKind.JSON_MODEL || candidate.kind == AndroidNativeTaskKind.EXTERNAL_NATIVE_BUILD) &&
              candidate.buildType != null &&
              candidate.buildType.equals(context.variantName, ignoreCase = true)
        }
        .sortedWith(nativeWarmupTaskComparator())
        .firstOrNull()
        ?.let { listOf(it.task.path) }
        ?: emptyList()
  }

  private fun parseAndroidNativeWarmupTask(task: GradleTask): AndroidNativeWarmupTask? {
    val name = task.name
    val kindAndSuffix =
        when {
          name.startsWith("configureCMake", ignoreCase = true) ->
            AndroidNativeTaskKind.CMAKE_CONFIGURE to name.substring("configureCMake".length)
          name.startsWith("configureNdkBuild", ignoreCase = true) ->
            AndroidNativeTaskKind.NDK_BUILD_CONFIGURE to name.substring("configureNdkBuild".length)
          name.startsWith("generateJsonModel", ignoreCase = true) ->
            AndroidNativeTaskKind.JSON_MODEL to name.substring("generateJsonModel".length)
          name.startsWith("externalNativeBuild", ignoreCase = true) &&
              !name.startsWith("externalNativeBuildClean", ignoreCase = true) ->
            AndroidNativeTaskKind.EXTERNAL_NATIVE_BUILD to name.substring("externalNativeBuild".length)
          else -> return null
        }
    val suffix = kindAndSuffix.second
    val abiStart = suffix.indexOf('[')
    val buildType = if (abiStart >= 0) suffix.substring(0, abiStart) else suffix
    val abi =
        if (abiStart >= 0 && suffix.endsWith(']')) suffix.substring(abiStart + 1, suffix.length - 1)
        else null
    return AndroidNativeWarmupTask(task, kindAndSuffix.first, buildType.ifBlank { null }, abi)
  }

  private fun nativeBuildTypeCompatibility(variantName: String, nativeBuildType: String?): Int? {
    val type = nativeBuildType?.takeIf { it.isNotBlank() } ?: return null
    if (variantName.equals(type, ignoreCase = true)) return 0
    if (variantName.endsWith(type, ignoreCase = true)) return 1

    // Some AGP versions expose CMake/ndk-build tasks as Debug/RelWithDebInfo while Android
    // variants use the conventional Debug/Release build types. Treat that documented native
    // release configuration as a lower-priority compatible match for both build systems.
    return if (
        type.equals("RelWithDebInfo", ignoreCase = true) &&
            variantName.endsWith("Release", ignoreCase = true)
    ) {
      2
    } else {
      null
    }
  }

  private fun nativeWarmupTaskComparator(): Comparator<AndroidNativeWarmupTask> {
    return compareBy<AndroidNativeWarmupTask> { it.kind.priority }
        .thenBy { nativeWarmupAbiPriority(it.abi) }
        .thenBy { it.task.path.length }
        .thenBy { it.task.path }
  }

  private fun nativeWarmupAbiPriority(abi: String?): Int {
    return when (abi) {
      "arm64-v8a" -> 0
      null -> 1
      else -> 2
    }
  }


  private fun shouldCheckBindingOutputs(context: AndroidWarmupContext): Boolean {
    return context.metadata?.viewBindingOptions?.isEnabled == true
  }

  private fun shouldCheckAidlOutputs(context: AndroidWarmupContext): Boolean {
    return context.generatedRootsOnDisk.any { path ->
      path.contains("/build/generated/aidl_source_output_dir/") ||
          path.contains("/build/generated/source/aidl/")
    }
  }

  private fun shouldCheckKaptOutputs(context: AndroidWarmupContext): Boolean {
    return context.generatedRootsOnDisk.any { path ->
      path.contains("/build/generated/ap_generated_sources/") ||
          path.contains("/build/generated/source/kapt/") ||
          path.contains("/build/generated/source/kaptkotlin/") ||
          path.contains("/build/tmp/kapt3/classes/")
    }
  }

  private fun shouldCheckNavigationOutputs(context: AndroidWarmupContext): Boolean {
    return context.generatedRootsOnDisk.any { path ->
      path.contains("/build/generated/source/navigation-args/")
    }
  }

  private fun hasCoreResourceOutputs(existingRoots: Set<String>): Boolean {
    return existingRoots.any { path ->
      path.contains("/build/generated/res/resvalues/") ||
          path.contains("/build/intermediates/packaged_res/") ||
          path.contains("/build/intermediates/merged_res/") ||
          path.contains("/build/generated/source/r/") ||
          path.contains("/build/generated/not_namespaced_r_class_sources/")
    }
  }

  private fun hasCoreSourceOutputs(existingRoots: Set<String>): Boolean {
    return existingRoots.any { path ->
      path.contains("/build/generated/source/buildconfig/") ||
          path.contains("/build/generated/source/")
    }
  }

  private fun hasBindingOutputs(existingRoots: Set<String>): Boolean {
    return existingRoots.any { path ->
      path.contains("/build/generated/data_binding_base_class_source_out/") ||
          path.contains("/build/generated/source/databinding/") ||
          path.contains("/build/generated/source/viewbinding/")
    }
  }

  private fun hasAidlOutputs(existingRoots: Set<String>): Boolean {
    return existingRoots.any { path ->
      path.contains("/build/generated/aidl_source_output_dir/") ||
          path.contains("/build/generated/source/aidl/")
    }
  }

  private fun hasKaptOutputs(existingRoots: Set<String>): Boolean {
    return existingRoots.any { path ->
      path.contains("/build/generated/ap_generated_sources/") ||
          path.contains("/build/generated/source/kapt/") ||
          path.contains("/build/generated/source/kaptkotlin/") ||
          path.contains("/build/tmp/kapt3/classes/")
    }
  }

  private fun hasNavigationOutputs(existingRoots: Set<String>): Boolean {
    return existingRoots.any { path ->
      path.contains("/build/generated/source/navigation-args/")
    }
  }

  private fun collectExistingGeneratedRoots(
      artifact: com.tom.rv2ide.tooling.api.models.AndroidArtifactMetadata,
  ): Set<String> {
    return (artifact.generatedSourceFolders + artifact.generatedResourceFolders)
        .asSequence()
        .filter { it.exists() && it.isDirectory }
        .filter(::isLikelyAgpGeneratedRoot)
        .map { normalizeGeneratedRootPath(it) }
        .toSet()
  }

  private fun isLikelyAgpGeneratedRoot(path: File): Boolean {
    val normalized = normalizeGeneratedRootPath(path)
    return normalized.contains("/build/generated/") || normalized.contains("/build/intermediates/")
  }

  private fun normalizeGeneratedRootPath(path: File): String {
    return path.absolutePath.replace('\\', '/').lowercase()
  }


  private fun runWarmupBuild(connection: ProjectConnection, tasks: List<String>) {
    if (tasks.isEmpty()) return

    val builder = connection.newBuild()
    val out = LoggingOutputStream()
    builder.setStandardInput("NoOp".byteInputStream())
    builder.setStandardError(out)
    builder.setStandardOutput(out)
    builder.forTasks(*tasks.filter { it.isNotBlank() }.toTypedArray())
    Main.finalizeLauncher(builder)

    this.buildCancellationToken = GradleConnector.newCancellationTokenSource()
    builder.withCancellationToken(this.buildCancellationToken!!.token())
    builder.run()
    this.buildCancellationToken = null
  }

  private fun setupConnectorForGradleInstallation(
      connector: GradleConnector,
      params: GradleDistributionParams,
  ) {

    when (params.type) {
      GradleDistributionType.GRADLE_WRAPPER -> {
        log.info("Using Gradle wrapper for build...")
      }

      GradleDistributionType.GRADLE_INSTALLATION -> {
        val file = File(params.value)
        if (!file.exists() || !file.isDirectory) {
          log.error("Specified Gradle installation does not exist: {}", params)
          return
        }

        log.info("Using Gradle installation: {}", file.canonicalPath)
        connector.useInstallation(file)
      }

      GradleDistributionType.GRADLE_VERSION -> {
        log.info("Using Gradle version '{}'", params.value)
        connector.useGradleVersion(params.value)
      }
    }
  }

  private fun notifyBuildFailure(tasks: List<String>) {
    client?.onBuildFailed(BuildResult((tasks)))
  }

  private fun notifyBuildSuccess(tasks: List<String>) {
    client?.onBuildSuccessful(BuildResult(tasks))
  }

  private fun notifyBeforeBuild(buildInfo: BuildInfo) {
    client?.prepareBuild(buildInfo)
  }

  override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
    return CompletableFuture.supplyAsync {
      if (this.buildCancellationToken == null) {
        return@supplyAsync BuildCancellationRequestResult(
            false,
            BuildCancellationRequestResult.Reason.NO_RUNNING_BUILD,
        )
      }

      try {
        this.buildCancellationToken!!.cancel()
        this.buildCancellationToken = null
      } catch (e: Exception) {
        val failureReason = CANCELLATION_ERROR
        failureReason.message = "${failureReason.message}: ${e.message}"
        return@supplyAsync BuildCancellationRequestResult(false, failureReason)
      }

      return@supplyAsync BuildCancellationRequestResult(true, null)
    }
  }

  override fun shutdown(): CompletableFuture<Void> {
    return CompletableFuture.supplyAsync {
      log.info("Shutting down Tooling API Server...")

      connection?.close()
      connector?.disconnect()
      connection = null
      connector = null

      // Stop all daemons
      log.info("Stopping all Gradle Daemons...")
      DefaultGradleConnector.close()

      // stop proxy
      try {
        httpProxy?.stop()
      } catch (_: Throwable) {}
      httpProxy = null

      // update the initialization flag before cancelling future
      this.isInitialized = false

      // cancelling this future will finish the Tooling API server process
      // see com.tom.rv2ide.tooling.impl.Main.main(String[])
      Main.future?.cancel(true)

      this.client = null
      this.buildCancellationToken = null // connector.disconnect() cancels any running builds
      this.lastInitParams = null
      Main.future = null
      Main.client = null

      null
    }
  }

  private fun getTaskFailureType(error: Throwable): Failure =
      when (error) {
        is BuildException -> BUILD_FAILED
        is BuildCancelledException -> BUILD_CANCELLED
        is UnsupportedOperationConfigurationException -> UNSUPPORTED_CONFIGURATION
        is UnsupportedVersionException -> UNSUPPORTED_GRADLE_VERSION
        is UnsupportedBuildArgumentException -> UNSUPPORTED_BUILD_ARGUMENT
        is GradleConnectionException -> CONNECTION_ERROR
        is java.lang.IllegalStateException -> CONNECTION_CLOSED
        else -> UNKNOWN
      }

  private inline fun <T : Any?> supplyAsync(crossinline action: () -> T): CompletableFuture<T> =
      CompletableFuture.supplyAsync { action() }

  private inline fun <T : Any?> runBuild(crossinline action: () -> T): CompletableFuture<T> =
      supplyAsync {
        if (isBuildInProgress) {
          log.error("Cannot run build, build is already in progress")
          throw IllegalStateException("Build is already in progress")
        }

        isBuildInProgress = true
        try {
          action()
        } finally {
          isBuildInProgress = false
        }
      }

  private fun assertProjectInitialized() {
    if (!isServerInitialized().get()) {
      throw CompletionException(IllegalStateException("Project is not initialized!"))
    }
  }

  fun connect(client: IToolingApiClient) {
    this.client = client
  }
}
