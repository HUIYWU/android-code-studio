package com.tom.rv2ide.language.services.kotlin.semantic

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

internal class KotlinSemanticService(
    private val backend: KotlinSemanticBackend,
    private val validator: KotlinSemanticRequestValidator,
) {
  private val closed = AtomicBoolean(false)

  fun close() {
    closed.set(true)
  }

  suspend fun complete(params: CompletionParams): CompletionResult =
      execute(params.file, params.position, params.documentVersion, params.documentRevision,
          params.cancelChecker, { CompletionResult(emptyList()) }, params.prefix) { backend.complete(it) }

  suspend fun hover(params: DefinitionParams): MarkupContent =
      execute(params.file, params.position, params.documentVersion, params.documentRevision,
          params.cancelChecker, { MarkupContent() }) { backend.hover(it) }

  suspend fun findDefinition(params: DefinitionParams): DefinitionResult =
      execute(params.file, params.position, params.documentVersion, params.documentRevision,
          params.cancelChecker, { DefinitionResult(emptyList()) }) { backend.findDefinition(it) }

  suspend fun findReferences(params: ReferenceParams): ReferenceResult =
      execute(params.file, params.position, params.documentVersion, params.documentRevision,
          params.cancelChecker, { ReferenceResult(emptyList()) },
          includeDeclaration = params.includeDeclaration) { backend.findReferences(it) }

  suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp =
      execute(params.file, params.position, params.documentVersion, params.documentRevision,
          params.cancelChecker, { SignatureHelp(emptyList(), 0, 0) }) { backend.signatureHelp(it) }

  private suspend fun <T> execute(
      file: Path,
      position: Position,
      version: Int,
      revision: Long,
      cancelChecker: ICancelChecker,
      empty: () -> T,
      prefix: String? = null,
      includeDeclaration: Boolean = false,
      action: suspend (KotlinSemanticRequest) -> T,
  ): T {
    val coroutineContext = currentCoroutineContext()
    coroutineContext.ensureActive()
    val requestCancellation = object : ICancelChecker {
      override fun cancel() = cancelChecker.cancel()
      override fun isCancelled(): Boolean =
          closed.get() || !coroutineContext.isActive || cancelChecker.isCancelled()
      override fun abortIfCancelled() {
        if (isCancelled()) throw CancellationException()
      }
    }
    val request = validator.capture(file, position, version, revision, requestCancellation,
        prefix, includeDeclaration) ?: return empty()
    return try {
      val result = action(request)
      coroutineContext.ensureActive()
      if (validator.isCurrent(request)) result else empty()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      KlsLogs.warn("Kotlin semantic request failed for: {}", request.file, e)
      empty()
    }
  }
}