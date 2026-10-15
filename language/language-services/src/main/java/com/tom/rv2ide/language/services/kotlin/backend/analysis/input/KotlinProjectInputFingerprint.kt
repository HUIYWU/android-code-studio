package com.tom.rv2ide.language.services.kotlin.backend.analysis.input

import com.tom.rv2ide.projects.models.ProjectBinaryDescriptor
import com.tom.rv2ide.projects.models.ProjectInputSnapshot
import com.tom.rv2ide.projects.models.ProjectModuleInputSnapshot
import com.tom.rv2ide.projects.models.ProjectRootDescriptor
import com.tom.rv2ide.projects.models.ProjectVariantArtifactSnapshot
import com.tom.rv2ide.projects.models.ProjectVariantSelectionSnapshot
import java.security.MessageDigest

internal data class KotlinProjectInputFingerprints(
    val moduleIdentity: String,
    val compilerConfiguration: String,
    val sourceStructure: String,
    val binaries: String,
    val boot: String,
    val dependencyGraph: String,
    val variant: String,
    val structure: String,
)

internal object KotlinProjectInputFingerprint {
  fun of(snapshot: ProjectInputSnapshot): KotlinProjectInputFingerprints {
    val moduleIdentity = digest(snapshot.modules.flatMap { module ->
      listOf(
          "workspace=${snapshot.workspaceRoot}",
          "module=${module.identity.modulePath}",
          "kind=${module.identity.kind}",
          "project=${module.identity.projectDirectory}",
          "build=${module.identity.buildDirectory}",
      )
    })
    val compilerConfiguration = digest(snapshot.modules.map { module ->
      listOf(
          "module=${module.identity.modulePath}",
          "javaSource=${module.compilerSettings.javaSourceVersion}",
          "javaBytecode=${module.compilerSettings.javaBytecodeVersion}",
      ).joinToString("|")
    })
    val sourceStructure = digest(snapshot.modules.flatMap { module ->
      rootLines(module, module.ordinarySourceRoots) +
          rootLines(module, module.generatedSourceRoots) +
          rootLines(module, module.javaSourceRoots)
    })
    val binaries = digest(snapshot.modules.flatMap { module ->
      binaryLines(module, module.moduleOutputs) + binaryLines(module, module.externalBinaries)
    })
    val boot = digest(snapshot.modules.flatMap { module ->
      binaryLines(module, module.bootBinaries)
    })
    val dependencyGraph = digest(snapshot.modules.flatMap { module ->
      buildList {
        add("module=${module.identity.modulePath}")
        module.compileModulePaths.forEach { add("compileModule=$it") }
        module.externalDependencyIdentities.forEach { add("external=$it") }
      }
    })
    val variant = digest(snapshot.modules.flatMap { module ->
      buildList {
        add("module=${module.identity.modulePath}")
        module.variantSelection?.let { addAll(selectionLines(it)) }
        module.variantArtifact?.let { addAll(artifactLines(it)) }
      }
    })
    val structure = digest(
        listOf(
            moduleIdentity,
            compilerConfiguration,
            sourceStructure,
            binaries,
            boot,
            dependencyGraph,
            variant,
        ),
    )
    return KotlinProjectInputFingerprints(
        moduleIdentity = moduleIdentity,
        compilerConfiguration = compilerConfiguration,
        sourceStructure = sourceStructure,
        binaries = binaries,
        boot = boot,
        dependencyGraph = dependencyGraph,
        variant = variant,
        structure = structure,
    )
  }

  private fun rootLines(
      module: ProjectModuleInputSnapshot,
      roots: List<ProjectRootDescriptor>,
  ): List<String> = roots.map { root ->
    listOf(
        "module=${module.identity.modulePath}",
        "path=${root.path}",
        "owner=${root.ownerModulePath}",
        "role=${root.role}",
        "origin=${root.origin}",
        "variant=${root.variantName.orEmpty()}",
        "exists=${root.exists}",
    ).joinToString("|")
  }

  private fun binaryLines(
      module: ProjectModuleInputSnapshot,
      binaries: List<ProjectBinaryDescriptor>,
  ): List<String> = binaries.map { binary ->
    listOf(
        "module=${module.identity.modulePath}",
        "path=${binary.path}",
        "owner=${binary.ownerModulePath.orEmpty()}",
        "role=${binary.role}",
        "origin=${binary.origin}",
        "variant=${binary.variantName.orEmpty()}",
        "exists=${binary.exists}",
        "size=${binary.size ?: -1L}",
        "modified=${binary.lastModifiedMillis ?: -1L}",
    ).joinToString("|")
  }

  private fun selectionLines(selection: ProjectVariantSelectionSnapshot): List<String> = listOf(
      "project=${selection.projectPath}",
      "selected=${selection.selectedVariant}",
      "available=${selection.availableVariants.sorted().joinToString(",")}",
      "versionName=${selection.versionName.orEmpty()}",
      "versionCode=${selection.versionCode ?: -1}",
      "minSdk=${selection.minSdk ?: -1}",
      "targetSdk=${selection.targetSdk ?: -1}",
      "compileSdk=${selection.compileSdk ?: -1}",
  )

  private fun artifactLines(artifact: ProjectVariantArtifactSnapshot): List<String> = buildList {
    add("variant=${artifact.variantName}")
    artifact.generatedSourceRoots.sortedBy { it.toString() }.forEach { add("generated=$it") }
    artifact.classJars.sortedBy { it.toString() }.forEach { add("classJar=$it") }
    add("sourceTask=${artifact.sourceGenerationTask.orEmpty()}")
    add("resourceTask=${artifact.resourceGenerationTask.orEmpty()}")
    add("compileTask=${artifact.compileTask.orEmpty()}")
    add("listing=${artifact.outputListingFile ?: ""}")
  }

  private fun digest(lines: List<String>): String {
    val content = lines.sorted().joinToString("\n")
    val bytes = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }
}