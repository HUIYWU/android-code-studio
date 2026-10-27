package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.backend.analysis.input.KotlinProjectChangeKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.input.KotlinProjectInputDiff
import com.tom.rv2ide.language.services.kotlin.backend.analysis.input.KotlinProjectInputDimension
import com.tom.rv2ide.language.services.kotlin.backend.analysis.input.KotlinProjectInputSnapshotState
import com.tom.rv2ide.projects.models.ProjectCompilerSettingsSnapshot
import com.tom.rv2ide.projects.models.ProjectInputSnapshot
import com.tom.rv2ide.projects.models.ProjectLibraryArchiveDescriptor
import com.tom.rv2ide.projects.models.ProjectLibraryAttachmentDescriptor
import com.tom.rv2ide.projects.models.ProjectModuleIdentity
import com.tom.rv2ide.projects.models.ProjectModuleInputSnapshot
import com.tom.rv2ide.projects.models.ProjectModuleKind
import com.tom.rv2ide.tooling.api.models.GradleKotlinDslModel
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KotlinLibraryAttachmentInputTest {

  @Test
  fun unchangedAndReorderedAttachmentsHaveTheSameFingerprint() {
    val first = attachment("first")
    val second = attachment("second")
    val before = state(listOf(first, second))
    val after = state(listOf(second, first))

    assertEquals(before.fingerprints, after.fingerprints)
    assertEquals(KotlinProjectChangeKind.NONE, KotlinProjectInputDiff.compare(before, after).kind)
  }

  @Test
  fun attachmentAdditionAndRemovalChangeTheLibraryClasspath() {
    val empty = state(emptyList())
    val attached = state(listOf(attachment("first")))

    assertLibraryChange(empty, attached)
    assertLibraryChange(attached, empty)
  }

  @Test
  fun archiveExistenceSizeAndModificationChangesAreDetected() {
    val original = attachment("first")
    val archive = original.sourceArchives.single()
    val changes = listOf(
        archive.copy(exists = false),
        archive.copy(size = 200L),
        archive.copy(lastModifiedMillis = 20L),
        archive.copy(path = Paths.get("/libraries/other-sources.jar")),
    )

    changes.forEach { changed ->
      assertLibraryChange(
          state(listOf(original)),
          state(listOf(original.copy(sourceArchives = listOf(changed)))),
      )
    }
  }

  @Test
  fun javadocAdditionAndRemovalAreDetectedWithoutChangingBinaryInputs() {
    val original = attachment("first")
    val documented = original.copy(
        javadocArchives = listOf(archive("first-javadoc")),
    )

    assertLibraryChange(state(listOf(original)), state(listOf(documented)))
    assertLibraryChange(state(listOf(documented)), state(listOf(original)))
  }

  @Test
  fun swappingSourcesBetweenLibrariesChangesTheirFingerprint() {
    val first = attachment("first")
    val second = attachment("second")
    val before = state(listOf(first, second))
    val after = state(listOf(
        first.copy(sourceArchives = second.sourceArchives),
        second.copy(sourceArchives = first.sourceArchives),
    ))

    assertLibraryChange(before, after)
  }

  @Test
  fun changingBinaryAssociationOrOwnerChangesTheFingerprint() {
    val original = attachment("first")
    val changes = listOf(
        original.copy(binaryPaths = listOf(Paths.get("/libraries/other.jar"))),
        original.copy(ownerModulePath = ":other"),
    )

    changes.forEach { changed ->
      assertLibraryChange(state(listOf(original)), state(listOf(changed)))
    }
  }

  private fun assertLibraryChange(
      before: KotlinProjectInputSnapshotState,
      after: KotlinProjectInputSnapshotState,
  ) {
    assertFalse(before.fingerprints.binaries == after.fingerprints.binaries)
    val change = KotlinProjectInputDiff.compare(before, after)
    assertEquals(KotlinProjectChangeKind.LIBRARY_CLASSPATH, change.kind)
    assertEquals(setOf(KotlinProjectInputDimension.BINARIES), change.dimensions)
    assertEquals(setOf(":library", ":app"), change.affectedModules)
    assertEquals(before.fingerprints.boot, after.fingerprints.boot)
    assertEquals(before.fingerprints.dependencyGraph, after.fingerprints.dependencyGraph)
  }

  private fun attachment(name: String) = ProjectLibraryAttachmentDescriptor(
      libraryIdentity = name,
      binaryPaths = listOf(Paths.get("/libraries/$name.jar")),
      sourceArchives = listOf(archive("$name-sources")),
      javadocArchives = emptyList(),
      ownerModulePath = ":library",
  )

  private fun archive(name: String) = ProjectLibraryArchiveDescriptor(
      path = Paths.get("/libraries/$name.jar"),
      exists = true,
      size = 100L,
      lastModifiedMillis = 10L,
  )

  private fun state(attachments: List<ProjectLibraryAttachmentDescriptor>) =
      KotlinProjectInputSnapshotState.from(
          ProjectInputSnapshot(
              workspaceRoot = Paths.get("/project"),
              modules = listOf(
                  module(":library", attachments),
                  module(":app", emptyList()).copy(compileModulePaths = listOf(":library")),
              ),
              gradleKotlinDslModel = GradleKotlinDslModel(),
          ),
      )

  private fun module(
      path: String,
      attachments: List<ProjectLibraryAttachmentDescriptor>,
  ) = ProjectModuleInputSnapshot(
      identity = ProjectModuleIdentity(
          modulePath = path,
          kind = ProjectModuleKind.ANDROID,
          projectDirectory = Paths.get("/project/${path.removePrefix(":")}"),
          buildDirectory = Paths.get("/project/${path.removePrefix(":")}/build"),
      ),
      compilerSettings = ProjectCompilerSettingsSnapshot("17", "17"),
      ordinarySourceRoots = emptyList(),
      generatedSourceRoots = emptyList(),
      javaSourceRoots = emptyList(),
      moduleOutputs = emptyList(),
      externalBinaries = emptyList(),
      bootBinaries = emptyList(),
      libraryAttachments = attachments,
      compileModulePaths = emptyList(),
      externalDependencyIdentities = emptyList(),
      variantSelection = null,
      variantArtifact = null,
  )
}
