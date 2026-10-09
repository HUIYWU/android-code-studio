package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticBackend
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequestValidator
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp

internal class AnalysisKotlinSemanticBackend(
    private val connection: KotlinAnalysisBackendConnection,
    private val validator: KotlinSemanticRequestValidator,
) : KotlinSemanticBackend {
  override suspend fun complete(request: KotlinSemanticRequest) =
      connection.complete(request, validator::isCurrent) ?: CompletionResult(emptyList())

  override suspend fun hover(request: KotlinSemanticRequest) =
      connection.hover(request, validator::isCurrent) ?: MarkupContent()

  override suspend fun findDefinition(request: KotlinSemanticRequest) =
      connection.findDefinition(request, validator::isCurrent) ?: DefinitionResult(emptyList())

  override suspend fun findReferences(request: KotlinSemanticRequest) =
      connection.findReferences(request, validator::isCurrent) ?: ReferenceResult(emptyList())

  override suspend fun signatureHelp(request: KotlinSemanticRequest) =
      connection.signatureHelp(request, validator::isCurrent) ?: SignatureHelp(emptyList(), 0, 0)
}