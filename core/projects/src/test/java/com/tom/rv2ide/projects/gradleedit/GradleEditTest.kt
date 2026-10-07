package com.tom.rv2ide.projects.gradleedit
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.treesitter.TreeSitter
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class GradleEditTest {
  @get:Rule
  val testTimeout: Timeout = Timeout.builder()
      .withTimeout(30, TimeUnit.SECONDS)
      .withLookingForStuckThread(true)
      .build()

  companion object {
    @JvmStatic
    @BeforeClass
    fun loadTreeSitterNativeLibraries() {
      TreeSitter.loadLibrary()
      System.loadLibrary("tree-sitter-kotlin")
      System.loadLibrary("tree-sitter-groovy")
    }
  }

  @Test fun kotlinSettingsIncludeIsAddedWithoutTouchingComments() {
    val source = "// include(\":fake\")\ninclude(\":app\")\n"
    val output = apply(source, ProjectSettingsEditor.addInclude(source, ":feature", GradleDsl.KOTLIN))
    assertThat(output).contains("include(\":app\", \":feature\")")
    assertThat(ProjectSettingsEditor.findIncludedPaths(output, GradleDsl.KOTLIN)).containsExactly(":app", ":feature")
    assertThat(output).contains("// include(\":fake\")")
  }

  @Test fun standaloneIncludeIsRemovedAndCompoundIncludeEntryCanBeRemoved() {
    val standalone = "include(\":app\")\n"
    val output = apply(standalone, ProjectSettingsEditor.removeInclude(standalone, ":app", GradleDsl.KOTLIN))
    assertThat(output).isEmpty()

    val compound = "include(\":feature\", \":shared\")\n"
    val compoundOutput = apply(compound, ProjectSettingsEditor.removeInclude(compound, ":feature", GradleDsl.KOTLIN))
    assertThat(compoundOutput).isEqualTo("include(\":shared\")\n")
  }

  @Test fun multilineKotlinIncludeRemovesOneEntryAndPreservesOthers() {
    val source = """include(
    ":app",
    ":tewo",
    ":sec",
    ":profile1",
    ":profile2"
)
"""
    val output = apply(source, ProjectSettingsEditor.removeInclude(source, ":profile2", GradleDsl.KOTLIN))
    assertThat(output).doesNotContain(":profile2")
    assertThat(output).isEqualTo("include(\n    \":app\",\n    \":tewo\",\n    \":sec\",\n    \":profile1\"\n)\n")
    assertThat(ProjectSettingsEditor.findIncludedPaths(output, GradleDsl.KOTLIN)).containsExactly(":app", ":tewo", ":sec", ":profile1")
  }

  @Test fun multilineKotlinIncludeAddsEntryWithExistingCommaStyle() {
    val source = "include(\n  \":app\",\n  \":profile1\"\n)\n"
    val output = apply(source, ProjectSettingsEditor.addInclude(source, ":profile2", GradleDsl.KOTLIN))
    assertThat(output).isEqualTo("include(\n  \":app\",\n  \":profile1\",\n  \":profile2\"\n)\n")
  }

  @Test fun includeWithDynamicArgumentFailsClosed() {
    val source = "include(\":app\", modules.first(), \":profile2\")\n"
    assertThat(ProjectSettingsEditor.removeInclude(source, ":profile2", GradleDsl.KOTLIN))
        .isInstanceOf(GradleEditResult.Unsupported::class.java)
  }

  @Test fun repeatedIncludeCallsFailClosed() {
    val source = "include(\":profile2\")\ninclude(\":app\", \":profile2\")\n"
    assertThat(ProjectSettingsEditor.removeInclude(source, ":profile2", GradleDsl.KOTLIN))
        .isInstanceOf(GradleEditResult.Ambiguous::class.java)
  }

  @Test fun groovySettingsIncludeIsRemovedWithOtherEntriesPreserved() {
    val source = "include ':app', ':feature', ':shared'\n"
    val output = apply(source, ProjectSettingsEditor.removeInclude(source, ":feature", GradleDsl.GROOVY))
    assertThat(output).contains("include ':app', ':shared'")
    assertThat(ProjectSettingsEditor.findIncludedPaths(output, GradleDsl.GROOVY)).containsExactly(":app", ":shared")
  }
  @Test fun groovySettingsIncludeWithMixedQuotesRemovesEntry() {
    val source = "include(\":app\", \":ltp\", ':lsr')\n"
    val output = apply(source, ProjectSettingsEditor.removeInclude(source, ":lsr", GradleDsl.GROOVY))
    assertThat(output).isEqualTo("include(\":app\", \":ltp\")\n")
    assertThat(ProjectSettingsEditor.findIncludedPaths(output, GradleDsl.GROOVY)).containsExactly(":app", ":ltp")
  }

  @Test fun includePathCanBeRenamedWithExplicitDslAndOriginalQuote() {
    val kotlinSource = "include(\":old\", \":other\")\n"
    val kotlinOutput = apply(kotlinSource, ProjectSettingsEditor.renameInclude(kotlinSource, ":old", ":new", GradleDsl.KOTLIN))
    assertThat(kotlinOutput).isEqualTo("include(\":new\", \":other\")\n")

    val groovySource = "include ':old', ':other'\n"
    val groovyOutput = apply(groovySource, ProjectSettingsEditor.renameInclude(groovySource, ":old", ":new", GradleDsl.GROOVY))
    assertThat(groovyOutput).isEqualTo("include ':new', ':other'\n")
  }

  @Test fun includeRenameFailsClosedForDynamicOrRepeatedCalls() {
    val dynamic = "include(\":old\", modules.first())\n"
    assertThat(ProjectSettingsEditor.renameInclude(dynamic, ":old", ":new", GradleDsl.KOTLIN))
        .isInstanceOf(GradleEditResult.Unsupported::class.java)

    val repeated = "include(\":old\")\ninclude(\":other\")\n"
    assertThat(ProjectSettingsEditor.renameInclude(repeated, ":old", ":new", GradleDsl.KOTLIN))
        .isInstanceOf(GradleEditResult.Ambiguous::class.java)
  }

  @Test fun projectDirectoryMappingPathCanBeRenamed() {
    val kotlinSource = "project(\":old\").projectDir = file(\"legacy\")\n"
    val kotlinOutput = apply(kotlinSource, ProjectSettingsEditor.renameProjectDirMappingPath(kotlinSource, ":old", ":new", GradleDsl.KOTLIN))
    assertThat(kotlinOutput).isEqualTo("project(\":new\").projectDir = file(\"legacy\")\n")

    val groovySource = "project(':old').projectDir = file('legacy')\n"
    val groovyOutput = apply(groovySource, ProjectSettingsEditor.renameProjectDirMappingPath(groovySource, ":old", ":new", GradleDsl.GROOVY))
    assertThat(groovyOutput).isEqualTo("project(':new').projectDir = file('legacy')\n")
  }


  @Test fun groovyProjectDirectoryMappingCanBeUpdated() {
    val source = "project(':demo').projectDir = file('../demo')\n"
    val edit = ProjectSettingsEditor.updateProjectDirMapping(source, ":demo", "../new-demo", GradleDsl.GROOVY)
    assertWithMessage(
        "Groovy mapping edit was not applied.\nParser calls:\n%s",
        parserDiagnostics(source),
    ).that(edit).isInstanceOf(GradleEditResult.Applied::class.java)
    val output = apply(source, edit)
    assertThat(output).contains("file('../new-demo')")
    val mappings = ProjectSettingsEditor.findProjectDirectoryMappings(output, GradleDsl.GROOVY)
    assertWithMessage(
        "Groovy mapping was not rediscovered.\nParser calls after edit:\n%s\nOutput:\n%s",
        parserDiagnostics(output),
        output,
    ).that(mappings).containsExactly(ProjectSettingsEditor.ProjectDirectoryMapping(":demo", "'../new-demo'"))
  }

  @Test fun groovyDependencyCanBeFoundAndRenamed() {
    val source = "dependencies {\n  implementation project(':old')\n}\n"
    val dependencies = BuildScriptDependenciesEditor.findProjectDependencies(source, GradleDsl.GROOVY)
    assertWithMessage(
        "Groovy dependency was not recognized.\nParser calls:\n%s",
        parserDiagnostics(source),
    ).that(dependencies).containsExactly(
        BuildScriptDependenciesEditor.ProjectDependency("implementation", ":old"),
    )
    val rename = BuildScriptDependenciesEditor.renameProjectDependency(source, "implementation", ":old", ":new", GradleDsl.GROOVY)
    assertWithMessage(
        "Groovy dependency rename was not applied.\nParser calls:\n%s",
        parserDiagnostics(source),
    ).that(rename).isInstanceOf(GradleEditResult.Applied::class.java)
    val output = apply(source, rename)
    assertThat(output).contains("project(':new')")
  }

  @Test fun groovyNamedProjectDependencyIsUnsupported() {
    val source = "dependencies { implementation project(path: ':feature') }"
    assertThat(BuildScriptDependenciesEditor.findProjectDependencies(source, GradleDsl.GROOVY)).isEmpty()
    assertThat(BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(source, ":feature", GradleDsl.GROOVY)).isTrue()
  }

  @Test fun projectDirectoryMappingCanBeUpdatedAndRemoved() {
    val source = "project(\":app\").projectDir = file(\"../app\")\n"
    val updated = apply(source, ProjectSettingsEditor.updateProjectDirMapping(source, ":app", "../custom-app", GradleDsl.KOTLIN))
    assertThat(ProjectSettingsEditor.findProjectDirectoryMappings(updated, GradleDsl.KOTLIN)).containsExactly(ProjectSettingsEditor.ProjectDirectoryMapping(":app", "\"../custom-app\""))
    val removed = apply(updated, ProjectSettingsEditor.removeProjectDirMapping(updated, ":app", GradleDsl.KOTLIN))
    assertThat(removed).isEmpty()
  }

  @Test fun nestedProjectDirectoryMappingCanBeUpdated() {
    val source = "project(\":demo\") {\n  projectDir = file(\"../demo\")\n}\n"
    val output = apply(source, ProjectSettingsEditor.updateProjectDirMapping(source, ":demo", "../new-demo", GradleDsl.KOTLIN))
    assertThat(output).contains("file(\"../new-demo\")")
  }

  @Test fun fakeDependenciesBlockInsideStringIsIgnored() {
    val source = "val text = \"dependencies { fake }\"\n"
    assertThat(BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", GradleDsl.KOTLIN))
        .isInstanceOf(GradleEditResult.Unsupported::class.java)
  }

  @Test fun groovyDependencyIsInsertedIntoAstLocatedBlock() {
    val source = "dependencies {\n}\n"
    val output = apply(source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", GradleDsl.GROOVY))
    assertThat(output).contains("implementation project(':feature')")
  }

  @Test fun kotlinDependencyIsInsertedIntoRealBlock() {
    val source = "val text = \"dependencies { fake }\"\ndependencies {\n  implementation(project(\":app\"))\n}\n"
    val output = apply(source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", GradleDsl.KOTLIN))
    assertThat(output).contains("implementation(project(\":feature\"))")
    assertThat(output).contains("val text = \"dependencies { fake }\"")
  }

  @Test fun kotlinDependencyUsesExistingEntryIndentAndNoBlankGap() {
    val source = "dependencies {\n    implementation(project(\":profile1\"))\n\n}\n"
    val output = apply(source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":profile2", GradleDsl.KOTLIN))
    assertThat(output).isEqualTo("dependencies {\n    implementation(project(\":profile1\"))\n    implementation(project(\":profile2\"))\n}\n")
  }

  @Test fun kotlinApplicationBuildScriptDependencyBlockIsFound() {
    val source = """plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.app"
    compileSdk = 36
}

dependencies {
    implementation(project(":profile1"))


}
"""
    val output = apply(source, BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":profile2", GradleDsl.KOTLIN))
    assertThat(output).contains("implementation(project(\":profile2\"))")
  }

  @Test fun kotlinMultipleProjectDependenciesRemainRemovable() {
    val source = "dependencies {\n  implementation(project(\":ldr\"))\n  implementation(project(\":inj\"))\n}\n"
    val dependencies = BuildScriptDependenciesEditor.findProjectDependencies(source, GradleDsl.KOTLIN)
    assertWithMessage(
        "Kotlin project dependencies were not recognized as removable.\nDependencies: %s\nParser calls and CST:\n%s",
        dependencies,
        parserDiagnostics(source),
    ).that(dependencies).containsExactly(
        BuildScriptDependenciesEditor.ProjectDependency("implementation", ":ldr"),
        BuildScriptDependenciesEditor.ProjectDependency("implementation", ":inj"),
    )
    assertWithMessage(
        "Standard Kotlin dependency implementation(project(\":ldr\")) was classified as unsupported.\nDependencies: %s\nParser calls and CST:\n%s",
        dependencies,
        parserDiagnostics(source),
    ).that(BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(source, ":ldr", GradleDsl.KOTLIN)).isFalse()
  }

  @Test fun supportedAndUnsupportedDependencyFormsAreDistinguished() {
    val supported = "dependencies { implementation(project(\":feature\")) }"
    val unsupported = "dependencies { implementation(project(path = \":feature\")) }"
    val commented = "// implementation(project(path = \":feature\"))\ndependencies { }"
    val supportedDependencies = BuildScriptDependenciesEditor.findProjectDependencies(supported, GradleDsl.KOTLIN)
    assertWithMessage(
        "Supported dependency was not recognized.\nParser calls:\n%s",
        parserDiagnostics(supported),
    ).that(supportedDependencies).containsExactly(
        BuildScriptDependenciesEditor.ProjectDependency("implementation", ":feature"),
    )
    assertThat(BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(supported, ":feature", GradleDsl.KOTLIN)).isFalse()
    assertThat(BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(unsupported, ":feature", GradleDsl.KOTLIN)).isTrue()
    assertThat(BuildScriptDependenciesEditor.hasUnsupportedProjectDependencyReference(commented, ":feature", GradleDsl.KOTLIN)).isFalse()
  }

  @Test fun projectDependencyCanBeRenamedAndRemoved() {
    val source = "dependencies {\n  implementation(project(\":old\"))\n  api(project(\":api-old\"))\n}\n"
    val renamed = apply(source, BuildScriptDependenciesEditor.renameProjectDependency(source, "implementation", ":old", ":new", GradleDsl.KOTLIN))
    assertThat(renamed).contains("project(\":new\")")
    val removed = apply(renamed, BuildScriptDependenciesEditor.removeProjectDependency(renamed, "api", ":api-old", GradleDsl.KOTLIN))
    assertThat(removed).doesNotContain(":api-old")
  }

  @Test fun transactionRestoresFilesAndOnlyDeletesTrackedDirectories() {
    val root = createTempDirectory("project-edit-").toFile()
    try {
      val settings = root.resolve("settings.gradle.kts").apply { writeText("include(\":app\")\n") }
      val newModule = root.resolve("feature")
      val transaction = ProjectEditTransaction.begin(root)
      transaction.capture(settings)
      transaction.trackCreatedDirectory(newModule)
      settings.writeText("changed")
      newModule.mkdirs()
      newModule.resolve("build.gradle.kts").writeText("plugins {}")
      assertThat(transaction.rollback()).isEmpty()
      assertThat(settings.readText()).isEqualTo("include(\":app\")\n")
      assertThat(newModule.exists()).isFalse()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun transactionMovesDirectoryAndRestoresItOnRollback() {
    val root = createTempDirectory("project-move-").toFile()
    try {
      val source = root.resolve("legacy").apply {
        mkdirs()
        resolve("build.gradle.kts").writeText("plugins {}")
      }
      val destination = root.resolve("feature/core")
      val transaction = ProjectEditTransaction.begin(root)
      transaction.moveDirectory(source, destination)
      assertThat(source.exists()).isFalse()
      assertThat(destination.resolve("build.gradle.kts").isFile).isTrue()
      assertThat(transaction.rollback()).isEmpty()
      assertThat(source.resolve("build.gradle.kts").isFile).isTrue()
      assertThat(destination.exists()).isFalse()
      assertThat(root.resolve("feature").exists()).isFalse()

      val completed = ProjectEditTransaction.begin(root)
      completed.moveDirectory(source, destination)
      completed.commit()
      assertThat(source.exists()).isFalse()
      assertThat(destination.resolve("build.gradle.kts").isFile).isTrue()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun multipleDependencyBlocksFailClosed() {
    val source = "dependencies { implementation(project(\":one\")) }\ndependencies { implementation(project(\":two\")) }\n"
    assertThat(BuildScriptDependenciesEditor.addProjectDependency(source, "implementation", ":feature", GradleDsl.KOTLIN)).isInstanceOf(GradleEditResult.Ambiguous::class.java)
    assertThat(BuildScriptDependenciesEditor.findProjectDependencies(source, GradleDsl.KOTLIN)).isEmpty()
  }

  @Test fun crlfIsPreserved() {
    val source = "include(\":app\")\r\n"
    val output = apply(source, ProjectSettingsEditor.addInclude(source, ":feature", GradleDsl.KOTLIN))
    assertThat(output).contains("\r\n")
    assertThat(output.replace("\r\n", "")).doesNotContain("\n")
  }

  @Test fun kotlinBuildFeaturesAreRead() {
    val source = """android {
    buildFeatures {
        viewBinding = true
        compose = false
        aidl = true
    }
}
"""
    assertThat(BuildFeatureScriptEditor.findEnabled(source, "viewBinding", GradleDsl.KOTLIN)).isTrue()
    assertThat(BuildFeatureScriptEditor.findEnabled(source, "compose", GradleDsl.KOTLIN)).isFalse()
    assertThat(BuildFeatureScriptEditor.findEnabled(source, "dataBinding", GradleDsl.KOTLIN)).isNull()
    assertThat(BuildFeatureScriptEditor.findEnabled(source, "aidl", GradleDsl.KOTLIN)).isTrue()
  }

  @Test fun groovyBuildFeaturesAreRead() {
    val source = """android {
    buildFeatures {
        viewBinding true
        compose false
        aidl true
    }
}
"""
    assertThat(BuildFeatureScriptEditor.findEnabled(source, "viewBinding", GradleDsl.GROOVY)).isTrue()
    assertThat(BuildFeatureScriptEditor.findEnabled(source, "compose", GradleDsl.GROOVY)).isFalse()
    assertThat(BuildFeatureScriptEditor.findEnabled(source, "mlModelBinding", GradleDsl.GROOVY)).isNull()
    assertThat(BuildFeatureScriptEditor.findEnabled(source, "aidl", GradleDsl.GROOVY)).isTrue()
  }

  @Test fun kotlinBuildFeatureValueIsRewrittenInPlace() {
    val source = "android {\n    buildFeatures {\n        viewBinding = true\n    }\n}\n"
    val output = apply(source, BuildFeatureScriptEditor.setBuildFeature(source, "viewBinding", false, GradleDsl.KOTLIN))
    assertThat(output).isEqualTo("android {\n    buildFeatures {\n        viewBinding = false\n    }\n}\n")
  }

  @Test fun groovyBuildFeatureValueIsRewrittenInPlace() {
    val source = "android {\n    buildFeatures {\n        viewBinding = true\n    }\n}\n"
    val output = apply(source, BuildFeatureScriptEditor.setBuildFeature(source, "viewBinding", false, GradleDsl.GROOVY))
    assertThat(output).isEqualTo("android {\n    buildFeatures {\n        viewBinding = false\n    }\n}\n")
  }

  @Test fun buildFeatureAlreadyInRequestedStateIsNoChange() {
    val source = "android {\n    buildFeatures {\n        viewBinding = true\n    }\n}\n"
    assertThat(BuildFeatureScriptEditor.setBuildFeature(source, "viewBinding", true, GradleDsl.KOTLIN))
        .isInstanceOf(GradleEditResult.NoChange::class.java)
  }

  @Test fun kotlinBuildFeatureIsAppendedWithExistingEntryIndent() {
    val source = "android {\n    buildFeatures {\n        viewBinding = true\n    }\n}\n"
    val output = apply(source, BuildFeatureScriptEditor.setBuildFeature(source, "compose", true, GradleDsl.KOTLIN))
    assertThat(output).isEqualTo("android {\n    buildFeatures {\n        viewBinding = true\n        compose = true\n    }\n}\n")
  }

  @Test fun groovyBuildFeatureIsAppendedWithExistingEntryIndent() {
    val source = "android {\n    buildFeatures {\n        viewBinding true\n    }\n}\n"
    val output = apply(source, BuildFeatureScriptEditor.setBuildFeature(source, "compose", false, GradleDsl.GROOVY))
    assertThat(output).isEqualTo("android {\n    buildFeatures {\n        viewBinding true\n        compose false\n    }\n}\n")
  }

  @Test fun buildFeaturesBlockIsCreatedInsideAndroidBlock() {
    val kotlinSource = "android {\n    namespace = \"com.example\"\n}\n"
    val kotlinOutput = apply(kotlinSource, BuildFeatureScriptEditor.setBuildFeature(kotlinSource, "mlModelBinding", true, GradleDsl.KOTLIN))
    assertThat(kotlinOutput).isEqualTo("android {\n    namespace = \"com.example\"\n    buildFeatures {\n        mlModelBinding = true\n    }\n}\n")

    val groovySource = "android {\n    namespace 'com.example'\n}\n"
    val groovyOutput = apply(groovySource, BuildFeatureScriptEditor.setBuildFeature(groovySource, "dataBinding", true, GradleDsl.GROOVY))
    assertThat(groovyOutput).isEqualTo("android {\n    namespace 'com.example'\n    buildFeatures {\n        dataBinding true\n    }\n}\n")
  }

  @Test fun buildFeatureEditsFailClosed() {
    assertThat(BuildFeatureScriptEditor.setBuildFeature("plugins {}\n", "viewBinding", true, GradleDsl.KOTLIN))
        .isInstanceOf(GradleEditResult.Unsupported::class.java)
    assertThat(BuildFeatureScriptEditor.setBuildFeature("android {}\nandroid {}\n", "viewBinding", true, GradleDsl.KOTLIN))
        .isInstanceOf(GradleEditResult.Ambiguous::class.java)
    val aidlSource = "android {}\n"
    val aidlOutput = apply(aidlSource, BuildFeatureScriptEditor.setBuildFeature(aidlSource, "aidl", true, GradleDsl.KOTLIN))
    assertThat(aidlOutput).isEqualTo("android {\n  buildFeatures {\n    aidl = true\n  }\n}\n")

    val dynamic = "android {\n    buildFeatures {\n        viewBinding = someFlag\n    }\n}\n"
    assertThat(BuildFeatureScriptEditor.setBuildFeature(dynamic, "viewBinding", true, GradleDsl.KOTLIN))
        .isInstanceOf(GradleEditResult.Unsupported::class.java)
  }

  @Test fun buildFeaturesInCommentsAndStringsAreIgnored() {
    val kotlinSource = "// buildFeatures { viewBinding = true }\nval fake = \"buildFeatures { viewBinding = true }\"\nandroid {\n    namespace = \"com.example\"\n}\n"
    val kotlinOutput = apply(kotlinSource, BuildFeatureScriptEditor.setBuildFeature(kotlinSource, "viewBinding", true, GradleDsl.KOTLIN))
    assertThat(kotlinOutput).contains("// buildFeatures { viewBinding = true }")
    assertThat(kotlinOutput).contains("val fake = \"buildFeatures { viewBinding = true }\"")
    assertThat(kotlinOutput).contains("    buildFeatures {\n        viewBinding = true\n    }")

    val groovySource = "// buildFeatures { viewBinding true }\ndef fake = 'buildFeatures { viewBinding true }'\nandroid {\n    namespace 'com.example'\n}\n"
    val groovyOutput = apply(groovySource, BuildFeatureScriptEditor.setBuildFeature(groovySource, "viewBinding", true, GradleDsl.GROOVY))
    assertThat(groovyOutput).contains("// buildFeatures { viewBinding true }")
    assertThat(groovyOutput).contains("def fake = 'buildFeatures { viewBinding true }'")
    assertThat(groovyOutput).contains("    buildFeatures {\n        viewBinding true\n    }")
  }

  private fun parserDiagnostics(source: String): String = buildString {
    append("source=").append(source.replace("\n", "\\n")).append('\n')
    for (dsl in GradleDsl.values()) {
      append(dsl.name).append(':')
      append(System.lineSeparator()).append(GradleParser.describeTree(source, dsl))
      GradleParser.parse(source, dsl).forEach { call ->
        append(" ").append(call.name)
            .append('[').append(call.start).append("..").append(call.end).append(']')
            .append(" args=").append(call.arguments)
            .append(" dynamic=").append(call.dynamic)
      }
      append(System.lineSeparator())
    }
  }

  private fun apply(source: String, result: GradleEditResult): String = TextEditApplier.apply(source, checkNotNull(result as? GradleEditResult.Applied).edits)
}