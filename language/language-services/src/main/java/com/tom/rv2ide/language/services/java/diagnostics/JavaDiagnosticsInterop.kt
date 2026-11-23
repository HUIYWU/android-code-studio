package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

interface JavaDiagnosticsResolver {
  fun diagnostics(
      file: Path,
      cancelChecker: ICancelChecker,
      documentVersion: Int,
      documentRevision: Long,
  ): List<DiagnosticItem>?
}

object JavaDiagnosticsInterop {
  private val log = LoggerFactory.getLogger(JavaDiagnosticsInterop::class.java)

  private class State(val resolver: JavaDiagnosticsResolver?, val generation: Long)

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
      documentVersion: Int,
      documentRevision: Long,
  ): Result {
    val current = state.get()
    val resolver = current.resolver ?: run {
      if (IdeLogConfig.shouldLogInfo()) {
        log.info("Java diagnostics interop unavailable file={} generation={}", file, current.generation)
      }
      return Result.Unavailable
    }
    return try {
      val diagnostics = resolver.diagnostics(file, cancelChecker, documentVersion, documentRevision)
      if (diagnostics == null) Result.Unavailable
      else if (state.get() === current) Result.Success(current.generation, diagnostics)
      else Result.Unavailable
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      log.warn("Java diagnostics interop failed file={} generation={}", file, current.generation, error)
      Result.Failure(current.generation, error)
    }
  }

  sealed interface Result {
    data object Unavailable : Result
    data class Success(val generation: Long, val diagnostics: List<DiagnosticItem>) : Result
    data class Failure(val generation: Long, val error: Exception) : Result
  }
}