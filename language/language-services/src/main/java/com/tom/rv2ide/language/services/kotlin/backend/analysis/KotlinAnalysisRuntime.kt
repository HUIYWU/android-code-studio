package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequest
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import com.tom.rv2ide.projects.models.OneHopDocumentEdit
import java.nio.file.Path

internal interface KotlinAnalysisRuntime {
  fun start(): Boolean
  fun onFileOpened(path: Path) = Unit
  fun onFileContentChanged(path: Path, edit: OneHopDocumentEdit? = null) = Unit
  fun onFileClosed(path: Path) = Unit
  fun onFileCreated(path: Path) = Unit
  fun onFileRemoved(path: Path) = Unit
  fun onFileMoved(fromPath: Path, toPath: Path) = Unit
  fun analyze(path: Path, snapshot: ActiveDocumentSnapshot?): DiagnosticResult?
  fun complete(request: KotlinSemanticRequest): CompletionResult?
  fun hover(request: KotlinSemanticRequest): MarkupContent?
  fun findDefinition(request: KotlinSemanticRequest): DefinitionResult?
  fun findReferences(request: KotlinSemanticRequest): ReferenceResult?
  fun signatureHelp(request: KotlinSemanticRequest): SignatureHelp?
  fun close()
}

internal fun interface KotlinAnalysisRuntimeFactory {
  fun create(classpathProvider: KotlinProjectClasspathProvider): KotlinAnalysisRuntime
}
