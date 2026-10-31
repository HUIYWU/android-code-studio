package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.analysisBestEffort
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.KotlinSemanticTargetResolution
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.completionItemsFor
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.definitionTargetPsi
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.locationForElement
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.locationForPsi
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.resolveOrdinaryTargetAt
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.SignatureCallCandidate
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.CompletionTarget
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.computeActiveParameter
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.documentationText
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.parameterLabel
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.receiverCompletionItems
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.resolveCompletionTarget
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.resolveSignatureCallCandidates
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.resolveSingleSignatureCall
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.signatureArgumentIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.signatureDisplayName
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
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
import com.tom.rv2ide.lsp.models.ReferenceRole
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureInformation
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.analysis.api.KaContextParameterApi
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaNonPublicApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNonPublicApi
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.types.Variance
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(KaContextParameterApi::class, KaExperimentalApi::class, KaImplementationDetail::class, KaNonPublicApi::class, KtNonPublicApi::class)
internal class KotlinStandaloneAnalysisRuntime(
    private val classpathProvider: KotlinProjectClasspathProvider,
    private val intellijPluginRoot: String,
) : KotlinAnalysisRuntime {
  private val closed = AtomicBoolean(false)
  private var environment: LiveAnalysisEnvironment? = null
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
    val created = LiveAnalysisEnvironment(classpathProvider, intellijPluginRoot)
    if (!created.start()) {
      startFailure = created.startFailure
      startFailureReason = created.startFailureReason
      created.close()
      return false
    }
    scheduleIndexing?.let { schedule -> created.configureRefreshScheduler(schedule) }
    environment = created
    return true
  }

  override fun onFileOpened(path: Path) {
    environment?.liveIndex?.refreshCurrentKtFile(path)
  }

  override fun onFileContentChanged(path: Path, edit: OneHopDocumentEdit?) {
    environment?.liveIndex?.applyDocumentEdit(path, edit)
  }

  override fun onFileClosed(path: Path) {
    environment?.liveIndex?.closeCurrent(path)
  }

  override fun onFileCreated(path: Path) {
    environment?.liveIndex?.scheduleRefreshCurrentKtFile(path)
  }

  override fun onFileRemoved(path: Path) {
    environment?.liveIndex?.removeFromSourceIndexUnder(path)
  }

  override fun onFileMoved(fromPath: Path, toPath: Path) {
    environment?.liveIndex?.moveSourceIndex(fromPath, toPath)
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
          KotlinAnalysisDiagnostics.collectDiagnosticsFor(this, file)
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
        val caretElement = elementAt(file, offset) ?: return@withSemanticLiveFile CompletionResult.EMPTY
        val element = caretElement as? org.jetbrains.kotlin.psi.KtElement
            ?: caretElement.parent as? org.jetbrains.kotlin.psi.KtElement
            ?: return@withSemanticLiveFile CompletionResult.EMPTY
        val prefix = request.prefix ?: extractCompletionPrefix(file.text, offset)
        when (val target = resolveCompletionTarget(offset, caretElement)) {
          is CompletionTarget.Receiver -> {
            val items = analysisBestEffort(emptyList(), request.cancelChecker) {
              receiverCompletionItems(prefix, target, request.cancelChecker)
            }
            CompletionResult(items)
          }
          CompletionTarget.None -> CompletionResult.EMPTY
          else -> CompletionResult(completionItemsFor(prefix, element))
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
    val target = withSemanticLiveFile(request) { file ->
      val resolution = resolveOrdinaryTargetAt(file, requestOffset(request))
      val symbol = (resolution as? KotlinSemanticTargetResolution.Unique)?.symbol ?: return@withSemanticLiveFile null
      if (symbol.origin != org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin.SOURCE) return@withSemanticLiveFile null
      SearchTarget(symbol.createPointer(), request.file)
    } ?: return ReferenceResult(emptyList())

    val locations = mutableListOf<Location>()
    val roles = mutableListOf<ReferenceRole>()
    env.liveIndex.currentPaths().forEach { path ->
      request.cancelChecker.abortIfCancelled()
      env.liveIndex.withLiveKtFile(path, request.cancelChecker) { live ->
        if (live.isStale) return@withLiveKtFile Unit
        live.analyzing(request.cancelChecker) { file ->
          val visitor = object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitElement(element: PsiElement) {
              request.cancelChecker.abortIfCancelled()
              if (request.includeDeclaration && element is KtNamedDeclaration) {
                val nameIdentifier = element.nameIdentifier
                if (nameIdentifier != null && analysisBestEffort(false, request.cancelChecker) {
                      element.symbol.createPointer().pointsToTheSameSymbolAs(target.pointer)
                    }) {
                  val location = locationForElement(path, file, nameIdentifier)
                  if (location != null) {
                    locations += location
                    roles += ReferenceRole.DEFINITION
                  }
                }
              }
              super.visitElement(element)
            }

            override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
              request.cancelChecker.abortIfCancelled()
              val reference = expression.reference as? KtReference
              if (reference != null && analysisBestEffort(false, request.cancelChecker) {
                    reference.resolveToSymbols().any { it.createPointer().pointsToTheSameSymbolAs(target.pointer) }
                  }) {
                val location = locationForElement(path, file, expression)
                if (location != null && (request.includeDeclaration || path != target.path || expression.parent !is KtNamedDeclaration)) {
                  locations += location
                  roles += if (path == target.path && expression.parent is KtNamedDeclaration) ReferenceRole.DEFINITION else ReferenceRole.USAGE
                }
              }
              super.visitSimpleNameExpression(expression)
            }
          }
          file.accept(visitor)
        }
      }
    }
    val distinctResults = locations.zip(roles).distinctBy { it.first }
    return ReferenceResult(distinctResults.map { it.first }, distinctResults.map { it.second })
  }

  override fun signatureHelp(request: KotlinSemanticRequest): SignatureHelp? =
      withSemanticLiveFile(request) { file ->
        val offset = requestOffset(request)
        val callElement = callElementAt(file, offset) ?: return@withSemanticLiveFile SignatureHelp(emptyList(), 0, 0)
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

  private data class SearchTarget(
      val pointer: KaSymbolPointer<KaSymbol>,
      val path: Path,
  )

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
    environment?.close()
    environment = null
  }
}