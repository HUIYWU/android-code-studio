package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.projects.models.ActiveDocumentSnapshot
import java.nio.file.Path

internal interface KotlinAnalysisRuntime {
  fun start(): Boolean
  fun analyze(path: Path, snapshot: ActiveDocumentSnapshot?): DiagnosticResult?
  fun close()
}

internal fun interface KotlinAnalysisRuntimeFactory {
  fun create(classpathProvider: KotlinProjectClasspathProvider): KotlinAnalysisRuntime
}
