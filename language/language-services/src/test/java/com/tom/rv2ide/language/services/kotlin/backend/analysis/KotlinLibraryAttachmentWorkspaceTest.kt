package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.lsp.testing.LspWorkspaceTestSupport
import com.tom.rv2ide.projects.models.ProjectInputSnapshotFactory
import com.tom.rv2ide.tooling.api.util.AndroidModulePropertyCopier
import com.tom.rv2ide.utils.FileProvider
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinLibraryAttachmentWorkspaceTest {

  @Test
  fun toolingAttachmentModelPropagation() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val appModule = workspace.androidProjects().single { it.path == ":app" }
      val binary = appModule.getCompileClasspaths().single { it.name == "kotlin-stdlib-2.3.21.jar" }.canonicalFile
      val library = appModule.libraryMap.values.single { it.artifact?.canonicalFile == binary }
      val originalSource = library.srcJar
      val originalSources = library.srcJars
      val originalJavadoc = library.docJar
      val buildDirectory = appModule.buildDir.toPath()
      Files.createDirectories(buildDirectory)
      val directory = Files.createTempDirectory(buildDirectory, "acs-attachment-input-")
      val source = directory.resolve("model-sources.jar")
      val missingSource = directory.resolve("missing-sources.jar")
      val javadoc = directory.resolve("model-javadoc.jar")
      try {
        JarOutputStream(Files.newOutputStream(source)).use { output ->
          output.putNextEntry(JarEntry("model/Attachment.kt"))
          output.write("package model\nclass Attachment\n".toByteArray(Charsets.UTF_8))
          output.closeEntry()
        }
        JarOutputStream(Files.newOutputStream(javadoc)).use { output ->
          output.putNextEntry(JarEntry("index.html"))
          output.write("<html>Model attachment</html>".toByteArray(Charsets.UTF_8))
          output.closeEntry()
        }
        library.srcJar = null
        library.srcJars = listOf(source.toFile(), missingSource.toFile(), source.toFile())
        library.docJar = javadoc.toFile()

        val copied = AndroidModulePropertyCopier.copy(library)
        assertNull(copied.srcJar)
        assertEquals(library.srcJars, copied.srcJars)
        assertEquals(library.docJar, copied.docJar)

        val binaryPath = binary.toPath()
        val sourcePath = source.toFile().canonicalFile.toPath()
        val missingPath = missingSource.toFile().canonicalFile.toPath()
        val javadocPath = javadoc.toFile().canonicalFile.toPath()
        val snapshot = ProjectInputSnapshotFactory.capture(workspace).modules.single { it.identity.modulePath == ":app" }
        val attachment = snapshot.libraryAttachments.single { binaryPath in it.binaryPaths }
        assertEquals(":app", attachment.ownerModulePath)
        assertEquals(listOf(binaryPath), attachment.binaryPaths)
        assertEquals(setOf(sourcePath, missingPath), attachment.sourceArchives.map { it.path }.toSet())
        assertEquals(2, attachment.sourceArchives.size)
        val sourceArchive = attachment.sourceArchives.single { it.path == sourcePath }
        assertTrue(sourceArchive.exists)
        assertEquals(source.toFile().length(), requireNotNull(sourceArchive.size))
        assertEquals(source.toFile().lastModified(), requireNotNull(sourceArchive.lastModifiedMillis))
        val missingArchive = attachment.sourceArchives.single { it.path == missingPath }
        assertFalse(missingArchive.exists)
        assertNull(missingArchive.size)
        assertNull(missingArchive.lastModifiedMillis)
        assertEquals(javadocPath, attachment.javadocArchives.single().path)
        assertTrue(snapshot.externalBinaries.none { it.path in setOf(sourcePath, missingPath, javadocPath) })

        val context = requireNotNull(KotlinAnalysisProjectContext.create(workspace).module(":app"))
        val contextAttachment = context.libraryAttachments.single { binaryPath in it.binaryPaths }
        assertEquals(attachment.libraryIdentity, contextAttachment.libraryIdentity)
        assertEquals(attachment.ownerModulePath, contextAttachment.ownerModulePath)
        assertEquals(attachment.binaryPaths, contextAttachment.binaryPaths)
        assertEquals(attachment.sourceArchives.map { it.path }, contextAttachment.sourcePaths)
        assertEquals(attachment.javadocArchives.map { it.path }, contextAttachment.javadocPaths)
        assertTrue(context.binaryRoots.none { it in setOf(sourcePath, missingPath, javadocPath) })
      } finally {
        library.srcJar = originalSource
        library.srcJars = originalSources
        library.docJar = originalJavadoc
        Files.deleteIfExists(source)
        Files.deleteIfExists(javadoc)
        Files.deleteIfExists(directory)
      }
    }
  }
}
