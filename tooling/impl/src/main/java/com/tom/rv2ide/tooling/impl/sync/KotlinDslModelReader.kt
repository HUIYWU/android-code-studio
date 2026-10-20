package com.tom.rv2ide.tooling.impl.sync

import com.tom.rv2ide.tooling.api.models.GradleKotlinDslBuildModel
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslEnvironment
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslEditorPosition
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslEditorReport
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslInputState
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslModelState
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslReportSeverity
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslScriptKind
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslScriptModel
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslModel
import com.tom.rv2ide.tooling.impl.Main.finalizeLauncher
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.BuildController
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.kotlin.dsl.EditorReport
import org.gradle.tooling.model.kotlin.dsl.KotlinDslModelsParameters
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptModel
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptsModel
import org.slf4j.LoggerFactory

internal object KotlinDslModelReader {

  private const val MODEL_SOURCE = "GRADLE_TOOLING_KOTLIN_DSL_MODEL"

  private val logger = LoggerFactory.getLogger(KotlinDslModelReader::class.java)

  fun read(
      connection: ProjectConnection,
      cancellationToken: CancellationToken?,
      buildRoot: File,
  ): GradleKotlinDslModel {
    val executor =
        connection.action { controller ->
          capture(controller, buildRoot)
        }

    finalizeLauncher(executor)
    executor.forTasks(KotlinDslModelsParameters.PREPARATION_TASK_NAME)
    executor.addJvmArguments(KotlinDslModelsParameters.CLASSPATH_MODE_SYSTEM_PROPERTY_DECLARATION)
    if (cancellationToken != null) {
      executor.withCancellationToken(cancellationToken)
    }

    return try {
      executor.run()
    } catch (error: BuildCancelledException) {
      throw error
    } catch (error: Throwable) {
      logger.warn("Unable to obtain Gradle Kotlin DSL model", error)
      failedModel(buildRoot, error)
    }
  }

  private fun capture(controller: BuildController, requestedBuildRoot: File): GradleKotlinDslModel {
    val buildEnvironment =
        runCatching { controller.getModel(BuildEnvironment::class.java) }
            .onFailure { logger.debug("Gradle BuildEnvironment model unavailable", it) }
            .getOrNull()

    val ideaProject =
        runCatching { controller.getModel(IdeaProject::class.java) }
            .onFailure { logger.debug("Gradle IdeaProject model unavailable for KTS mapping", it) }
            .getOrNull()

    val buildRootPath =
        buildEnvironment?.buildIdentifier?.rootDir?.canonicalPath
            ?: requestedBuildRoot.canonicalPath
    val modulePaths = createModulePaths(ideaProject)

    val model =
        try {
          controller.getModel(KotlinDslScriptsModel::class.java)
        } catch (error: Throwable) {
          return failedModel(
              File(buildRootPath),
              error,
              buildEnvironment = copyBuildEnvironment(buildEnvironment, GradleKotlinDslModelState.FAILED),
          )
        }

    val scripts =
        model.scriptModels.entries
            .sortedBy { it.key.canonicalPath }
            .map { (script, scriptModel) ->
              copyScriptModel(script, scriptModel, modulePaths)
            }

    val environment = copyBuildEnvironment(buildEnvironment, environmentState(buildEnvironment))
    val hasScriptProblems = scripts.any { it.state != GradleKotlinDslModelState.VALID }
    val hasEnvironmentProblem = environment.state != GradleKotlinDslModelState.VALID
    val buildState =
        if (hasScriptProblems || hasEnvironmentProblem) {
          GradleKotlinDslModelState.PARTIAL
        } else {
          GradleKotlinDslModelState.VALID
        }

    val buildModel =
        GradleKotlinDslBuildModel(
            buildRootPath = buildRootPath,
            state = buildState,
            environment = environment,
            scripts = scripts,
            exceptions = environment.exceptions + scripts.flatMap { it.exceptions },
        ).withFingerprint()

    return GradleKotlinDslModel(
            state = buildState,
            builds = listOf(buildModel),
            exceptions = buildModel.exceptions,
        )
        .withFingerprint()
  }

  private fun copyScriptModel(
      script: File,
      model: KotlinDslScriptModel,
      modulePaths: List<ModulePath>,
  ): GradleKotlinDslScriptModel {
    val classPath = model.classPath.map { it.canonicalPath }
    val sourcePath = model.sourcePath.map { it.canonicalPath }
    val implicitImports = model.implicitImports.toList()
    val reports = model.editorReports.map(::copyEditorReport)
    val exceptions = model.exceptions.toList()
    val state =
        if (exceptions.isNotEmpty() || reports.any { it.severity == GradleKotlinDslReportSeverity.ERROR }) {
          GradleKotlinDslModelState.PARTIAL
        } else {
          GradleKotlinDslModelState.VALID
        }

    return GradleKotlinDslScriptModel(
            scriptPath = script.canonicalPath,
            kind = scriptKind(script.name),
            projectPath = findOwningProject(script, modulePaths),
            state = state,
            modelSource = MODEL_SOURCE,
            classPath = classPath,
            sourcePath = sourcePath,
            implicitImports = implicitImports,
            editorReports = reports,
            exceptions = exceptions,
            inputState = GradleKotlinDslInputState.SAVED_FILES,
        )
        .withFingerprint()
  }

  private fun copyEditorReport(report: EditorReport): GradleKotlinDslEditorReport {
    val position = report.position?.let { GradleKotlinDslEditorPosition(it.line, it.column) }
    return GradleKotlinDslEditorReport(
        severity = GradleKotlinDslReportSeverity.valueOf(report.severity.name),
        message = report.message,
        position = position,
    )
  }

  private fun copyBuildEnvironment(
      environment: BuildEnvironment?,
      state: GradleKotlinDslModelState,
  ): GradleKotlinDslEnvironment {
    return GradleKotlinDslEnvironment(
        state = state,
        gradleVersion = environment?.gradle?.gradleVersion,
        gradleUserHome = environment?.gradle?.gradleUserHome?.canonicalPath,
        javaHome = runCatching { environment?.java?.javaHome?.canonicalPath }.getOrNull(),
        jvmArguments = runCatching { environment?.java?.jvmArguments?.toList() }.getOrNull().orEmpty(),
    )
  }

  private fun environmentState(environment: BuildEnvironment?): GradleKotlinDslModelState =
      if (environment == null) GradleKotlinDslModelState.UNAVAILABLE
      else GradleKotlinDslModelState.VALID

  private fun createModulePaths(project: IdeaProject?): List<ModulePath> {
    return project?.modules.orEmpty().mapNotNull { module ->
      runCatching {
        ModulePath(module.gradleProject.projectDirectory.canonicalFile, module.gradleProject.path)
      }.getOrNull()
    }.sortedByDescending { it.directory.path.length }
  }

  private fun findOwningProject(script: File, modules: List<ModulePath>): String? {
    val scriptPath = script.canonicalFile.toPath()
    return modules.firstOrNull { scriptPath.startsWith(it.directory.toPath()) }?.projectPath
  }

  private fun scriptKind(name: String): GradleKotlinDslScriptKind =
      when {
        name == "init.gradle.kts" -> GradleKotlinDslScriptKind.INIT
        name == "settings.gradle.kts" -> GradleKotlinDslScriptKind.SETTINGS
        name == "build.gradle.kts" -> GradleKotlinDslScriptKind.PROJECT
        name.endsWith(".gradle.kts") -> GradleKotlinDslScriptKind.APPLIED
        else -> GradleKotlinDslScriptKind.UNKNOWN
      }

  private fun failedModel(
      buildRoot: File,
      error: Throwable,
      buildEnvironment: GradleKotlinDslEnvironment = GradleKotlinDslEnvironment(),
  ): GradleKotlinDslModel {
    val message = "${error.javaClass.name}: ${error.message ?: "unknown error"}"
    val build =
        GradleKotlinDslBuildModel(
            buildRootPath = buildRoot.canonicalPath,
            state = GradleKotlinDslModelState.FAILED,
            environment = buildEnvironment,
            exceptions = listOf(message),
        ).withFingerprint()
    return GradleKotlinDslModel(
            state = GradleKotlinDslModelState.FAILED,
            builds = listOf(build),
            exceptions = listOf(message),
        )
        .withFingerprint()
  }

  private data class ModulePath(val directory: File, val projectPath: String)

  private fun GradleKotlinDslScriptModel.withFingerprint(): GradleKotlinDslScriptModel =
      copy(fingerprint = fingerprintOf(toFingerprintText()))

  private fun GradleKotlinDslBuildModel.withFingerprint(): GradleKotlinDslBuildModel =
      copy(fingerprint = fingerprintOf(toFingerprintText()))

  private fun GradleKotlinDslModel.withFingerprint(): GradleKotlinDslModel =
      copy(fingerprint = fingerprintOf(toFingerprintText()))

  private fun GradleKotlinDslScriptModel.toFingerprintText(): String =
      listOf(
              scriptPath,
              kind,
              projectPath,
              state,
              modelSource,
              classPath,
              sourcePath,
              implicitImports,
              editorReports,
              exceptions,
              inputState,
              inputFingerprint,
          )
          .joinToString("\u001f")

  private fun GradleKotlinDslBuildModel.toFingerprintText(): String =
      listOf(buildRootPath, state, environment, scripts, exceptions).joinToString("\u001f")

  private fun GradleKotlinDslModel.toFingerprintText(): String =
      listOf(schemaVersion, state, builds, exceptions).joinToString("\u001f")

  private fun fingerprintOf(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }
}