package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import java.nio.file.Path

internal interface ReferenceScanObserver {
  fun candidateSelected(path: Path) = Unit

  fun prefilterSkipped(path: Path) = Unit

  fun analysisSessionStarted(path: Path) = Unit

  fun referenceResolved(path: Path) = Unit

  fun fileFailed(path: Path) = Unit

  companion object {
    val NONE: ReferenceScanObserver = object : ReferenceScanObserver {}
  }
}