package com.tom.rv2ide.projects.models

import java.nio.file.Path

public enum class ProjectModuleKind {
  ANDROID,
  JAVA,
  OTHER,
}

public enum class ProjectRootRole {
  ORDINARY_SOURCE,
  GENERATED_SOURCE,
  JAVA_SOURCE,
  BOOT,
}

public enum class ProjectRootOrigin {
  MAIN_SOURCE_SET,
  VARIANT_MAIN_ARTIFACT,
  JAVA_CONTENT_ROOT,
  COMPOSITE_BUILD,
  ANDROID_BOOT_CLASSPATH,
  JAVA_INHERITED_BOOT_CLASSPATH,
  UNKNOWN,
}

public data class ProjectModuleIdentity(
    val modulePath: String,
    val kind: ProjectModuleKind,
    val projectDirectory: Path,
    val buildDirectory: Path,
)

public data class ProjectCompilerSettingsSnapshot(
    val javaSourceVersion: String,
    val javaBytecodeVersion: String,
)

public data class ProjectRootDescriptor(
    val path: Path,
    val ownerModulePath: String,
    val role: ProjectRootRole,
    val origin: ProjectRootOrigin,
    val variantName: String? = null,
    val exists: Boolean,
)

public enum class ProjectBinaryRole {
  MODULE_OUTPUT,
  EXTERNAL_BINARY,
  BOOT_BINARY,
}

public enum class ProjectBinaryOrigin {
  ANDROID_GENERATED_JAR,
  ANDROID_VARIANT_CLASS_JAR,
  JAVA_CLASSES_JAR,
  ANDROID_LIBRARY,
  JAVA_LIBRARY,
  EXTERNAL_JAR,
  AAR_CLASSES_JAR,
  ANDROID_BOOT,
  JAVA_INHERITED_BOOT,
  UNKNOWN,
}

public data class ProjectBinaryDescriptor(
    val path: Path,
    val ownerModulePath: String?,
    val role: ProjectBinaryRole,
    val origin: ProjectBinaryOrigin,
    val variantName: String? = null,
    val exists: Boolean,
    val size: Long? = null,
    val lastModifiedMillis: Long? = null,
)

public data class ProjectVariantSelectionSnapshot(
    val projectPath: String,
    val selectedVariant: String,
    val availableVariants: List<String>,
    val versionName: String?,
    val versionCode: Int?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compileSdk: Int?,
)

public data class ProjectVariantArtifactSnapshot(
    val variantName: String,
    val generatedSourceRoots: List<Path>,
    val classJars: List<Path>,
    val sourceGenerationTask: String?,
    val resourceGenerationTask: String?,
    val compileTask: String?,
    val outputListingFile: Path?,
)

public data class ProjectModuleInputSnapshot(
    val identity: ProjectModuleIdentity,
    val compilerSettings: ProjectCompilerSettingsSnapshot,
    val ordinarySourceRoots: List<ProjectRootDescriptor>,
    val generatedSourceRoots: List<ProjectRootDescriptor>,
    val javaSourceRoots: List<ProjectRootDescriptor>,
    val moduleOutputs: List<ProjectBinaryDescriptor>,
    val externalBinaries: List<ProjectBinaryDescriptor>,
    val bootBinaries: List<ProjectBinaryDescriptor>,
    val compileModulePaths: List<String>,
    val externalDependencyIdentities: List<String>,
    val variantSelection: ProjectVariantSelectionSnapshot?,
    val variantArtifact: ProjectVariantArtifactSnapshot?,
)

public data class ProjectInputSnapshot(
    val workspaceRoot: Path,
    val modules: List<ProjectModuleInputSnapshot>,
)
