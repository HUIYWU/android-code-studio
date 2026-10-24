package com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler

import com.tom.rv2ide.progress.ICancelChecker
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal class AnalysisRequestCancellation(
    private val parent: ICancelChecker,
) : ICancelChecker {
  private val cancelled = AtomicBoolean(false)

  override fun cancel() {
    cancelled.set(true)
  }

  override fun isCancelled(): Boolean = cancelled.get() || parent.isCancelled()

  override fun abortIfCancelled() {
    if (isCancelled()) throw CancellationException()
  }
}