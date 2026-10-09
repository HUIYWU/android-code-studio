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
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.lsp.models.ParameterInformation
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureInformation
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analysis.api.KaContextParameterApi
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze as kaAnalyze
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbol
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.calls
import org.jetbrains.kotlin.analysis.api.signatures.KaFunctionSignature
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.types.Variance

/**
 * Builds and owns the standalone Kotlin Analysis API session used by [KotlinAnalysisBackendConnection].
 *
 * Analysis calls are executed under the standalone application's read action by the backend connection.
 */
@OptIn(KaContextParameterApi::class, KaExperimentalApi::class, KaImplementationDetail::class, KaPlatformInterface::class, K1Deprecation::class)
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
              val diagnostics = KotlinAnalysisDiagnostics.collectDiagnosticsFor(
                   ktFile,
                   "${path.normalize()} version=disk revision=disk",
               )
              DiagnosticResult(path, diagnostics, DiagnosticResult.CHANNEL_KOTLIN)
            },
        )
      } else {
        analyzeInMemoryText(currentSession.project, path, snapshot)
      }
    } catch (t: Throwable) {
      KlsLogs.warn("Kotlin Analysis API analysis failed for: $path", t)
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
        KotlinAnalysisDiagnostics.collectDiagnosticsFor(
             file,
             "${path.normalize()} version=${snapshot.version} revision=${snapshot.revision}",
         ),
        DiagnosticResult.CHANNEL_KOTLIN,
        snapshot.version,
        snapshot.revision,
    )
  }

  override fun complete(request: KotlinSemanticRequest): CompletionResult? = null

  override fun hover(request: KotlinSemanticRequest): MarkupContent? =
      withSemanticSnapshot(request) { file ->
        val symbol =
            symbolAt(file, requestOffset(request)) as? KaDeclarationSymbol
                ?: return@withSemanticSnapshot null
        val declaration = symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES).trim()
        if (declaration.isEmpty()) {
          null
        } else {
          MarkupContent("```kotlin\n$declaration\n```", MarkupKind.MARKDOWN)
        }
      }

  override fun findDefinition(request: KotlinSemanticRequest): DefinitionResult? =
      withSemanticSnapshot(request) { file ->
        val symbol = symbolAt(file, requestOffset(request)) ?: return@withSemanticSnapshot DefinitionResult(emptyList())
        val psi = runCatching { symbol.psi?.navigationElement }.getOrNull()
            ?: return@withSemanticSnapshot DefinitionResult(emptyList())
        val locationPsi = definitionTargetPsi(psi)
        val location = locationForPsi(request, file, locationPsi)
        DefinitionResult(if (location == null) emptyList() else listOf(location))
      }

  override fun findReferences(request: KotlinSemanticRequest): ReferenceResult? = null

  override fun signatureHelp(request: KotlinSemanticRequest): SignatureHelp? =
      withSemanticSnapshot(request) { file ->
        val offset = requestOffset(request)
        val callElement = callElementAt(file, offset)
            ?: return@withSemanticSnapshot SignatureHelp(emptyList(), 0, 0)
        val callInfo = runCatching { callElement.resolveToCall() }.getOrNull()
            ?: return@withSemanticSnapshot SignatureHelp(emptyList(), 0, 0)
        val functionCalls = callInfo.calls.filterIsInstance<KaFunctionCall<*>>()
        val functionName = callElement.calleeExpression?.text?.takeIf { it.isNotBlank() } ?: "invoke"
        val signatureEntries = functionCalls.mapNotNull { call ->
          val signature = call.signature as? KaFunctionSignature<*> ?: return@mapNotNull null
          val parameters = signature.valueParameters.map { parameter ->
            val name = parameter.name.asString()
            val type = parameter.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
            ParameterInformation(
                label = if (name.isEmpty()) type else "$name: $type",
                documentation = MarkupContent(),
            )
          }
          if (parameters.isEmpty()) return@mapNotNull null
          val information = SignatureInformation(
              label = "$functionName(${parameters.joinToString(", ") { it.label }})",
              documentation = MarkupContent(),
              parameters = parameters,
          )
          information to activeParameter(call, callElement, offset, signature)
              .coerceIn(0, parameters.lastIndex)
        }
        if (signatureEntries.isEmpty()) {
          return@withSemanticSnapshot SignatureHelp(emptyList(), 0, 0)
        }
        val signatures = signatureEntries.map { it.first }
        val activeParameter = signatureEntries.firstOrNull()?.second ?: 0
        SignatureHelp(signatures, 0, activeParameter)
      }

  private fun requestOffset(request: KotlinSemanticRequest): Int =
      LineIndex.from(request.snapshot.content).lineColumnToIndex(request.line, request.column)

  private fun KaSession.symbolAt(file: KtFile, offset: Int): KaSymbol? {
    var element: PsiElement? = elementAt(file, offset)
    while (element != null && element !is PsiFile) {
      val reference = when (element) {
        is KtSimpleNameExpression -> element.reference as? KtReference
        is KtReferenceExpression -> element.reference as? KtReference
        else -> null
      }
      if (reference != null) {
        runCatching { reference.resolveToSymbol() }.getOrNull()?.let { return it }
      }
      element = element.parent
    }
    return null
  }

  private fun definitionTargetPsi(psi: PsiElement): PsiElement {
    if (psi is KtConstructor<*>) {
      var parent: PsiElement? = psi.parent
      while (parent != null && parent !is KtClassOrObject) {
        parent = parent.parent
      }
      if (parent is KtClassOrObject) {
        return parent.getNameIdentifier() ?: psi
      }
    }
    if (psi is KtNamedDeclaration) {
      return psi.getNameIdentifier() ?: psi
    }
    return psi
  }

  private fun elementAt(file: KtFile, offset: Int): PsiElement? {
    val safeOffset = offset.coerceIn(0, file.textLength)
    return file.findElementAt(safeOffset)
        ?: if (safeOffset > 0) file.findElementAt(safeOffset - 1) else null
  }

  private fun callElementAt(file: KtFile, offset: Int): KtCallElement? {
    var element: PsiElement? = elementAt(file, offset)
    while (element != null && element !is PsiFile) {
      if (element is KtCallElement) return element
      element = element.parent
    }
    return null
  }

  private fun activeParameter(
      call: KaFunctionCall<*>,
      callElement: KtCallElement,
      offset: Int,
      signature: KaFunctionSignature<*>,
  ): Int {
    val parameters = signature.valueParameters
    val arguments = callElement.valueArguments.mapNotNull { it as? KtValueArgument }
    val argument = arguments.firstOrNull { valueArgument ->
      val range = valueArgument.textRange
      range.containsOffset(offset) ||
          (offset == range.endOffset &&
              valueArgument.getArgumentExpression()?.textRange?.containsOffset(offset) == true)
    }
    val mapped = argument?.getArgumentExpression()?.let { call.argumentMapping[it] }
    if (mapped != null) {
      val mappedIndex = parameters.indexOfFirst { it.name == mapped.name }
      if (mappedIndex >= 0) return mappedIndex
    }
    val precedingArguments = arguments.count { it.textRange.startOffset < offset }
    return precedingArguments.coerceIn(0, parameters.lastIndex)
  }

  private fun locationForPsi(request: KotlinSemanticRequest, sourceFile: KtFile, psi: PsiElement): Location? {
    val containingFile = runCatching { psi.containingFile }.getOrNull() ?: return null
    val targetPath = when {
      containingFile == sourceFile -> request.file
      else -> containingFile.virtualFile?.path?.let(Paths::get) ?: return null
    }
    val range = runCatching { psi.textRange }.getOrNull() ?: return null
    val text = runCatching { containingFile.text }.getOrNull() ?: return null
    val lineIndex = LineIndex.from(text)
    val startOffset = range.startOffset.coerceIn(0, text.length)
    val endOffset = range.endOffset.coerceIn(startOffset, text.length)
    return Location(
        file = targetPath,
        range = Range(lineIndex.indexToPosition(startOffset), lineIndex.indexToPosition(endOffset)),
    )
  }

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
