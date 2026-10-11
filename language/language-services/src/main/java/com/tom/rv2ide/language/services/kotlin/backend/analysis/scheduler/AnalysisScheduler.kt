package com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler

import com.tom.rv2ide.progress.ICancelChecker
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal enum class AnalysisPriority {
  INDEXING,
  DIAGNOSTICS,
  SEMANTIC,
}

internal object AnalysisScheduler {
  private val lock = ReentrantLock(true)

  fun <T> withLock(
      priority: AnalysisPriority,
      cancelChecker: ICancelChecker,
      action: () -> T,
  ): T {
    while (!lock.tryLock(25L, TimeUnit.MILLISECONDS)) {
      cancelChecker.abortIfCancelled()
    }
    try {
      cancelChecker.abortIfCancelled()
      return action()
    } finally {
      lock.unlock()
    }
  }
}
