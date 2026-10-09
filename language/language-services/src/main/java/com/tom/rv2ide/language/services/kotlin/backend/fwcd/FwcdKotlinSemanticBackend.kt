package com.tom.rv2ide.language.services.kotlin.backend.fwcd

import com.tom.rv2ide.language.services.kotlin.request.KotlinRequestHandler
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticBackend
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest

internal class FwcdKotlinSemanticBackend(
    private val requestHandler: KotlinRequestHandler,
) : KotlinSemanticBackend {
  override suspend fun complete(request: KotlinSemanticRequest) = requestHandler.complete(request)
  override suspend fun hover(request: KotlinSemanticRequest) = requestHandler.hover(request)
  override suspend fun findDefinition(request: KotlinSemanticRequest) = requestHandler.findDefinition(request)
  override suspend fun findReferences(request: KotlinSemanticRequest) = requestHandler.findReferences(request)
  override suspend fun signatureHelp(request: KotlinSemanticRequest) = requestHandler.signatureHelp(request)
}