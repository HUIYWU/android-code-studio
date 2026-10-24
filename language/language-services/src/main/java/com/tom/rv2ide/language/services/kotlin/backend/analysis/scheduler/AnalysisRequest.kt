package com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler

import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path

internal enum class AnalysisRequestKind(val rank: Int) {
  SEMANTIC(0),
  COMMAND(1),
  DIAGNOSTICS(2),
  INDEXING(3),
}

internal class AnalysisRequest<T>(
    val id: Long,
    val kind: AnalysisRequestKind,
    val path: Path?,
    val backendGeneration: Long,
    val documentVersion: Int?,
    val documentRevision: Long?,
    val cancelChecker: ICancelChecker,
    val attempt: Int = 0,
    val action: () -> T?,
    val cancel: () -> Unit = {},
) : Comparable<AnalysisRequest<*>> {
  override fun compareTo(other: AnalysisRequest<*>): Int {
    val kindComparison = kind.rank.compareTo(other.kind.rank)
    return if (kindComparison != 0) kindComparison else id.compareTo(other.id)
  }
}
