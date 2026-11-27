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
import com.tom.rv2ide.projects.ModuleProject
import java.util.concurrent.atomic.AtomicReference

interface JavaPsiServiceResolver {
  fun fields(params: JavaPsiFieldsParams): JavaPsiFieldsResult? = null

  fun generateConstructor(params: JavaPsiGenerateConstructorParams): JavaPsiConstructorResult? = null

  fun generateAccessors(params: JavaPsiGenerateAccessorsParams): JavaPsiAccessorsResult? = null

  fun generateToString(params: JavaPsiGenerateToStringParams): JavaPsiToStringResult? = null

  fun overrideMethods(params: JavaPsiOverrideMethodsParams): JavaPsiOverrideMethodsResult? = null

  fun generateOverrides(params: JavaPsiGenerateOverridesParams): JavaPsiOverridesResult? = null

  fun implementAbstractMethods(
      params: JavaPsiImplementAbstractMethodsParams,
  ): JavaPsiOverridesResult? = null

  fun addUnhandledExceptions(
      params: JavaPsiAddUnhandledExceptionsParams,
  ): List<JavaPsiTextEdit>? = null

  fun removeUnusedThrows(
      params: JavaPsiRemoveUnusedThrowsParams,
  ): JavaPsiTextEdit? = null

  fun removeUnusedImports(
      params: JavaPsiRemoveUnusedImportsParams,
  ): List<JavaPsiTextEdit>? = null

  fun createMissingMethod(
      params: JavaPsiUnresolvedMethodParams,
  ): JavaPsiTextEdit? = null

  fun suppressUncheckedWarning(
      params: JavaPsiSuppressUncheckedWarningParams,
  ): JavaPsiTextEdit? = null

  fun resolveImportCandidates(
      params: JavaPsiResolveImportCandidatesParams,
  ): List<JavaPsiImportCandidate>? = null

  fun fixImports(params: JavaPsiFixImportsParams): JavaPsiFixImportsResult? = null

  fun generateImports(params: JavaPsiGenerateImportsParams): List<JavaPsiTextEdit>? = null

  fun expandSelection(params: ExpandSelectionParams): Range?

  fun definition(params: DefinitionParams): JavaPsiDefinitionTarget?

  fun references(params: ReferenceParams): ReferenceResult?

  fun hover(params: DefinitionParams): JavaPsiHoverTarget?

  fun signatureHelp(params: SignatureHelpParams): SignatureHelp?

  fun complete(params: CompletionParams, module: ModuleProject?, matchAllLowerCase: Boolean): CompletionResult?
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
  fun currentGeneration(): Long? = state.get().let { if (it.resolver == null) null else it.generation }

  @JvmStatic
  fun isCurrentGeneration(generation: Long): Boolean = state.get().let {
    it.resolver != null && it.generation == generation
  }

  @JvmStatic
  fun fields(params: JavaPsiFieldsParams): JavaPsiFieldsResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.fields(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun generateConstructor(params: JavaPsiGenerateConstructorParams): JavaPsiConstructorResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.generateConstructor(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun generateAccessors(params: JavaPsiGenerateAccessorsParams): JavaPsiAccessorsResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.generateAccessors(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun generateToString(params: JavaPsiGenerateToStringParams): JavaPsiToStringResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.generateToString(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun overrideMethods(params: JavaPsiOverrideMethodsParams): JavaPsiOverrideMethodsResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.overrideMethods(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun generateOverrides(params: JavaPsiGenerateOverridesParams): JavaPsiOverridesResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.generateOverrides(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun implementAbstractMethods(
      params: JavaPsiImplementAbstractMethodsParams,
  ): JavaPsiOverridesResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.implementAbstractMethods(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun addUnhandledExceptions(params: JavaPsiAddUnhandledExceptionsParams): List<JavaPsiTextEdit>? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.addUnhandledExceptions(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun removeUnusedThrows(params: JavaPsiRemoveUnusedThrowsParams): JavaPsiTextEdit? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.removeUnusedThrows(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun removeUnusedImports(params: JavaPsiRemoveUnusedImportsParams): List<JavaPsiTextEdit>? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.removeUnusedImports(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun createMissingMethod(params: JavaPsiUnresolvedMethodParams): JavaPsiTextEdit? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.createMissingMethod(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun suppressUncheckedWarning(params: JavaPsiSuppressUncheckedWarningParams): JavaPsiTextEdit? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.suppressUncheckedWarning(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun resolveImportCandidates(params: JavaPsiResolveImportCandidatesParams): List<JavaPsiImportCandidate>? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.resolveImportCandidates(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun fixImports(params: JavaPsiFixImportsParams): JavaPsiFixImportsResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.fixImports(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

  @JvmStatic
  fun generateImports(params: JavaPsiGenerateImportsParams): List<JavaPsiTextEdit>? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.generateImports(params)
    params.cancelChecker.abortIfCancelled()
    return if (state.get() === current) result else null
  }

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
  fun complete(params: CompletionParams, module: ModuleProject?, matchAllLowerCase: Boolean): CompletionResult? {
    params.cancelChecker.abortIfCancelled()
    val current = state.get()
    val resolver = current.resolver ?: return null
    val result = resolver.complete(params, module, matchAllLowerCase)
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