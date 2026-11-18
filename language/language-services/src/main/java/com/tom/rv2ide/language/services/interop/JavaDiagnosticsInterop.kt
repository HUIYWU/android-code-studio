package com.tom.rv2ide.language.services.interop

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.progress.ICancelChecker
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

interface JavaDiagnosticsResolver {
  /** Returns the diagnostics, or null when no shared Java PSI is available for [file]. */
  fun diagnostics(file: Path, cancelChecker: ICancelChecker): List<DiagnosticItem>?
}

object JavaDiagnosticsInterop {
  private val log = LoggerFactory.getLogger(JavaDiagnosticsInterop::class.java)

  private class State(
      val resolver: JavaDiagnosticsResolver?,
      val generation: Long,
  )

  private val state = AtomicReference(State(null, 0L))

  @JvmStatic
  fun register(resolver: JavaDiagnosticsResolver) {
    while (true) {
      val current = state.get()
      if (state.compareAndSet(current, State(resolver, current.generation + 1L))) return
    }
  }

  @JvmStatic
  fun unregister(resolver: JavaDiagnosticsResolver) {
    while (true) {
      val current = state.get()
      if (current.resolver !== resolver) return
      if (state.compareAndSet(current, State(null, current.generation + 1L))) return
    }
  }

  @JvmStatic
  fun currentGeneration(): Long? {
    val current = state.get()
    return current.resolver?.let { current.generation }
  }

  @JvmStatic
  fun diagnostics(
      file: Path,
      cancelChecker: ICancelChecker,
  ): Result {
    val current = state.get()
    val resolver = current.resolver ?: run {
      if (IdeLogConfig.shouldLogInfo()) {
        log.info("Java diagnostics interop unavailable file={} generation={}", file, current.generation)
      }
      return Result.Unavailable
    }
    val startedAt = System.nanoTime()
    if (IdeLogConfig.shouldLogDebug()) {
      log.debug("Java diagnostics interop begin file={} generation={} resolver={}", file, current.generation, resolver.javaClass.name)
    }
    return try {
      val diagnostics = resolver.diagnostics(file, cancelChecker)
      if (diagnostics == null) {
        if (IdeLogConfig.shouldLogInfo()) {
          log.info("Java diagnostics interop returned unavailable file={} generation={} durationUs={}", file, current.generation, (System.nanoTime() - startedAt) / 1_000L)
        }
        Result.Unavailable
      } else {
        if (IdeLogConfig.shouldLogDebug()) {
          log.debug("Java diagnostics interop success file={} generation={} diagnostics={} durationUs={}", file, current.generation, diagnostics.size, (System.nanoTime() - startedAt) / 1_000L)
        }
        Result.Success(current.generation, diagnostics)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      log.warn("Java diagnostics interop failure file={} generation={} durationUs={} type={} message={}", file, current.generation, (System.nanoTime() - startedAt) / 1_000L, error.javaClass.name, error.message, error)
      Result.Failure(current.generation, error)
    }
  }

  sealed interface Result {
    data object Unavailable : Result

    data class Success(
        val generation: Long,
        val diagnostics: List<DiagnosticItem>,
    ) : Result

    data class Failure(
        val generation: Long,
        val error: Exception,
    ) : Result
  }
}
