package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.progress.ICancelChecker
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.ControlFlowException
import org.jetbrains.kotlin.com.intellij.openapi.project.IndexNotReadyException

internal fun analysisExceptionToPropagate(
    error: Throwable,
    cancelChecker: ICancelChecker,
): Throwable? {
  val causes = generateSequence(error) { current ->
    current.cause?.takeUnless { it === current }
  }.toList()
  causes.firstOrNull { it is ControlFlowException || it is IndexNotReadyException }?.let { return it }
  return causes.firstOrNull { it is CancellationException }
      ?.takeUnless { cancelChecker.isCancelled() }
}

internal fun isRequestCancellation(
    error: Throwable,
    cancelChecker: ICancelChecker,
): Boolean {
  val causes = generateSequence(error) { current ->
    current.cause?.takeUnless { it === current }
  }.toList()
  if (causes.any { it is ControlFlowException || it is IndexNotReadyException }) return false
  return cancelChecker.isCancelled() && causes.any { it is CancellationException }
}

internal fun <T> analysisBestEffort(
    fallback: T,
    cancelChecker: ICancelChecker,
    action: () -> T,
): T = try {
  action()
} catch (error: Throwable) {
  analysisExceptionToPropagate(error, cancelChecker)?.let { throw it }
  fallback
}
