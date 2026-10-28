package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.diskRequest
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.request
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.android.AndroidModule
import java.io.File
import java.nio.file.Files
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisRuntimeTest {

  @Test
  fun workspaceAndBasicSemantics() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val content = file.readText()
      val libraryApiFile = fixturePath("library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt")
      val libraryBaseFile = fixturePath("library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryBase.kt")
      val javaFile = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val appModule = requireNotNull(workspace.findModuleForFile(file, checkExistance = true))
      val libraryModule = requireNotNull(workspace.findModuleForFile(libraryApiFile, checkExistance = true))
      val javaModule = requireNotNull(workspace.findModuleForFile(javaFile, checkExistance = true))
      assertEquals(":app", appModule.path)
      assertEquals(":library", libraryModule.path)
      assertEquals(":app", javaModule.path)
      assertTrue(appModule.getCompileModuleProjects().any { it.path == ":library" })
      assertTrue(appModule.getCompileSourceDirectories().any { file.startsWith(it.toPath()) })
      assertTrue(appModule.getCompileSourceDirectories().any { javaFile.startsWith(it.toPath()) })
      assertTrue(libraryModule.getCompileSourceDirectories().any { libraryApiFile.startsWith(it.toPath()) })
      assertTrue(appModule.getCompileClasspaths().any { it.name == "kotlin-stdlib-2.3.21.jar" })
      openDocument(file, content, version = 1, revision = 1L)

      withRuntime(workspace) { runtime ->
        runAnalysis { runtime.onFileOpened(file) }
        val hover = requireNotNull(runAnalysis { runtime.hover(request(file, content, "LibraryApi")) })
        assertEquals(MarkupKind.MARKDOWN, hover.kind)
        assertTrue(hover.value.startsWith("```kotlin\n"))
        assertTrue(hover.value.contains("class LibraryApi"))

        val definition = requireNotNull(runAnalysis { runtime.findDefinition(request(file, content, "LibraryApi")) })
        val definitionLocation = definition.locations.single()
        val libraryApiContent = libraryApiFile.readText()
        val libraryApiStart = libraryApiContent.indexOf("LibraryApi")
        assertTrue(libraryApiStart >= 0)
        assertEquals(libraryApiFile, definitionLocation.file)
        assertEquals(
            LineIndex.from(libraryApiContent).indexToPosition(libraryApiStart),
            definitionLocation.range.start,
        )
        assertEquals(
            LineIndex.from(libraryApiContent).indexToPosition(libraryApiStart + "LibraryApi".length),
            definitionLocation.range.end,
        )

        val signatureHelp = requireNotNull(runAnalysis { runtime.signatureHelp(request(file, content, "load")) })
        val loadSignature = signatureHelp.signatures.single()
        assertEquals("load(value: String)", loadSignature.label)
        assertEquals("value: String", loadSignature.parameters.single().label)
        assertEquals(0, signatureHelp.activeSignature)
        assertEquals(0, signatureHelp.activeParameter)
        assertNotNull(runAnalysis { runtime.complete(request(file, content, "api")) })
        assertNotNull(runAnalysis { runtime.findReferences(request(file, content, "LibraryApi")) })
        assertNotNull(runAnalysis { runtime.hover(request(file, content, "ArrayDeque")) })
        assertNotNull(runAnalysis { runtime.hover(request(file, content, "JavaInterop")) })
        val javaDefinition = runAnalysis { runtime.findDefinition(request(file, content, "JavaInterop")) }
        val javaDefinitionLocation = requireNotNull(javaDefinition).locations.single()
        val javaContent = javaFile.readText()
        val javaNameStart = javaContent.indexOf("JavaInterop")
        assertTrue(javaNameStart >= 0)
        assertEquals(javaFile, javaDefinitionLocation.file)
        assertEquals(
            LineIndex.from(javaContent).indexToPosition(javaNameStart),
            javaDefinitionLocation.range.start,
        )
        assertEquals(
            LineIndex.from(javaContent).indexToPosition(javaNameStart + "JavaInterop".length),
            javaDefinitionLocation.range.end,
        )
        val constructorDefinition = requireNotNull(runAnalysis {
          runtime.findDefinition(request(file, content, "LibraryApi", occurrence = 1))
        }).locations.single()
        val libraryClassStart = libraryApiContent.indexOf("LibraryApi")
        assertEquals(
            LineIndex.from(libraryApiContent).indexToPosition(libraryClassStart),
            constructorDefinition.range.start,
        )
        assertEquals(
            LineIndex.from(libraryApiContent).indexToPosition(libraryClassStart + "LibraryApi".length),
            constructorDefinition.range.end,
        )
        assertNotNull(runAnalysis { runtime.hover(request(file, content, "length")) })

        val baseDefinition = runAnalysis { runtime.findDefinition(request(file, content, "LibraryBase")) }
        assertEquals(libraryBaseFile, requireNotNull(baseDefinition).locations.single().file)
        val extensionDefinition = runAnalysis { runtime.findDefinition(request(file, content, "libraryExtension")) }
        assertEquals(libraryApiFile, requireNotNull(extensionDefinition).locations.single().file)
        val references = requireNotNull(runAnalysis {
          runtime.findReferences(request(file, content, "LibraryApi", includeDeclaration = true))
        })
        assertEquals(references.locations.size, references.roles.size)
        assertTrue(references.locations.any { it.file == file })
        assertTrue(references.locations.any { it.file == libraryApiFile })
      }
    }
  }

  @Test
  fun missingRequiredArgumentStillProducesSignatureHelp() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/MissingArgument.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi

          fun missingArgument(): String = LibraryApi().load()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val signatureHelp = requireNotNull(runAnalysis {
            runtime.signatureHelp(diskRequest(file, content, "load"))
          })
          assertEquals("load(value: String)", signatureHelp.signatures.single().label)
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun signatureHelpKeepsOverloadsAfterOneCandidateMatchesExactly() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/SignatureOverloads.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          fun overloaded(value: String): String = value
          fun overloaded(value: String, suffix: String): String = value + suffix

          fun useOverload(): String = overloaded("value")
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val signatureHelp = requireNotNull(runAnalysis {
            runtime.signatureHelp(diskRequest(file, content, "overloaded", occurrence = 2))
          })
          val labels = signatureHelp.signatures.map { it.label }
          assertTrue(labels.contains("overloaded(value: String)"))
          assertTrue(labels.contains("overloaded(value: String, suffix: String)"))
        }
      } finally {
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun activeDiskAndReopenedDocumentIdentity() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ActiveDocument.kt")
      val diskContent = file.readText()
      val activeContent = diskContent.replace("diskFunction(): Int = 1", "activeFunction(): Int = 2")
      val reopenedContent = diskContent.replace("diskFunction(): Int = 1", "reopenedFunction(): Int = 3")
      openDocument(file, activeContent, version = 7, revision = 17L)

      withRuntime(workspace) { runtime ->
        runAnalysis { runtime.onFileOpened(file) }
        val snapshot = requireNotNull(FileManager.getActiveDocumentSnapshot(file))
        assertEquals(activeContent, snapshot.content)
        assertEquals(7, snapshot.version)
        assertEquals(17L, snapshot.revision)
        val activeHover = runAnalysis { runtime.hover(request(file, activeContent, "activeFunction")) }
        assertNotNull(activeHover)
        assertTrue(activeHover!!.value.contains("activeFunction"))
        val activeDefinition = requireNotNull(runAnalysis {
          runtime.findDefinition(request(file, activeContent, "activeFunction"))
        }).locations.single()
        assertEquals(file, activeDefinition.file)
        val activeNameStart = activeContent.indexOf("activeFunction")
        assertTrue(activeNameStart >= 0)
        assertEquals(
            LineIndex.from(activeContent).indexToPosition(activeNameStart),
            activeDefinition.range.start,
        )
        assertEquals(
            LineIndex.from(activeContent).indexToPosition(activeNameStart + "activeFunction".length),
            activeDefinition.range.end,
        )

        closeDocument(file)
        assertNull(FileManager.getActiveDocumentSnapshot(file))
        runAnalysis { runtime.onFileClosed(file) }
        val diskHover = runAnalysis { runtime.hover(diskRequest(file, diskContent, "diskFunction")) }
        assertNotNull(diskHover)
        assertTrue(diskHover!!.value.contains("diskFunction"))

        openDocument(file, reopenedContent, version = 8, revision = 18L)
        runAnalysis { runtime.onFileOpened(file) }
        val reopenedHover = runAnalysis { runtime.hover(request(file, reopenedContent, "reopenedFunction")) }
        assertNotNull(reopenedHover)
        assertTrue(reopenedHover!!.value.contains("reopenedFunction"))
      }
    }
  }

  @Test
  fun generatedSourceFromToolingRoot() {
    withWorkspace {
      val appModule = workspace.getSubProjects().filterIsInstance<AndroidModule>().single { it.path == ":app" }
      val generatedRoot = appModule.getSourceDirectories()
          .map(File::toPath)
          .firstOrNull { it.normalize().toString().contains("/build/generated/") }
          ?: error("Tooling fixture did not expose an AGP generated source root")
      val generatedDirectory = generatedRoot.resolve("acs-fixture-generated")
      val packageDirectory = generatedDirectory.resolve("com/tom/rv2ide/testing/kotlinfixture/generated")
      val generatedFile = packageDirectory.resolve("GeneratedApi.kt")
      val generatedContent = """
          package com.tom.rv2ide.testing.kotlinfixture.generated

          fun generatedApi(): String = "generated"
      """.trimIndent() + "\n"
      Files.createDirectories(packageDirectory)
      try {
        Files.write(generatedFile, generatedContent.toByteArray(Charsets.UTF_8))
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(generatedFile) }
          val hover = runAnalysis { runtime.hover(diskRequest(generatedFile, generatedContent, "generatedApi")) }
          assertNotNull(hover)
          assertTrue(hover!!.value.contains("generatedApi"))
          val definition = requireNotNull(runAnalysis {
            runtime.findDefinition(diskRequest(generatedFile, generatedContent, "generatedApi"))
          }).locations.single()
          assertEquals(generatedFile, definition.file)
          val nameStart = generatedContent.indexOf("generatedApi")
          assertTrue(nameStart >= 0)
          assertEquals(
              LineIndex.from(generatedContent).indexToPosition(nameStart),
              definition.range.start,
          )
          assertEquals(
              LineIndex.from(generatedContent).indexToPosition(nameStart + "generatedApi".length),
              definition.range.end,
          )
        }
      } finally {
        Files.deleteIfExists(generatedFile)
        var directory = packageDirectory
        while (directory != generatedDirectory) {
          Files.deleteIfExists(directory)
          directory = directory.parent
        }
        Files.deleteIfExists(generatedDirectory)
      }
    }
  }
}
