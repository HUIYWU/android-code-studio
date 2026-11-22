package com.tom.rv2ide.language.services.java.interop

import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Range
import java.util.concurrent.atomic.AtomicReference

interface JavaPsiServiceResolver {
  fun expandSelection(params: ExpandSelectionParams): Range?

  fun definition(params: DefinitionParams): JavaPsiDefinitionTarget?

  fun references(params: ReferenceParams): ReferenceResult?

  fun hover(params: DefinitionParams): JavaPsiHoverTarget?

  fun signatureHelp(params: SignatureHelpParams): SignatureHelp?

  fun completeMember(params: CompletionParams): CompletionResult?
}

class JavaPsiDefinitionTarget(
    @JvmField val kind: Kind,
    @JvmField val locations: List<Location>,
) {
  enum class Kind {
    JAVA_SOURCE,
    KOTLIN_SOURCE,
  }
}

object JavaPsiServiceInterop {
  private class State(val resolver: JavaPsiServiceResolver?, val generation: Long)

  private val state = AtomicReference(State(null, 0L))

  @JvmStatic
  fun register(resolver: JavaPsiServiceResolver) {
    while (true) {
      val current = state.get()
      if (state.compareAndSet(current, State(resolver, current.generation + 1L))) return
    }
  }

  @JvmStatic
  fun unregister(resolver: JavaPsiServiceResolver) {
    while (true) {
      val current = state.get()
      if (current.resolver !== resolver) return
      if (state.compareAndSet(current, State(null, current.generation + 1L))) return
    }
  }

  @JvmStatic
  fun isAvailable(): Boolean = state.get().resolver != null

  @JvmStatic
  fun expandSelection(params: ExpandSelectionParams): Range? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.expandSelection(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun definition(params: DefinitionParams): JavaPsiDefinitionTarget? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.definition(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun references(params: ReferenceParams): ReferenceResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.references(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun hover(params: DefinitionParams): JavaPsiHoverTarget? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.hover(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun completeMember(params: CompletionParams): CompletionResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.completeMember(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun signatureHelp(params: SignatureHelpParams): SignatureHelp? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.signatureHelp(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }
}