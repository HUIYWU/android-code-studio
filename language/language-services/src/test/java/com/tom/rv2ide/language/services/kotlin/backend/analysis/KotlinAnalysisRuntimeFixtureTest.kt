package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.testing.LspWorkspaceTestSupport
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import com.tom.rv2ide.utils.FileProvider
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisRuntimeFixtureTest {

  init {
    IdeLogConfig.setIdeLogsEnabledProvider { true }
    IdeLogConfig.setIdeDebugLogsEnabledProvider { true }
  }

  @Test
  fun realWorkspaceContainsKotlinModulesAndRuntimeStarts() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val appFile = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val libraryFile = fixturePath("library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt")
      val javaFile = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      val appModule = requireNotNull(workspace.findModuleForFile(appFile, checkExistance = true))
      val libraryModule = requireNotNull(workspace.findModuleForFile(libraryFile, checkExistance = true))
      val javaModule = requireNotNull(workspace.findModuleForFile(javaFile, checkExistance = true))

      assertEquals(":app", appModule.path)
      assertEquals(":library", libraryModule.path)
      assertEquals(":app", javaModule.path)
      assertTrue(appModule.getCompileModuleProjects().any { it.path == ":library" })
      assertTrue(appModule.getCompileSourceDirectories().any { appFile.startsWith(it.toPath()) })
      assertTrue(appModule.getCompileSourceDirectories().any { javaFile.startsWith(it.toPath()) })
      assertTrue(libraryModule.getCompileSourceDirectories().any { libraryFile.startsWith(it.toPath()) })

      val runtime = createRuntime(workspace)
      try {
        assertRuntimeStarted(runtime)
      } finally {
        runtime.close()
      }
    }
  }

  @Test
  fun realRuntimeUsesActiveDocumentContentForLivePsi() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ActiveDocument.kt")
      val diskContent = file.readText()
      val activeContent = diskContent.replace("diskFunction(): Int = 1", "activeFunction(): Int = 2")
      openDocument(file, activeContent, version = 7, revision = 17L)

      val runtime = createRuntime(workspace)
      try {
        assertRuntimeStarted(runtime)
        runAnalysis { runtime.onFileOpened(file) }
        val snapshot = requireNotNull(FileManager.getActiveDocumentSnapshot(file))
        assertEquals(activeContent, snapshot.content)
        assertEquals(7, snapshot.version)
        assertEquals(17L, snapshot.revision)

        val request = request(file, activeContent, "activeFunction")
        val hover = runAnalysis { runtime.hover(request) }
        assertNotNull(hover)
        assertTrue(hover!!.value.contains("activeFunction"))
      } finally {
        runtime.close()
      }
    }
  }

  @Test
  fun realRuntimeExecutesFiveSemanticOperations() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val content = file.readText()
      openDocument(file, content, version = 1, revision = 1L)

      val runtime = createRuntime(workspace)
      try {
        assertRuntimeStarted(runtime)
        runAnalysis { runtime.onFileOpened(file) }

        val hover = runAnalysis { runtime.hover(request(file, content, "LibraryApi")) }
        assertNotNull(hover)

        val definition = runAnalysis { runtime.findDefinition(request(file, content, "LibraryApi")) }
        val definitionLocation = requireNotNull(definition).locations.single()
        assertEquals(
            fixturePath("library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt"),
            definitionLocation.file,
        )

        val signature = runAnalysis { runtime.signatureHelp(request(file, content, "load")) }
        assertNotNull(signature)

        val completion = runAnalysis { runtime.complete(request(file, content, "api")) }
        assertNotNull(completion)

        val references = runAnalysis { runtime.findReferences(request(file, content, "LibraryApi")) }
        assertNotNull(references)
      } finally {
        runtime.close()
      }
    }
  }

  @Test
  fun realRuntimeResolvesJavaSourceAndJdkMembers() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val content = file.readText()
      val javaFile = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java")
      openDocument(file, content, version = 3, revision = 3L)

      val runtime = createRuntime(workspace)
      try {
        assertRuntimeStarted(runtime)
        runAnalysis { runtime.onFileOpened(file) }

        val javaHover = runAnalysis { runtime.hover(request(file, content, "JavaInterop")) }
        assertNotNull(javaHover)

        val javaDefinition = runAnalysis { runtime.findDefinition(request(file, content, "JavaInterop")) }
        assertEquals(javaFile, requireNotNull(javaDefinition).locations.single().file)

        val jdkHover = runAnalysis { runtime.hover(request(file, content, "length")) }
        assertNotNull(jdkHover)
      } finally {
        runtime.close()
      }
    }
  }

  @Test
  fun realRuntimeResolvesLibraryInheritanceExtensionAndReferences() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val content = file.readText()
      val libraryApiFile = fixturePath("library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt")
      val libraryBaseFile = fixturePath("library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryBase.kt")
      openDocument(file, content, version = 2, revision = 2L)

      val runtime = createRuntime(workspace)
      try {
        assertRuntimeStarted(runtime)
        runAnalysis { runtime.onFileOpened(file) }

        val baseDefinition = runAnalysis { runtime.findDefinition(request(file, content, "LibraryBase")) }
        assertEquals(libraryBaseFile, requireNotNull(baseDefinition).locations.single().file)

        val extensionDefinition = runAnalysis { runtime.findDefinition(request(file, content, "libraryExtension")) }
        assertEquals(libraryApiFile, requireNotNull(extensionDefinition).locations.single().file)

        val references = runAnalysis {
          runtime.findReferences(request(file, content, "LibraryApi", includeDeclaration = true))
        }
        val result = requireNotNull(references)
        assertEquals(result.locations.size, result.roles.size)
        assertTrue(result.locations.any { it.file == file })
        assertTrue(result.locations.any { it.file == libraryApiFile })
      } finally {
        runtime.close()
      }
    }
  }

  private fun assertRuntimeStarted(runtime: KotlinStandaloneAnalysisRuntime) {
    val started = runtime.start()
    assertTrue(
        "${runtime.startFailureReason ?: "Kotlin Analysis runtime failed to start"}\n${runtime.startFailure?.stackTraceToString().orEmpty()}",
        started,
    )
  }

  private fun <T> runAnalysis(action: () -> T): T =
      CompletableFuture.supplyAsync(action).join()

  private fun fixturePath(relative: String): Path =
      FileProvider.kotlinAnalysisProjectRoot().resolve(relative).normalize()

  private fun createRuntime(workspace: IWorkspace): KotlinStandaloneAnalysisRuntime {
    val home = File(BaseApplication.getBaseInstance()!!.filesDir, "kotlin-analysis-fixture")
    val config = File(home, "config")
    val system = File(home, "system")
    listOf(home, config, system).forEach { check(it.isDirectory || it.mkdirs()) }
    System.setProperty("idea.home.path", home.absolutePath)
    System.setProperty("idea.config.path", config.absolutePath)
    System.setProperty("idea.system.path", system.absolutePath)
    return KotlinStandaloneAnalysisRuntime(
        KotlinProjectClasspathProvider(workspace),
        FileProvider.projectRoot()
            .resolve("docs/misc/kotlin-android/analysis-api-standalone-embeddable-for-ide-2.3.255-SNAPSHOT.jar")
            .toString(),
    )
  }

  private fun request(
      file: Path,
      content: String,
      marker: String,
      includeDeclaration: Boolean = false,
  ): KotlinSemanticRequest {
    val offset = content.indexOf(marker)
    require(offset >= 0) { "Fixture marker not found: $marker" }
    val position = LineIndex.from(content).indexToPosition(offset)
    val snapshot = requireNotNull(FileManager.getActiveDocumentSnapshot(file))
    return KotlinSemanticRequest(
        snapshot = ActiveDocumentSnapshot(file, snapshot.version, Instant.EPOCH, content, snapshot.revision),
        line = position.line,
        column = position.column,
        backendGeneration = 1L,
        cancelChecker = ICancelChecker.NOOP,
        prefix = marker,
        includeDeclaration = includeDeclaration,
    )
  }
}
