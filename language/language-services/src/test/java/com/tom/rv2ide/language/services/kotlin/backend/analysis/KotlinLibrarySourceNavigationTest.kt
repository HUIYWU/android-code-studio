package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.request
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.LineIndex
import java.nio.file.Files
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinLibrarySourceNavigationTest {

  @Test
  fun matchedExternalLibrarySourceNavigationAndKDoc() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val content = file.readText()
      val appModule = workspace.androidProjects().single { it.path == ":app" }
      val binary = appModule.getCompileClasspaths().single { it.name == "kotlin-stdlib-2.3.21.jar" }.canonicalFile
      val library = appModule.libraryMap.values.single { it.artifact?.canonicalFile == binary }
      val sourceArchive = fixturePath("app/libs/kotlin-stdlib-2.3.21-sources.jar").toFile().canonicalFile
      val originalSource = library.srcJar
      val originalSources = library.srcJars
      val originalJavadoc = library.docJar
      try {
        assertTrue(sourceArchive.isFile)
        library.srcJar = null
        library.srcJars = listOf(sourceArchive)
        library.docJar = null
        openDocument(file, content, version = 5, revision = 5L)

        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val hover = requireNotNull(runAnalysis { runtime.hover(request(file, content, "ArrayDeque")) })
          assertTrue(hover.value.contains("Resizable-array implementation of the deque data structure."))

          val definition = requireNotNull(runAnalysis { runtime.findDefinition(request(file, content, "ArrayDeque")) })
          val location = definition.locations.single()
          val normalizedPath = location.file.toString().replace('\\', '/')
          assertTrue(normalizedPath.endsWith("commonMain/kotlin/collections/ArrayDeque.kt"))
          assertTrue(Files.isRegularFile(location.file))
          val sourceContent = location.file.readText()
          val classStart = sourceContent.indexOf("class ArrayDeque")
          assertTrue(classStart >= 0)
          val nameStart = classStart + "class ".length
          assertEquals(
              LineIndex.from(sourceContent).indexToPosition(nameStart),
              location.range.start,
          )
          assertEquals(
              LineIndex.from(sourceContent).indexToPosition(nameStart + "ArrayDeque".length),
              location.range.end,
          )
        }
      } finally {
        library.srcJar = originalSource
        library.srcJars = originalSources
        library.docJar = originalJavadoc
      }
    }
  }

  @Test
  fun unmatchedExternalLibrarySourceDoesNotExposeArchiveDefinition() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt")
      val content = file.readText()
      val appModule = workspace.androidProjects().single { it.path == ":app" }
      val binary = appModule.getCompileClasspaths().single { it.name == "kotlin-stdlib-2.3.21.jar" }.canonicalFile
      val library = appModule.libraryMap.values.single { it.artifact?.canonicalFile == binary }
      val originalSource = library.srcJar
      val originalSources = library.srcJars
      val originalJavadoc = library.docJar
      try {
        library.srcJar = null
        library.srcJars = emptyList()
        library.docJar = null
        openDocument(file, content, version = 6, revision = 6L)

        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val definition = requireNotNull(runAnalysis { runtime.findDefinition(request(file, content, "ArrayDeque")) })
          assertTrue(definition.locations.isEmpty())
        }
      } finally {
        library.srcJar = originalSource
        library.srcJars = originalSources
        library.docJar = originalJavadoc
      }
    }
  }
}