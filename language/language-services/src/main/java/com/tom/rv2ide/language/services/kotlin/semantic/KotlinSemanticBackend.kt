package com.tom.rv2ide.language.services.kotlin.semantic

import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp

internal interface KotlinSemanticBackend {
  suspend fun complete(request: KotlinSemanticRequest): CompletionResult
  suspend fun hover(request: KotlinSemanticRequest): MarkupContent
  suspend fun findDefinition(request: KotlinSemanticRequest): DefinitionResult
  suspend fun findReferences(request: KotlinSemanticRequest): ReferenceResult
  suspend fun signatureHelp(request: KotlinSemanticRequest): SignatureHelp
}

internal object EmptyKotlinSemanticBackend : KotlinSemanticBackend {
  override suspend fun complete(request: KotlinSemanticRequest) = CompletionResult(emptyList())
  override suspend fun hover(request: KotlinSemanticRequest) = MarkupContent()
  override suspend fun findDefinition(request: KotlinSemanticRequest) = DefinitionResult(emptyList())
  override suspend fun findReferences(request: KotlinSemanticRequest) = ReferenceResult(emptyList())
  override suspend fun signatureHelp(request: KotlinSemanticRequest) = SignatureHelp(emptyList(), 0, 0)
}