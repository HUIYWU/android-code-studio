/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.language.services.java.semantic

import com.tom.rv2ide.language.services.java.JavaCompilerProvider
import com.tom.rv2ide.language.services.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.projects.ModuleProject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Semantic ownership boundary of one Java module.
 *
 * The scope holds only identities, counters and in-flight bookkeeping. Source text, javac trees and
 * the reusable compiler stay with their existing owners, so retaining a scope while a module is
 * active stays cheap on Android.
 *
 * [epoch] changes whenever classpath, Kotlin ABI or module state invalidates the semantic
 * environment. It is the single environment identity used by request keys and result validation.
 */
internal class JavaModuleScope internal constructor(
    val module: ModuleProject,
    private val nextEpoch: () -> Long,
    initialEpoch: Long,
) {
  private val epochValue = AtomicLong(initialEpoch)
  private val foregroundLeases = AtomicInteger(0)

  val epoch: Long
    get() = epochValue.get()

  fun compiler(): JavaCompilerService = JavaCompilerProvider.get(module)

  /**
   * Marks foreground semantic work such as completion, signature help, definition, references,
   * expand-selection or hover as active.
   *
   * Diagnostics consult [hasForegroundWork] immediately before entering javac so speculative
   * analysis yields instead of competing for the module's reusable compiler.
   */
  fun beginForegroundSemantic(): AutoCloseable {
    foregroundLeases.incrementAndGet()
    return AutoCloseable { foregroundLeases.decrementAndGet() }
  }

  val hasForegroundWork: Boolean
    get() = foregroundLeases.get() > 0

  /** Identity of a shareable completion computation. */
  data class CompletionRequestKey(
      override val file: Path,
      val stamp: DocumentStamp,
      val epoch: Long,
      val cursorIndex: Long,
      val prefix: String,
  ) : SemanticRequestKey {
    override val documentRevision: Long
      get() = stamp.revision

    val documentVersion: Int
      get() = stamp.version

    init {
      require(cursorIndex >= 0L) { "A completion key requires a valid cursor index" }
    }

    companion object {
      fun create(
          file: Path,
          documentVersion: Int,
          documentRevision: Long,
          epoch: Long,
          cursorIndex: Long,
          prefix: String?,
      ): CompletionRequestKey =
          CompletionRequestKey(
              file = file.normalize(),
              stamp = DocumentStamp(documentVersion, documentRevision),
              epoch = epoch,
              cursorIndex = cursorIndex,
              prefix = prefix.orEmpty(),
          )
    }
  }

  /** Identity of a shareable Signature Help computation. */
  data class SignatureRequestKey(
      override val file: Path,
      val stamp: DocumentStamp,
      val epoch: Long,
      val cursorIndex: Long,
  ) : SemanticRequestKey {
    override val documentRevision: Long
      get() = stamp.revision

    val documentVersion: Int
      get() = stamp.version

    init {
      require(cursorIndex >= 0L) { "A signature key requires a valid cursor index" }
    }

    companion object {
      fun create(
          file: Path,
          documentVersion: Int,
          documentRevision: Long,
          epoch: Long,
          cursorIndex: Long,
      ): SignatureRequestKey =
          SignatureRequestKey(
              file = file.normalize(),
              stamp = DocumentStamp(documentVersion, documentRevision),
              epoch = epoch,
              cursorIndex = cursorIndex,
          )
    }
  }

  private val completionRequests = ConcurrentHashMap<CompletionRequestKey, InFlightRequest<CompletionResult>>()
  private val signatureRequests = ConcurrentHashMap<SignatureRequestKey, InFlightRequest<SignatureHelp>>()

  /**
   * Returns the only active completion state for [key], together with whether this caller created it.
   *
   * Terminal states are atomically replaced so a new request cannot inherit a just-cancelled worker
   * before its finally block removes the old entry.
   */
  fun acquireCompletion(key: CompletionRequestKey): Pair<InFlightRequest<CompletionResult>, Boolean> {
    while (true) {
      val created = InFlightRequest<CompletionResult>(key, "Completion worker cancelled")
      val existing = completionRequests.putIfAbsent(key, created)
      if (existing == null) return created to true
      if (!existing.result.isDone) return existing to false
      if (completionRequests.replace(key, existing, created)) return created to true
    }
  }

  fun removeCompletion(state: InFlightRequest<CompletionResult>) {
    completionRequests.remove(state.key, state)
  }

  fun acquireSignatureHelp(
      key: SignatureRequestKey,
  ): Pair<InFlightRequest<SignatureHelp>, Boolean> {
    while (true) {
      val created = InFlightRequest<SignatureHelp>(key, "Signature Help worker cancelled")
      val existing = signatureRequests.putIfAbsent(key, created)
      if (existing == null) return created to true
      if (!existing.result.isDone) return existing to false
      if (signatureRequests.replace(key, existing, created)) return created to true
    }
  }

  fun removeSignatureHelp(state: InFlightRequest<SignatureHelp>) {
    signatureRequests.remove(state.key, state)
  }

  /**
   * Cancels only stale requests for [file]. EventBus listeners can run asynchronously, so an older
   * event may arrive after a newer request has already been registered. Comparing revisions rather
   * than cancelling every state for the file prevents that late event from killing current work.
   */
  fun cancelOlderThan(file: Path, revision: Long) {
    val normalizedFile = file.normalize()
    completionRequests.values.forEach { state ->
      if (state.key.file == normalizedFile && state.key.documentRevision < revision) {
        if (completionRequests.remove(state.key, state)) {
          state.cancelWorker()
        }
      }
    }
    signatureRequests.values.forEach { state ->
      if (state.key.file == normalizedFile && state.key.documentRevision < revision) {
        if (signatureRequests.remove(state.key, state)) {
          state.cancelWorker()
        }
      }
    }
  }

  /** A closed document has no valid Completion or Signature Help consumer. */
  fun cancelForFile(file: Path) {
    val normalizedFile = file.normalize()
    completionRequests.values.forEach { state ->
      if (state.key.file == normalizedFile && completionRequests.remove(state.key, state)) {
        state.cancelWorker()
      }
    }
    signatureRequests.values.forEach { state ->
      if (state.key.file == normalizedFile && signatureRequests.remove(state.key, state)) {
        state.cancelWorker()
      }
    }
  }

  private fun cancelAllRequests() {
    completionRequests.values.forEach { it.cancelWorker() }
    completionRequests.clear()
    signatureRequests.values.forEach { it.cancelWorker() }
    signatureRequests.clear()
  }

  fun invalidateEpoch(): Long {
    cancelAllRequests()
    val next = nextEpoch()
    epochValue.set(next)
    return next
  }
}