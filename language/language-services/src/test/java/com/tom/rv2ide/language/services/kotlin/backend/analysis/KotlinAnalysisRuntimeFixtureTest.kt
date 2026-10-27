package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.google.gson.JsonObject
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequestValidator
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.testing.LspWorkspaceTestSupport
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import com.tom.rv2ide.utils.FileProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
  fun realBackendRejectsStaleActiveDocumentRequests() = runBlocking {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ActiveDocument.kt")
      val diskContent = file.readText()
      val firstContent = diskContent.replace("diskFunction(): Int = 1", "firstFunction(): Int = 1")
      val secondContent = firstContent.replace("firstFunction(): Int = 1", "secondFunction(): Int = 2")
      openDocument(file, firstContent, version = 1, revision = 11L)

      val backend = createBackend()
      try {
        assertTrue(backend.startBackend(KotlinProjectClasspathProvider(workspace)))
        var initialized = false
        backend.initialize(JsonObject()) { initialized = it != null }
        assertTrue(initialized)
        backend.sendNotification("textDocument/didOpen", documentNotification(file))

        val validator = KotlinSemanticRequestValidator(backend)
        val semanticBackend = AnalysisKotlinSemanticBackend(backend, validator)
        val firstPosition = LineIndex.from(firstContent).indexToPosition(firstContent.indexOf("firstFunction"))
        val oldRequest = requireNotNull(
            validator.capture(
                file,
                firstPosition,
                documentVersion = 1,
                documentRevision = 11L,
                cancelChecker = ICancelChecker.Default(),
            ),
        )
        val oldHover = runSuspend { semanticBackend.hover(oldRequest) }
        assertTrue(oldHover.value.contains("firstFunction"))

        changeDocument(file, firstContent, secondContent, version = 2, revision = 12L)
        backend.sendNotification("textDocument/didChange", documentNotification(file))

        val snapshot = requireNotNull(FileManager.getActiveDocumentSnapshot(file))
        assertEquals(secondContent, snapshot.content)
        assertEquals(2, snapshot.version)
        assertEquals(12L, snapshot.revision)
        assertTrue(!validator.isCurrent(oldRequest))
        assertEquals("", runSuspend { semanticBackend.hover(oldRequest) }.value)

        val secondPosition = LineIndex.from(secondContent).indexToPosition(secondContent.indexOf("secondFunction"))
        val currentRequest = requireNotNull(
            validator.capture(
                file,
                secondPosition,
                documentVersion = 2,
                documentRevision = 12L,
                cancelChecker = ICancelChecker.Default(),
            ),
        )
        val currentHover = runSuspend { semanticBackend.hover(currentRequest) }
        assertTrue(currentHover.value.contains("secondFunction"))

      } finally {
        backend.close()
      }
    }
  }

  @Test
  fun realBackendDropsCancelledResultAfterRealRuntimeExecution() = runBlocking {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ActiveDocument.kt")
      val content = file.readText()
      openDocument(file, content, version = 1, revision = 1L)

      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      prepareAnalysisPaths()
      val backend = KotlinAnalysisBackendConnection(
          KotlinAnalysisRuntimeFactory { provider ->
            val delegate = KotlinStandaloneAnalysisRuntime(provider, analysisApiJar())
            object : KotlinAnalysisRuntime by delegate {
              override fun hover(request: KotlinSemanticRequest): MarkupContent? {
                val result = delegate.hover(request)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return result
              }
            }
          },
      )
      try {
        assertTrue(backend.startBackend(KotlinProjectClasspathProvider(workspace)))
        var initialized = false
        backend.initialize(JsonObject()) { initialized = it != null }
        assertTrue(initialized)
        backend.sendNotification("textDocument/didOpen", documentNotification(file))

        val validator = KotlinSemanticRequestValidator(backend)
        val semanticBackend = AnalysisKotlinSemanticBackend(backend, validator)
        val position = LineIndex.from(content).indexToPosition(content.indexOf("diskFunction"))
        val checker = ICancelChecker.Default()
        val request = requireNotNull(
            validator.capture(
                file,
                position,
                documentVersion = 1,
                documentRevision = 1L,
                cancelChecker = checker,
            ),
        )
        val result = async(Dispatchers.Default) { semanticBackend.hover(request) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        checker.cancel()
        release.countDown()
        assertEquals("", runSuspend { withTimeout(5_000L) { result.await() } }.value)
      } finally {
        release.countDown()
        backend.close()
      }
    }
  }

  @Test
  fun realBackendCancelsReferencesAtMultipleSearchCheckpoints() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val content = file.readText()
      openDocument(file, content, version = 5, revision = 5L)
      val appModule = workspace.androidProjects().single { it.path == ":app" }
      val variant = requireNotNull(appModule.getSelectedVariant())
      val generatedRoot = variant.mainArtifact.generatedSourceFolders
          .map { it.toPath().normalize() }
          .sortedBy(Path::toString)
          .firstOrNull()
          ?: error("Tooling fixture did not expose an AGP generated source root")
      Files.createDirectories(generatedRoot)
      val directory = Files.createTempDirectory(generatedRoot, "acs-references-fixture-")
      val fanoutFiles = (0 until 8).map { directory.resolve("ReferenceFanout$it.kt") }
      val returnedSearches = AtomicInteger()
      val cancelledSearches = AtomicInteger()
      var backend: KotlinAnalysisBackendConnection? = null
      try {
        fanoutFiles.forEachIndexed { fileIndex, path ->
          val source = buildString {
            append("package com.tom.rv2ide.testing.kotlinfixture.fanout\n\n")
            append("import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi\n\n")
            repeat(32) { referenceIndex ->
              append("fun reference${fileIndex}_$referenceIndex(): LibraryApi = LibraryApi()\n")
            }
          }
          Files.write(path, source.toByteArray(Charsets.UTF_8))
        }
        prepareAnalysisPaths()
        val connection = KotlinAnalysisBackendConnection(
            KotlinAnalysisRuntimeFactory { provider ->
              val delegate = KotlinStandaloneAnalysisRuntime(provider, analysisApiJar())
              object : KotlinAnalysisRuntime by delegate {
                override fun findReferences(request: KotlinSemanticRequest): ReferenceResult? {
                  return try {
                    delegate.findReferences(request).also { returnedSearches.incrementAndGet() }
                  } catch (cancelled: CancellationException) {
                    cancelledSearches.incrementAndGet()
                    throw cancelled
                  }
                }
              }
            },
        )
        backend = connection
        assertTrue(connection.startBackend(KotlinProjectClasspathProvider(workspace)))
        connection.markInitialized()
        assertTrue(connection.isInitialized)
        connection.sendNotificationOrThrow("textDocument/didOpen", documentNotification(file))
        val validator = KotlinSemanticRequestValidator(connection)
        val semanticBackend = AnalysisKotlinSemanticBackend(connection, validator)
        val position = LineIndex.from(content).indexToPosition(content.indexOf("LibraryApi"))

        fun search(checker: ICancelChecker): ReferenceResult {
          val captured = requireNotNull(
              validator.capture(
                  file,
                  position,
                  documentVersion = 5,
                  documentRevision = 5L,
                  cancelChecker = checker,
                  includeDeclaration = true,
              ),
          )
          return runSuspend {
            withTimeout(30_000L) { semanticBackend.findReferences(captured) }
          }
        }

        val baselineChecker = CheckpointCancelChecker()
        val baseline = search(baselineChecker)
        assertEquals(baseline.locations.size, baseline.roles.size)
        fanoutFiles.forEach { path ->
          assertTrue("References did not include fan-out file: $path", baseline.locations.count { it.file == path } >= 32)
        }
        assertEquals(1, returnedSearches.get())
        assertEquals(0, cancelledSearches.get())
        val checkpoints = baselineChecker.checkpoints.get()
        assertTrue("References did not traverse enough cancellation checkpoints", checkpoints > 300)

        listOf(checkpoints / 3, checkpoints * 2 / 3).forEachIndexed { index, cancelAt ->
          val checker = CheckpointCancelChecker(cancelAt)
          val cancelled = search(checker)
          assertTrue(checker.isCancelled())
          assertEquals(cancelAt, checker.checkpoints.get())
          assertTrue(cancelled.locations.isEmpty())
          assertTrue(cancelled.roles.isEmpty())
          assertEquals(index + 1, cancelledSearches.get())
          assertEquals(1, returnedSearches.get())
        }

        val recovered = search(ICancelChecker.Default())
        assertEquals(baseline.locations.toSet(), recovered.locations.toSet())
        assertEquals(2, returnedSearches.get())
        assertEquals(2, cancelledSearches.get())
      } finally {
        try {
          backend?.close()
        } finally {
          fanoutFiles.forEach { Files.deleteIfExists(it) }
          Files.deleteIfExists(directory)
        }
      }
    }
  }

  @Test
  fun realRuntimeResolvesGeneratedSourceFromToolingRoot() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val appModule = workspace.getSubProjects()
          .filterIsInstance<AndroidModule>()
          .single { it.path == ":app" }
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
      Files.write(generatedFile, generatedContent.toByteArray(Charsets.UTF_8))

      val runtime = createRuntime(workspace)
      try {
        assertRuntimeStarted(runtime)
        runAnalysis { runtime.onFileOpened(generatedFile) }

        val hover = runAnalysis { runtime.hover(diskRequest(generatedFile, generatedContent, "generatedApi")) }
        assertNotNull(hover)
        assertTrue(hover!!.value.contains("generatedApi"))
      } finally {
        runtime.close()
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

  @Test
  fun realRuntimeTracksActiveDiskAndReopenedDocumentIdentity() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ActiveDocument.kt")
      val diskContent = file.readText()
      val activeContent = diskContent.replace("diskFunction(): Int = 1", "activeFunction(): Int = 2")
      val reopenedContent = diskContent.replace("diskFunction(): Int = 1", "reopenedFunction(): Int = 3")
      openDocument(file, activeContent, version = 7, revision = 17L)

      val runtime = createRuntime(workspace)
      try {
        assertRuntimeStarted(runtime)
        runAnalysis { runtime.onFileOpened(file) }

        val activeHover = runAnalysis { runtime.hover(request(file, activeContent, "activeFunction")) }
        assertNotNull(activeHover)
        assertTrue(activeHover!!.value.contains("activeFunction"))

        closeDocument(file)
        assertNull(FileManager.getActiveDocumentSnapshot(file))
        runAnalysis { runtime.onFileClosed(file) }

        val diskRequest = diskRequest(file, diskContent, "diskFunction")
        val diskHover = runAnalysis { runtime.hover(diskRequest) }
        assertNotNull(diskHover)
        assertTrue(diskHover!!.value.contains("diskFunction"))

        openDocument(file, reopenedContent, version = 8, revision = 18L)
        runAnalysis { runtime.onFileOpened(file) }

        val reopenedHover = runAnalysis { runtime.hover(request(file, reopenedContent, "reopenedFunction")) }
        assertNotNull(reopenedHover)
        assertTrue(reopenedHover!!.value.contains("reopenedFunction"))
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
        val hoverContent = requireNotNull(hover)
        assertEquals(MarkupKind.MARKDOWN, hoverContent.kind)
        assertTrue(hoverContent.value.startsWith("```kotlin\n"))
        assertTrue(hoverContent.value.contains("class LibraryApi"))

        val definition = runAnalysis { runtime.findDefinition(request(file, content, "LibraryApi")) }
        val definitionLocation = requireNotNull(definition).locations.single()
        val libraryApiFile = fixturePath("library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt")
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

        val signature = runAnalysis { runtime.signatureHelp(request(file, content, "load")) }
        val signatureHelp = requireNotNull(signature)
        val loadSignature = signatureHelp.signatures.single()
        assertEquals("load(value: String)", loadSignature.label)
        assertEquals("value: String", loadSignature.parameters.single().label)
        assertEquals(0, signatureHelp.activeSignature)
        assertEquals(0, signatureHelp.activeParameter)


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
  fun realRuntimeResolvesKotlinBinaryLibraryMembers() {
    LspWorkspaceTestSupport.withWorkspace(FileProvider.kotlinAnalysisProjectRoot()) {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val content = file.readText()
      val appModule = requireNotNull(workspace.findModuleForFile(file, checkExistance = true))
      assertTrue(
          appModule.getCompileClasspaths().any { it.name == "kotlin-stdlib-2.3.21.jar" },
      )
      openDocument(file, content, version = 4, revision = 4L)

      val runtime = createRuntime(workspace)
      try {
        assertRuntimeStarted(runtime)
        runAnalysis { runtime.onFileOpened(file) }

        val hover = runAnalysis { runtime.hover(request(file, content, "ArrayDeque")) }
        assertNotNull(hover)
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

  private class CheckpointCancelChecker(
      private val cancelAt: Int = Int.MAX_VALUE,
  ) : ICancelChecker {
    val checkpoints = AtomicInteger()
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
      cancelled.set(true)
    }

    override fun isCancelled(): Boolean = cancelled.get()

    override fun abortIfCancelled() {
      if (checkpoints.incrementAndGet() >= cancelAt) cancel()
      if (isCancelled()) throw CancellationException()
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

  private fun <T> runSuspend(action: suspend () -> T): T =
      runBlocking { action() }

  private fun fixturePath(relative: String): Path =
      FileProvider.kotlinAnalysisProjectRoot().resolve(relative).normalize()

  private fun analysisApiJar(): String =
      FileProvider.projectRoot()
          .resolve("docs/misc/kotlin-android/analysis-api-standalone-embeddable-for-ide-2.3.255-SNAPSHOT.jar")
          .toString()

  private fun createBackend(): KotlinAnalysisBackendConnection {
    prepareAnalysisPaths()
    return KotlinAnalysisBackendConnection(analysisApiJar())
  }

  private fun documentNotification(file: Path): JsonObject =
      JsonObject().apply {
        add(
            "textDocument",
            JsonObject().apply { addProperty("uri", file.normalize().toUri().toString()) },
        )
      }

  private fun prepareAnalysisPaths() {
    val home = File(BaseApplication.getBaseInstance()!!.filesDir, "kotlin-analysis-fixture")
    val config = File(home, "config")
    val system = File(home, "system")
    listOf(home, config, system).forEach { check(it.isDirectory || it.mkdirs()) }
    System.setProperty("idea.home.path", home.absolutePath)
    System.setProperty("idea.config.path", config.absolutePath)
    System.setProperty("idea.system.path", system.absolutePath)
  }

  private fun createRuntime(workspace: IWorkspace): KotlinStandaloneAnalysisRuntime {
    prepareAnalysisPaths()
    return KotlinStandaloneAnalysisRuntime(KotlinProjectClasspathProvider(workspace), analysisApiJar())
  }

  private fun diskRequest(file: Path, content: String, marker: String): KotlinSemanticRequest {
    val offset = content.indexOf(marker)
    require(offset >= 0) { "Fixture marker not found: $marker" }
    val position = LineIndex.from(content).indexToPosition(offset)
    return KotlinSemanticRequest(
        snapshot = ActiveDocumentSnapshot(file, -1, Instant.EPOCH, content, -1L),
        line = position.line,
        column = position.column,
        backendGeneration = 1L,
        cancelChecker = ICancelChecker.NOOP,
        prefix = marker,
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
