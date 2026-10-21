package com.tom.rv2ide.tooling.api.models

import java.io.Serializable
import java.util.Collections

enum class GradleKotlinDslModelState {
  NOT_REQUESTED,
  UNAVAILABLE,
  FAILED,
  PARTIAL,
  VALID,
}

enum class GradleKotlinDslScriptKind {
  INIT,
  SETTINGS,
  PROJECT,
  APPLIED,
  UNKNOWN,
}

enum class GradleKotlinDslInputState {
  NOT_CAPTURED,
  SAVED_FILES,
}

enum class GradleKotlinDslReportSeverity {
  WARNING,
  ERROR,
}

data class GradleKotlinDslEditorPosition(
    val line: Int,
    val column: Int,
) : Serializable

data class GradleKotlinDslEditorReport(
    val severity: GradleKotlinDslReportSeverity,
    val message: String,
    val position: GradleKotlinDslEditorPosition?,
) : Serializable

data class GradleKotlinDslEnvironment(
    val state: GradleKotlinDslModelState = GradleKotlinDslModelState.NOT_REQUESTED,
    val gradleVersion: String? = null,
    val gradleHome: String? = null,
    val gradleUserHome: String? = null,
    val javaHome: String? = null,
    val jvmArguments: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
) : Serializable {
  fun immutableCopy(): GradleKotlinDslEnvironment = copy(
      jvmArguments = immutableList(jvmArguments),
      exceptions = immutableList(exceptions),
  )
}

data class GradleKotlinDslScriptModel(
    val scriptPath: String,
    val kind: GradleKotlinDslScriptKind,
    val projectPath: String?,
    val state: GradleKotlinDslModelState,
    val modelSource: String,
    val classPath: List<String>,
    val sourcePath: List<String>,
    val implicitImports: List<String>,
    val editorReports: List<GradleKotlinDslEditorReport>,
    val exceptions: List<String>,
    val inputState: GradleKotlinDslInputState = GradleKotlinDslInputState.NOT_CAPTURED,
    val inputFingerprint: String? = null,
    val fingerprint: String? = null,
) : Serializable {
  fun immutableCopy(): GradleKotlinDslScriptModel = copy(
      classPath = immutableList(classPath),
      sourcePath = immutableList(sourcePath),
      implicitImports = immutableList(implicitImports),
      editorReports = immutableList(editorReports),
      exceptions = immutableList(exceptions),
  )
}

data class GradleKotlinDslBuildModel(
    val buildRootPath: String,
    val state: GradleKotlinDslModelState,
    val environment: GradleKotlinDslEnvironment = GradleKotlinDslEnvironment(),
    val scripts: List<GradleKotlinDslScriptModel> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val fingerprint: String? = null,
) : Serializable {
  fun immutableCopy(): GradleKotlinDslBuildModel = copy(
      environment = environment.immutableCopy(),
      scripts = immutableList(scripts.map { it.immutableCopy() }),
      exceptions = immutableList(exceptions),
  )
}

data class GradleKotlinDslModel(
    val schemaVersion: Int = 1,
    val state: GradleKotlinDslModelState = GradleKotlinDslModelState.NOT_REQUESTED,
    val builds: List<GradleKotlinDslBuildModel> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val fingerprint: String? = null,
) : Serializable {
  fun immutableCopy(): GradleKotlinDslModel = copy(
      builds = immutableList(builds.map { it.immutableCopy() }),
      exceptions = immutableList(exceptions),
  )
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
