package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.analysisBestEffort
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.ownerModulePathOf
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.KotlinSemanticTargetResolution
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.ReferenceScanObserver
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.buildReferencePlan
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.collectReferences
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.completionItemsFor
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.definitionTargetPsi
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.locationForPsi
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.resolveOrdinaryTargetAt
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.SignatureCallCandidate
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.CompletionTarget
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.computeActiveParameter
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.documentationText
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.extensionFunctionValueSignature
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.parameterLabel
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.keywordCompletionItems
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.packageCompletionItems
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.receiverCompletionItems
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.receiverTypeShortName
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.resolveCompletionTarget
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.resolveSignatureCallCandidates
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.resolveSingleSignatureCall
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.signatureArgumentIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.signatureDisplayName
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.snippetCompletionItems
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.unimportedCompletionItems
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.unimportedExtensionCompletionItems
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.unimportedShadowingKind
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.projects.models.OneHopDocumentEdit
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.lsp.models.ParameterInformation
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureInformation
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.analysis.api.KaContextParameterApi
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaNonPublicApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNonPublicApi
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.types.Variance
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(KaContextParameterApi::class, KaExperimentalApi::class, KaImplementationDetail::class, KaNonPublicApi::class, KtNonPublicApi::class, LLFirInternals::class)
internal class KotlinStandaloneAnalysisRuntime(
    private val classpathProvider: KotlinProjectClasspathProvider,
    private val intellijPluginRoot: String,
    private val referenceObserver: ReferenceScanObserver = ReferenceScanObserver.NONE,
    private val freshEnvironment: Boolean = false,
) : KotlinAnalysisRuntime {
  private val closed = AtomicBoolean(false)
  private var environment: LiveAnalysisEnvironment? = null
  private var lease: SharedAnalysisWorkspaceOwner.Lease? = null
  private var scheduleIndexing: ((Path, () -> Unit) -> Unit)? = null

  internal var startFailure: Throwable? = null
    private set

  internal var startFailureReason: String? = null
    private set

  override fun configureIndexingScheduler(schedule: (Path, () -> Unit) -> Unit) {
    scheduleIndexing = schedule
    environment?.configureRefreshScheduler(schedule)
  }

  override fun start(): Boolean {
    if (closed.get()) {
      startFailureReason = "Kotlin Analysis runtime is already closed"
      return false
    }
    startFailure = null
    startFailureReason = null
    val acquired =
        SharedAnalysisWorkspaceOwner.acquire(classpathProvider, intellijPluginRoot, freshEnvironment)
    val created = acquired.environment
    if (created == null) {
      startFailure = acquired.failure
      startFailureReason =
          acquired.failureReason ?: "Kotlin Analysis environment is unavailable"
      acquired.close()
      return false
    }
    lease = acquired
    scheduleIndexing?.let { schedule -> created.configureRefreshScheduler(schedule) }
    environment = created
    return true
  }

  override fun onFileOpened(path: Path) {
    val env = environment ?: return
    if (path.toString().endsWith(".java")) {
      env.javaFiles.refresh(path)
      env.javaSourceIndex.refreshFile(path)
    } else {
      env.liveIndex.refreshCurrentKtFile(path)
    }
  }

  override fun onFileContentChanged(path: Path, edit: OneHopDocumentEdit?) {
    val env = environment ?: return
    if (path.toString().endsWith(".java")) {
      env.javaFiles.refresh(path)
      env.javaSourceIndex.refreshFile(path)
    } else {
      env.liveIndex.applyDocumentEdit(path, edit)
    }
  }

  override fun onFileClosed(path: Path) {
    val env = environment ?: return
    if (path.toString().endsWith(".java")) {
      env.javaFiles.refresh(path)
      env.javaSourceIndex.refreshFile(path)
    } else {
      env.liveIndex.closeCurrent(path)
    }
  }

  override fun onFileCreated(path: Path) {
    val env = environment ?: return
    env.invalidateProjectFiles(path)
    if (path.toString().endsWith(".java")) {
      env.javaFiles.refresh(path)
      // findFileByNioPath does not refresh the VFS, so a file created after the runtime started
      // must be inserted here or the PSI-based Java reference scan cannot read it.
      VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)
      env.javaSourceIndex.invalidate()
    } else {
      env.liveIndex.scheduleRefreshCurrentKtFile(path)
    }
  }

  override fun onFileSaved(path: Path) {
    val env = environment ?: return
    if (path.toString().endsWith(".java")) env.javaSourceIndex.refreshFile(path)
  }

  override fun onFileRemoved(path: Path) {
    val env = environment ?: return
    env.invalidateProjectFiles(path)
    env.javaFiles.removeUnder(path)
    // The removed path can be a directory holding Java sources, so the path set is invalidated
    // unconditionally. A Kotlin-only removal only costs one rebuild of the Java source tables.
    env.javaSourceIndex.invalidate()
    env.liveIndex.removeFromSourceIndexUnder(path)
  }

  override fun onFileMoved(fromPath: Path, toPath: Path) {
    val env = environment ?: return
    env.invalidateProjectFiles(fromPath, toPath)
    env.javaFiles.removeUnder(fromPath)
    env.javaSourceIndex.invalidate()
    // The destination is a new VFS entry for the same reason as in onFileCreated.
    VirtualFileManager.getInstance().refreshAndFindFileByNioPath(toPath)
    env.liveIndex.moveSourceIndex(fromPath, toPath)
    com.tom.rv2ide.projects.FileManager.getActiveDocumentFiles()
        .filter { it == toPath || it.startsWith(toPath) }
        .forEach(env.javaFiles::refresh)
  }

  override fun analyze(
      path: Path,
      snapshot: com.tom.rv2ide.projects.models.ActiveDocumentSnapshot?,
      cancelChecker: ICancelChecker,
  ): DiagnosticResult? {
    val env = environment ?: return null
    val normalized = path.normalize()
    return env.liveIndex.withLiveKtFile(normalized, cancelChecker, action = { live ->
      cancelChecker.abortIfCancelled()
      if (live.isStale) return@withLiveKtFile null
      val diagnostics = live.analyzing(cancelChecker) { file ->
          KotlinAnalysisDiagnostics.collectDiagnosticsFor(this, file, env.diagnosticFacadeService)
        }
        DiagnosticResult(
           normalized,
           diagnostics,
           DiagnosticResult.CHANNEL_KOTLIN,
           snapshot?.version ?: DiagnosticResult.UNKNOWN_DOCUMENT_VERSION,
           snapshot?.revision ?: DiagnosticResult.UNKNOWN_DOCUMENT_REVISION,
       )
    })
  }

  override fun complete(request: KotlinSemanticRequest): CompletionResult? {
    return withSemanticLiveFile(request) { file ->
        val offset = requestOffset(request)
        // Keep the raw leaf for target resolution; end-of-line whitespace is handled there.
        val caretElement = elementAt(file, offset) ?: return@withSemanticLiveFile CompletionResult.EMPTY
        val element = caretElement as? org.jetbrains.kotlin.psi.KtElement
            ?: caretElement.parent as? org.jetbrains.kotlin.psi.KtElement
            ?: return@withSemanticLiveFile CompletionResult.EMPTY
        val clientPrefix = request.prefix ?: extractCompletionPrefix(file.text, offset)
        when (val target = resolveCompletionTarget(offset, caretElement)) {
          is CompletionTarget.Receiver -> {
            // Client prefix includes '.', so member completion filters by the text after the dot.
            val memberPrefix = clientPrefix.substringAfterLast('.')
            CompletionResult(
                analysisBestEffort(emptyList(), request.cancelChecker) {
                  val visible = receiverCompletionItems(memberPrefix, target, request.cancelChecker)
                  val receiverTypeName = receiverTypeShortName(target.receiver, target.safe)
                  val unimported =
                      if (receiverTypeName == null) {
                        emptyList()
                      } else {
                        unimportedExtensionCompletionItems(
                            memberPrefix,
                            receiverTypeName,
                            file,
                            environment?.metadataIndex,
                            request.cancelChecker,
                        )
                      }
                  if (unimported.isEmpty()) {
                    visible
                  } else {
                    visible + unimported.filterNot { candidate ->
                      visible.any {
                        it.ideLabel == candidate.ideLabel && it.completionKind == candidate.completionKind
                      }
                    }
                  }
                }
            )
          }
          CompletionTarget.None -> CompletionResult.EMPTY
          CompletionTarget.PackageOrImport -> {
            val metadataIndex = environment?.metadataIndex
            val modulePath = metadataIndex?.ownerModulePathOf(file)
            val binarySources = environment?.binaryRootsFor(modulePath).orEmpty()
            CompletionResult(
                packageCompletionItems(
                    clientPrefix,
                    metadataIndex,
                    modulePath,
                    environment?.binaryClassIndex,
                    binarySources,
                    environment?.javaSourceIndex,
                    request.cancelChecker,
                )
            )
          }
          else -> {
            val classCallSnippet = target !is CompletionTarget.TypePosition &&
                target !is CompletionTarget.PackageOrImport
            val items = completionItemsFor(clientPrefix, element, classCallSnippet, request.cancelChecker)
            val metadataIndex = environment?.metadataIndex
            val binarySources =
                environment?.binaryRootsFor(metadataIndex?.ownerModulePathOf(file)).orEmpty()
            val unimported = unimportedCompletionItems(
                clientPrefix,
                file,
                metadataIndex,
                classCallSnippet,
                environment?.binaryClassIndex,
                binarySources,
                environment?.javaSourceIndex,
                request.cancelChecker,
            )
            val symbolItems =
                if (unimported.isEmpty()) {
                  items
                } else {
                  request.cancelChecker.abortIfCancelled()
                  val visibleKeys = items.mapTo(HashSet()) {
                    it.ideLabel to it.completionKind.unimportedShadowingKind()
                  }
                  items + unimported.filterNot { candidate ->
                    request.cancelChecker.abortIfCancelled()
                    (candidate.ideLabel to candidate.completionKind.unimportedShadowingKind()) in visibleKeys
                  }
                }
            val snippetItems = snippetCompletionItems(file, offset, element)
            val symbolAndSnippets =
                if (snippetItems.isEmpty()) {
                  symbolItems
                } else {
                  request.cancelChecker.abortIfCancelled()
                  symbolItems + snippetItems.filterNot { snippet ->
                    symbolItems.any {
                      it.ideLabel == snippet.ideLabel && it.completionKind == snippet.completionKind
                    }
                  }
                }
            val snippetLabels =
                if (snippetItems.isEmpty()) emptySet()
                else snippetItems.mapTo(HashSet()) { it.ideLabel }
            val keywordItems = keywordCompletionItems(clientPrefix, file, offset, element, request.cancelChecker)
            val merged =
                if (keywordItems.isNullOrEmpty()) {
                  symbolAndSnippets
                } else {
                  symbolAndSnippets + keywordItems.filterNot { keyword ->
                    (clientPrefix.isNotEmpty() && keyword.ideLabel in snippetLabels) ||
                        symbolItems.any {
                          it.ideLabel == keyword.ideLabel && it.completionKind == keyword.completionKind
                        }
                  }
                }
            CompletionResult(merged)
          }
        }
      }
  }

  override fun hover(request: KotlinSemanticRequest): MarkupContent? =
      withSemanticLiveFile(request) { file ->
        val symbol = (resolveOrdinaryTargetAt(file, requestOffset(request)) as? KotlinSemanticTargetResolution.Unique)?.symbol as? KaDeclarationSymbol
            ?: return@withSemanticLiveFile null
          val sourceDeclaration = analysisBestEffort(null, request.cancelChecker) {
            environment?.attachedSourceDeclaration(this, symbol)
          }
          val declaration = symbol.render(org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource.WITH_SHORT_NAMES).trim()
          val documentation = analysisBestEffort("", request.cancelChecker) {
            documentationText(symbol, sourceDeclaration)
          }
         if (declaration.isEmpty()) null else MarkupContent(
             buildString {
               append("```kotlin\n")
               append(declaration)
               append("\n```")
               if (documentation.isNotEmpty()) {
                 append("\n\n---\n\n")
                 append(documentation)
               }
             },
             MarkupKind.MARKDOWN,
         )
      }

  override fun findDefinition(request: KotlinSemanticRequest): DefinitionResult? =
      withSemanticLiveFile(request) { file ->
val symbol = when (val resolution = resolveOrdinaryTargetAt(file, requestOffset(request))) {
          is KotlinSemanticTargetResolution.Unique -> resolution.symbol
          else -> return@withSemanticLiveFile DefinitionResult(emptyList())
        }
        val declaration = symbol as? KaDeclarationSymbol
        val psi = analysisBestEffort(null, request.cancelChecker) {
          declaration?.let { environment?.attachedSourceDeclaration(this, it) }
              ?: declaration?.psi?.navigationElement
              ?: declaration?.containingDeclaration?.psi?.navigationElement
        } ?: return@withSemanticLiveFile DefinitionResult(emptyList())
         val target = definitionTargetPsi(psi)
        val location = locationForPsi(request.file, file, target)
        DefinitionResult(if (location == null) emptyList() else listOf(location))
      }

  override fun findReferences(request: KotlinSemanticRequest): ReferenceResult? {
    val env = environment ?: return null
    val plan =
        withSemanticLiveFile(request) { file ->
          buildReferencePlan(
              requestPath = request.file,
              file = file,
              offset = requestOffset(request),
              includeDeclaration = request.includeDeclaration,
              requestVersion = request.snapshot.version,
              requestRevision = request.snapshot.revision,
              environment = env,
          )
        } ?: return ReferenceResult(emptyList())
    return collectReferences(plan, env, request.cancelChecker, referenceObserver)
  }

  override fun signatureHelp(request: KotlinSemanticRequest): SignatureHelp? =
      withSemanticLiveFile(request) { file ->
        val offset = requestOffset(request)
        val callElement = callElementAt(file, offset) ?: return@withSemanticLiveFile SignatureHelp(emptyList(), 0, 0)
        val extensionValueSignature = analysisBestEffort(null, request.cancelChecker) {
          extensionFunctionValueSignature(file, callElement, offset)
        }
        if (extensionValueSignature != null) return@withSemanticLiveFile extensionValueSignature
        val candidates = analysisBestEffort(emptyList(), request.cancelChecker) {
          resolveSignatureCallCandidates(callElement)
        }
        val resolved = if (candidates.isNotEmpty()) {
          candidates
        } else {
          analysisBestEffort(emptyList(), request.cancelChecker) {
            resolveSingleSignatureCall(callElement)?.let { listOf(SignatureCallCandidate(it, true)) } ?: emptyList()
          }
        }
        val argumentIndex = signatureArgumentIndex(callElement, offset)
        val entries = analysisBestEffort(emptyList(), request.cancelChecker) {
          resolved.map { candidate ->
            val signature = candidate.call.signature
            val parameters = signature.valueParameters
            val labels = parameters.map { parameterLabel(it) }
            SignatureEntry(
                label = "${signatureDisplayName(callElement, candidate.call)}(${labels.joinToString(", ")})",
                parameters = labels.map { ParameterInformation(it, MarkupContent()) },
                call = candidate.call,
                isInBestCandidates = candidate.isInBestCandidates,
                parameterCount = parameters.size,
                acceptsExtraArguments = parameters.lastOrNull()?.symbol?.isVararg == true,
            )
          }
        }
        if (entries.isEmpty()) return@withSemanticLiveFile SignatureHelp(emptyList(), 0, 0)
        val bestIndices = entries.indices.filter { entries[it].isInBestCandidates }
        val activeSignature =
            bestIndices.firstOrNull { entries[it].acceptsArgument(argumentIndex) }
                ?: entries.indices.firstOrNull { entries[it].acceptsArgument(argumentIndex) }
                ?: bestIndices.firstOrNull()
                ?: 0
        val activeParameter = computeActiveParameter(callElement, entries[activeSignature].call, offset, argumentIndex)
        SignatureHelp(
            entries.map { SignatureInformation(it.label, MarkupContent(), it.parameters) },
            activeSignature,
            activeParameter,
        )
      }

  private data class SignatureEntry(
      val label: String,
      val parameters: List<ParameterInformation>,
      val call: KaFunctionCall<*>,
      val isInBestCandidates: Boolean,
      val parameterCount: Int,
      val acceptsExtraArguments: Boolean,
  ) {
    fun acceptsArgument(index: Int): Boolean =
        index < parameterCount || (acceptsExtraArguments && parameterCount > 0)
  }

  private fun <T> withSemanticLiveFile(
      request: KotlinSemanticRequest,
      action: KaSession.(KtFile) -> T?,
  ): T? {
    request.cancelChecker.abortIfCancelled()
    val env = environment ?: return null
    return env.liveIndex.withLiveKtFile(request.file, request.cancelChecker) { live ->
      if (live.isStale) return@withLiveKtFile null
      live.analyzing(request.cancelChecker) {
        request.cancelChecker.abortIfCancelled()
        action(this, it)
      }
    }
  }

  private fun requestOffset(request: KotlinSemanticRequest): Int =
      LineIndex.from(request.snapshot.content).lineColumnToIndex(request.line, request.column)
private fun extractCompletionPrefix(text: String, offset: Int): String {
    val end = offset.coerceIn(0, text.length)
    var start = end
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
    return text.substring(start, end)
  }

  private fun elementAt(file: KtFile, offset: Int): PsiElement? {
    val safe = offset.coerceIn(0, file.textLength)
    return file.findElementAt(safe) ?: if (safe > 0) file.findElementAt(safe - 1) else null
  }

  private fun callElementAt(file: KtFile, offset: Int): KtCallElement? {
    var element: PsiElement? = elementAt(file, offset)
    while (element != null && element !is PsiFile) {
      if (element is KtFunctionLiteral) return null
      if (element is KtCallElement) {
        val callee = element.calleeExpression
        if (callee != null && offset >= callee.textRange.startOffset) return element
      }
      element = element.parent
    }
    return null
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) {
      return
    }
    lease?.close()
    lease = null
    environment = null
  }
}
