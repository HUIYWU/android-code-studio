/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.testing

import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.ChangeType
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.testing.tooling.ToolingApiTestLauncher
import com.tom.rv2ide.utils.FileProvider
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.robolectric.RuntimeEnvironment

/**
 * Establishes one real Tooling API project and workspace for an LSP integration-test callback.
 *
 * It deliberately owns only project/document lifecycle. Callers create their server inside [action]
 * and must close it there, so server-specific caches and EventBus registrations never outlive a test.
 */
object LspWorkspaceTestSupport {

  /** The single Java source owned by the minimal Tooling fixture. */
  @JvmStatic
  fun fixtureJavaFile(): Path =
      FileProvider.testProjectRoot()
          .resolve("app/src/main/java/com/tom/rv2ide/testing/fixture/DiagnosticFixture.java")
          .normalize()

  /**
   * Executes the invariant checks proven by the workspace smoke test.
   *
   * This stays in the support module so LSP test suites can reuse the real Tooling/workspace setup
   * without duplicating fixture-path, module-ownership, or FileManager ordering assumptions.
   */
  @JvmStatic
  fun verifyFixtureWorkspace() {
    val file = fixtureJavaFile()
    val contents = file.readText()

    withWorkspace {
      val module =
          requireNotNull(workspace.findModuleForFile(file, checkExistance = true)) {
            "Fixture Java file must belong to a Tooling-discovered module: $file"
          }
      check(module.path == ":app") { "Fixture Java file resolved to ${module.path}, expected :app" }
      check(module.hasBeenIndexed()) { "Fixture app module was not indexed during workspace setup" }

      openDocument(file, contents, version = 7, revision = 17L)
      val snapshot =
          requireNotNull(FileManager.getActiveDocumentSnapshot(file)) {
            "openDocument did not establish FileManager state for $file"
          }
      check(snapshot.version == 7) { "Unexpected fixture document version: ${snapshot.version}" }
      check(snapshot.revision == 17L) { "Unexpected fixture document revision: ${snapshot.revision}" }
      check(snapshot.content == contents) { "Fixture document content differs from FileManager snapshot" }
    }
  }

  @JvmStatic
  fun withWorkspace(
      projectDir: Path = FileProvider.testProjectRoot(),
      action: LspWorkspaceScope.() -> Unit,
  ) {
    prepareJvmApplication()
    ToolingApiTestLauncher.launch(projectDir) {
      val manager = IProjectManager.getInstance()
      manager.openProject(projectDir.toFile())
      runBlocking { manager.setupProject(requireProject()) }
      val workspace = manager.requireWorkspace()
      prepareCompilerEnvironment(workspace)
      val scope = LspWorkspaceScope(workspace)
      try {
        scope.action()
      } finally {
        scope.closeOpenDocuments()
        manager.destroy()
      }
    }
  }

  /**
   * Verifies the application selected by the Robolectric test configuration.
   *
   * The calling test must use @Config(application = BaseApplication::class). Robolectric then invokes
   * BaseApplication.onCreate(), which supplies the static instance and PreferenceManager required by
   * JavaPreferences. Under Robolectric VMUtils.isJvm() is true, so ToolsManager remains skipped.
   */
  private fun prepareJvmApplication() {
    check(RuntimeEnvironment.getApplication() is BaseApplication) {
      "LSP workspace tests require @Config(application = BaseApplication::class)"
    }
    check(BaseApplication.getBaseInstance() != null) {
      "Robolectric did not initialize BaseApplication"
    }
  }

  /**
   * Supplies JavaCompilerService's legacy JVM environment before that class is initialized.
   *
   * JavaCompilerService creates NO_MODULE_COMPILER statically and dereferences Environment.ANDROID_JAR
   * even though real Android modules later use their Tooling-provided boot classpath. Its module file
   * manager also requires Environment.JAVA_HOME. Reusing the discovered fixture android.jar and the
   * current test JVM home keeps this fixture aligned with the real compiler inputs.
   */
  private fun prepareCompilerEnvironment(workspace: IWorkspace) {
    val androidJar =
        workspace.androidProjects().flatMap { it.bootClassPaths.asSequence() }.firstOrNull {
          it.name == "android.jar" && it.isFile
        }
            ?: error("Tooling workspace did not provide an existing android.jar boot classpath")
    Environment.ANDROID_JAR = androidJar
    Environment.JAVA_HOME = File(System.getProperty("java.home") ?: error("Missing JVM java.home"))
  }
}

/** Test-scoped helpers that keep FileManager state synchronized before EventBus delivery. */
class LspWorkspaceScope internal constructor(val workspace: IWorkspace) {
  private val openFiles = linkedSetOf<Path>()

  fun openDocument(file: Path, text: String, version: Int = 1, revision: Long = 1L) {
    val event = DocumentOpenEvent(file, text, version, revision)
    FileManager.onDocumentOpen(event)
    openFiles.add(file.normalize())

    EventBus.getDefault().post(event)
  }

  fun changeDocument(
      file: Path,
      oldText: String,
      newText: String,
      version: Int,
      revision: Long,
      changeDelta: Int = newText.length - oldText.length,
  ) {
    val event =
        DocumentChangeEvent(
            file,
            oldText,
            newText,
            version,
            ChangeType.NEW_TEXT,
            changeDelta,
            Range.NONE,
            revision = revision,
        )
    // FileManager must lead asynchronous EventBus subscribers, matching IDEEditor production order.
    FileManager.onDocumentContentChange(event)
    EventBus.getDefault().post(event)
  }

  internal fun closeOpenDocuments() {
    openFiles.toList().forEach { file ->
      val event = DocumentCloseEvent(file)
      FileManager.onDocumentClose(event)
      EventBus.getDefault().post(event)
    }
    openFiles.clear()
  }
}