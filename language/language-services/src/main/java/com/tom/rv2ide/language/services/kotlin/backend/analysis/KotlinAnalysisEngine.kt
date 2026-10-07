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
package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.javac.config.JavacConfigProvider
import com.tom.rv2ide.language.services.kotlin.compiler.KotlinClasspathProvider
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Builds and owns the standalone Kotlin Analysis API session used by [KotlinAnalysisLspConnection].
 *
 * TODO(ACS-KT-ANALYSIS-EXPERIMENT): the spike analyses without a project read/write lock; lock
 *  discipline is not implemented yet.
 */
internal class KotlinAnalysisEngine(
    private val classpathProvider: KotlinClasspathProvider,
    private val intellijPluginRoot: String,
) {

  private val disposable = Disposer.newDisposable("acs-kotlin-analysis")
  private val closed = AtomicBoolean(false)
  private var session: StandaloneAnalysisAPISession? = null
  private var sourceModulesByProjectPath: Map<String, KaSourceModule> = emptyMap()

  @OptIn(KaExperimentalApi::class, KaImplementationDetail::class, K1Deprecation::class)
  fun start(): Boolean {
    val moduleProjects = resolveModuleProjects()
    val sourceRoots = moduleProjects.flatMap { it.getSourceDirectories() }
        .filter(File::isDirectory)
        .map(File::toPath)
        .distinct()
    if (sourceRoots.isEmpty()) {
      KlsLogs.warn("No Kotlin source roots found for the Kotlin Analysis API engine")
      return false
    }
    val classpathRoots =
        classpathProvider
            .getClasspathList()
            .map { File(it) }
            .filter { it.exists() }
            .map { it.toPath() }

    Environment.JAVA_HOME?.let { jdkHome ->
      System.setProperty(JavacConfigProvider.PROP_ANDROIDIDE_JAVA_HOME, jdkHome.absolutePath)
    }

    val platform = JvmPlatforms.defaultJvmPlatform
    val configuration =
        CompilerConfiguration().apply {
          put(CLIConfigurationKeys.INTELLIJ_PLUGIN_ROOT, intellijPluginRoot)
          Environment.JAVA_HOME?.let { jdkHome ->
            put(JVMConfigurationKeys.JDK_HOME, jdkHome)
            put(JVMConfigurationKeys.JDK_RELEASE, resolveJdkRelease(jdkHome))
          }
        }

    return try {
      session =
          buildStandaloneAnalysisAPISession(disposable, true, configuration) {
            buildKtModuleProvider {
              this.platform = platform
              val libraryModule = buildKtLibraryModule {
                libraryName = "acs-classpath"
                this.platform = platform
                addBinaryRoots(classpathRoots)
              }
               addModule(libraryModule)
               val builtModules = linkedMapOf<String, KaSourceModule>()
               val building = mutableSetOf<String>()

               fun buildSourceModule(module: ModuleProject): KaSourceModule {
                 builtModules[module.path]?.let { return it }
                 if (!building.add(module.path)) {
                   KlsLogs.warn("Skipping cyclic Kotlin module dependency at ${module.path}")
                   return builtModules[module.path]
                       ?: error("Kotlin module is being built before registration: ${module.path}")
                 }
                 val dependencies = module.getCompileModuleProjects()
                     .filter { it.path != module.path && it.path !in building }
                     .distinctBy { it.path }
                     .map(::buildSourceModule)
                 val sourceModule = addModule(buildKtSourceModule {
                   moduleName = module.path
                   this.platform = platform
                   addSourceRoots(
                       module.getSourceDirectories()
                           .filter(File::isDirectory)
                           .map(File::toPath)
                   )
                   addRegularDependency(libraryModule)
                   dependencies.forEach(::addRegularDependency)
                 })
                 building.remove(module.path)
                 builtModules[module.path] = sourceModule
                 return sourceModule
               }

                moduleProjects.forEach(::buildSourceModule)
                sourceModulesByProjectPath = builtModules.toMap()
             }
           }
      KlsLogs.info(
          "Kotlin Analysis API engine ready (sourceRoots={}, classpathRoots={})",
          sourceRoots.size,
          classpathRoots.size,
      )
      true
    } catch (t: Throwable) {
      KlsLogs.error("Failed to create the Kotlin Analysis API session", t)
      disposeQuietly()
      false
    }
  }

  fun analyze(path: Path, snapshot: ActiveDocumentSnapshot? = null): DiagnosticResult? {
    val currentSession = session ?: return null

    if (snapshot != null) {
      return analyzeInMemoryText(currentSession.project, path, snapshot)
    }

    val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(path) ?: return null
    virtualFile.refresh(false, false)
    val psiManager = PsiManager.getInstance(currentSession.project)
    val psiFile = psiManager.findFile(virtualFile)
    val ktFile = psiFile as? KtFile ?: return null

    return try {
      val diagnostics = KotlinAnalysisDiagnostics.collectDiagnosticsFor(ktFile)
      DiagnosticResult(path, diagnostics, DiagnosticResult.CHANNEL_SERVER)
    } catch (t: Throwable) {
      KlsLogs.warn("Kotlin Analysis API disk analysis failed for: {}", path, t)
      null
    }
  }

  @OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
  private fun analyzeInMemoryText(
      project: Project,
      path: Path,
      snapshot: ActiveDocumentSnapshot,
  ): DiagnosticResult? {
    return try {
      val physicalFile = findPhysicalKtFile(project, path)
      val module = sourceModuleFor(path)
      val factory = if (physicalFile != null) {
        KtPsiFactory.contextual(physicalFile, markGenerated = true, eventSystemEnabled = false)
      } else {
        KtPsiFactory(project, markGenerated = true, eventSystemEnabled = false)
      }
      val ktFile = factory.createFile(path.fileName.toString(), snapshot.content)
      if (module != null) {
        ktFile.contextModule = module
      }
      val diagnostics = KotlinAnalysisDiagnostics.collectDiagnosticsFor(ktFile)
      DiagnosticResult(
          path,
          diagnostics,
          DiagnosticResult.CHANNEL_SERVER,
          snapshot.version,
          snapshot.revision,
      )
    } catch (t: Throwable) {
      KlsLogs.warn("Kotlin Analysis API in-memory analysis failed for: {}", path, t)
      null
    }
  }

  private fun findPhysicalKtFile(project: Project, path: Path): KtFile? {
    val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(path) ?: return null
    return PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
  }

  private fun sourceModuleFor(path: Path): KaSourceModule? {
    val moduleProject = IProjectManager.getInstance().getWorkspace()?.findModuleForFile(path, false)
        ?: return sourceModulesByProjectPath.values.singleOrNull()
    return sourceModulesByProjectPath[moduleProject.path]
  }

  fun close() {
    disposeQuietly()
  }

  private fun disposeQuietly() {
    if (!closed.compareAndSet(false, true)) {
      return
    }
    session = null
    sourceModulesByProjectPath = emptyMap()
    try {
      Disposer.dispose(disposable)
    } catch (t: Throwable) {
      KlsLogs.warn("Failed to dispose the Kotlin Analysis API session", t)
    }
  }
  private fun resolveModuleProjects(): List<ModuleProject> {
    val workspace = IProjectManager.getInstance().getWorkspace() ?: return emptyList()
    val directModules = buildList {
      add(workspace.getRootProject())
      addAll(workspace.getSubProjects())
    }.filterIsInstance<ModuleProject>()

    val modules = linkedMapOf<String, ModuleProject>()
    val visiting = mutableSetOf<String>()

    fun collect(module: ModuleProject) {
      if (modules.containsKey(module.path) || !visiting.add(module.path)) {
        return
      }
      modules[module.path] = module
      module.getCompileModuleProjects().forEach(::collect)
      visiting.remove(module.path)
    }

    directModules.forEach(::collect)
    return modules.values.toList()
  }


  private fun resolveJdkRelease(jdkHome: File): Int =
      Regex("java-(\\d+)").find(jdkHome.name)?.groupValues?.get(1)?.toIntOrNull() ?: 17

  companion object {
    private const val ENGINE_PROBE_CLASS =
        "org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilder"

    fun isEngineBundled(): Boolean =
        try {
          Class.forName(ENGINE_PROBE_CLASS)
          true
        } catch (_: Throwable) {
          false
        }
  }
}
