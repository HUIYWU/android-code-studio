package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.kotlin.com.intellij.openapi.project.IncompleteDependenciesService
import org.jetbrains.kotlin.com.intellij.openapi.project.IncompleteDependenciesService.IncompleteDependenciesAccessToken
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import java.util.Collections
import java.util.IdentityHashMap

// TODO(ACS-ANALYSIS-EXPERIMENT): Replace with the standalone project service when it provides this service in the embedded environment.
internal class LiveIncompleteDependenciesService(
    private val project: Project,
) : IncompleteDependenciesService {
  private val state = MutableStateFlow(IncompleteDependenciesService.DependenciesState.COMPLETE)
  private val tokens = Collections.newSetFromMap(IdentityHashMap<IncompleteDependenciesAccessToken, Boolean>())

  override val stateFlow: Flow<IncompleteDependenciesService.DependenciesState>
    get() = state

  override fun getState(): IncompleteDependenciesService.DependenciesState = state.value

  override fun enterIncompleteState(requestor: Any): IncompleteDependenciesAccessToken =
      synchronized(tokens) {
        val token = Token()
        tokens += token
        if (state.value != IncompleteDependenciesService.DependenciesState.INCOMPLETE) {
          state.value = IncompleteDependenciesService.DependenciesState.INCOMPLETE
        }
        token
      }

  private inner class Token : IncompleteDependenciesAccessToken() {
    private var finished = false

    override fun finish() {
      synchronized(tokens) {
        if (finished) return
        finished = true
        tokens.remove(this)
        if (tokens.isEmpty() && state.value != IncompleteDependenciesService.DependenciesState.COMPLETE) {
          state.value = IncompleteDependenciesService.DependenciesState.COMPLETE
          if (!project.isDisposed) PsiManager.getInstance(project).dropPsiCaches()
        }
      }
    }
  }
}