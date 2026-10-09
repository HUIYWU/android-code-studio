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
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze as kaAnalyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Builds and owns the standalone Kotlin Analysis API session used by [KotlinAnalysisBackendConnection].
 *
 * Analysis calls are executed under the standalone application's read action by the backend connection.
 */
internal class KotlinStandaloneAnalysisRuntime(
    private val classpathProvider: KotlinProjectClasspathProvider,
    private val intellijPluginRoot: String,
) : KotlinAnalysisRuntime {

  private val disposable = Disposer.newDisposable("acs-kotlin-analysis")
  private val closed = AtomicBoolean(false)
  private var session: StandaloneAnalysisAPISession? = null
  private var sourceModulesByProjectPath: Map<String, KaSourceModule> = emptyMap()
  private var projectContext: KotlinAnalysisProjectContext? = null

  @OptIn(KaExperimentalApi::class, KaImplementationDetail::class, K1Deprecation::class)
  override fun start(): Boolean {
    val context = KotlinAnalysisProjectContext.create(
        classpathProvider.workspace ?: return false,
        classpathProvider,
    )
    projectContext = context
    val sourceRoots = context.modules.flatMap { it.sourceRoots }
    if (sourceRoots.isEmpty()) {
      KlsLogs.warn("No Kotlin source roots found for the Kotlin Analysis API engine")
      disposeQuietly()
      return false
    }
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
      session = buildStandaloneAnalysisAPISession(disposable, true, configuration) {
        buildKtModuleProvider {
          this.platform = platform
          val contextsByPath = context.modules.associateBy { it.modulePath }
          val libraryModules = linkedMapOf<String, KaLibraryModule>()

          fun buildLibraryModule(moduleContext: KotlinAnalysisModuleContext): KaLibraryModule {
            libraryModules[moduleContext.modulePath]?.let { return it }
            val libraryModule = addModule(buildKtLibraryModule {
              libraryName = "acs-classpath-${moduleContext.modulePath}"
              this.platform = platform
              addBinaryRoots(
                  (moduleContext.binaryRoots + moduleContext.bootClasspaths).distinct()
              )
            })
            libraryModules[moduleContext.modulePath] = libraryModule
            return libraryModule
          }

          val builtModules = linkedMapOf<String, KaSourceModule>()
          val building = mutableSetOf<String>()

          fun buildSourceModule(moduleContext: KotlinAnalysisModuleContext): KaSourceModule {
            builtModules[moduleContext.modulePath]?.let { return it }
            if (!building.add(moduleContext.modulePath)) {
              KlsLogs.warn("Skipping cyclic Kotlin module dependency at ${moduleContext.modulePath}")
              return builtModules[moduleContext.modulePath]
                  ?: error("Kotlin module is being built before registration: ${moduleContext.modulePath}")
            }
            val dependencies = moduleContext.compileModulePaths
                .filter { it != moduleContext.modulePath && it !in building }
                .mapNotNull(contextsByPath::get)
                .map(::buildSourceModule)
            val sourceModule = addModule(buildKtSourceModule {
              moduleName = moduleContext.modulePath
              this.platform = platform
              addSourceRoots(moduleContext.sourceRoots)
              addRegularDependency(buildLibraryModule(moduleContext))
              dependencies.forEach(::addRegularDependency)
            })
            building.remove(moduleContext.modulePath)
            builtModules[moduleContext.modulePath] = sourceModule
            return sourceModule
          }

          context.modules.forEach(::buildLibraryModule)
          context.modules.forEach(::buildSourceModule)
          sourceModulesByProjectPath = builtModules.toMap()
        }
      }
      KlsLogs.info(
          "Kotlin Analysis API engine ready (modules={}, sourceRoots={})",
          context.modules.size,
          sourceRoots.size,
      )
      true
    } catch (t: Throwable) {
      KlsLogs.error("Failed to create the Kotlin Analysis API session", t)
      disposeQuietly()
      false
    }
  }

  override fun analyze(path: Path, snapshot: ActiveDocumentSnapshot?): DiagnosticResult? {
    val currentSession = session ?: return null

    return try {
      if (snapshot == null) {
        val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(path) ?: return null
        virtualFile.refresh(false, false)
        ApplicationManager.getApplication().runReadAction(
            Computable {
              val psiManager = PsiManager.getInstance(currentSession.project)
              val psiFile = psiManager.findFile(virtualFile)
              val ktFile = psiFile as? KtFile ?: return@Computable null
              val diagnostics = KotlinAnalysisDiagnostics.collectDiagnosticsFor(ktFile)
              DiagnosticResult(path, diagnostics, DiagnosticResult.CHANNEL_KOTLIN)
            },
        )
      } else {
        analyzeInMemoryText(currentSession.project, path, snapshot)
      }
    } catch (t: Throwable) {
      KlsLogs.warn("Kotlin Analysis API analysis failed for: {}", path, t)
      null
    }
  }

  private fun analyzeInMemoryText(
      project: Project,
      path: Path,
      snapshot: ActiveDocumentSnapshot,
  ): DiagnosticResult? = withSnapshotFile(project, path, snapshot) { file ->
    DiagnosticResult(
        path,
        KotlinAnalysisDiagnostics.collectDiagnosticsFor(file),
        DiagnosticResult.CHANNEL_KOTLIN,
        snapshot.version,
        snapshot.revision,
    )
  }

  override fun complete(request: KotlinSemanticRequest): CompletionResult? = null

  override fun hover(request: KotlinSemanticRequest): MarkupContent? =
      withSemanticSnapshot(request) { null }

  override fun findDefinition(request: KotlinSemanticRequest): DefinitionResult? =
      withSemanticSnapshot(request) { null }

  override fun findReferences(request: KotlinSemanticRequest): ReferenceResult? = null

  override fun signatureHelp(request: KotlinSemanticRequest): SignatureHelp? =
      withSemanticSnapshot(request) { null }

  private fun <T> withSemanticSnapshot(
      request: KotlinSemanticRequest,
      action: KaSession.(KtFile) -> T?,
  ): T? {
    request.cancelChecker.abortIfCancelled()
    val currentSession = session ?: return null
    return withSnapshotFile(currentSession.project, request.file, request.snapshot) { file ->
      request.cancelChecker.abortIfCancelled()
      kaAnalyze(file) {
        request.cancelChecker.abortIfCancelled()
        val result = action(file)
        request.cancelChecker.abortIfCancelled()
        result
      }
    }
  }

  @OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
  private fun <T> withSnapshotFile(
      project: Project,
      path: Path,
      snapshot: ActiveDocumentSnapshot,
      action: (KtFile) -> T?,
  ): T? = ApplicationManager.getApplication().runReadAction(
      Computable { action(createAnalysisFile(project, path, snapshot)) }
  )

  @OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
  private fun createAnalysisFile(
      project: Project,
      path: Path,
      snapshot: ActiveDocumentSnapshot,
  ): KtFile {
    val physicalFile = findPhysicalKtFile(project, path)
    val factory = if (physicalFile != null) {
      KtPsiFactory.contextual(physicalFile, markGenerated = true, eventSystemEnabled = false)
    } else {
      KtPsiFactory(project, markGenerated = true, eventSystemEnabled = false)
    }
    return factory.createFile(path.fileName.toString(), snapshot.content).apply {
      sourceModuleFor(path)?.let { contextModule = it }
    }
  }

  private fun findPhysicalKtFile(project: Project, path: Path): KtFile? {
    val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(path) ?: return null
    return PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
  }

  private fun sourceModuleFor(path: Path): KaSourceModule? {
    val context = projectContext ?: return sourceModulesByProjectPath.values.singleOrNull()
    val workspace = classpathProvider.workspace ?: return null
    val moduleContext = context.moduleFor(path, workspace)
        ?: return sourceModulesByProjectPath.values.singleOrNull()
    return sourceModulesByProjectPath[moduleContext.modulePath]
  }

  override fun close() {
    disposeQuietly()
  }

  private fun disposeQuietly() {
    if (!closed.compareAndSet(false, true)) {
      return
    }
    session = null
    sourceModulesByProjectPath = emptyMap()
    projectContext = null
    try {
      Disposer.dispose(disposable)
    } catch (t: Throwable) {
      KlsLogs.warn("Failed to dispose the Kotlin Analysis API session", t)
    }
  }
  private fun resolveJdkRelease(jdkHome: File): Int =
      Regex("java-(\\d+)").find(jdkHome.name)?.groupValues?.get(1)?.toIntOrNull() ?: 17

}
