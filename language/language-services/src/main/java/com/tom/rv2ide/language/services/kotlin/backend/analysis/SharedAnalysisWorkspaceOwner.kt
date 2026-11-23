package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.projects.IWorkspace
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the shared Analysis environments used by Kotlin and Java features.
 *
 * A normal acquire reuses the environment for the same workspace and increments its lease count. A
 * fresh acquire always starts a new environment, which lets the backend replace the environment
 * during a refresh without reusing the old PSI project. The Java interop slot always points at the
 * most recently registered environment; releasing an older environment does not clear it.
 */
internal object SharedAnalysisWorkspaceOwner {
  internal class Lease
  internal constructor(
      internal val environment: LiveAnalysisEnvironment?,
      internal val failure: Throwable?,
      internal val failureReason: String?,
  ) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
      if (!released.compareAndSet(false, true)) return
      SharedAnalysisWorkspaceOwner.release(this)
    }
  }

  private class State(
      val workspace: IWorkspace?,
      val environment: LiveAnalysisEnvironment,
      var leases: Int,
  )

  private val lock = Any()
  private val states = mutableListOf<State>()

  fun acquire(
      classpathProvider: KotlinProjectClasspathProvider,
      intellijPluginRoot: String,
      fresh: Boolean,
  ): Lease =
      synchronized(lock) {
        if (!fresh) {
          val reusable = states.lastOrNull { it.workspace === classpathProvider.workspace }
          if (reusable != null) {
            reusable.leases++
            return Lease(reusable.environment, null, null)
          }
        }
        val created = LiveAnalysisEnvironment(classpathProvider, intellijPluginRoot)
        if (!created.start()) {
          val failure = created.startFailure
          val reason = created.startFailureReason
          created.close()
          return Lease(null, failure, reason)
        }
        JavaPsiInterop.register(created.javaPsiResolver)
        JavaPsiServiceInterop.register(created.javaServicePsiResolver)
        JavaDiagnosticsInterop.register(created.javaDiagnosticsResolver)
        states += State(classpathProvider.workspace, created, 1)
        Lease(created, null, null)
      }

  private fun release(lease: Lease) {
    val released =
        synchronized(lock) {
          val environment = lease.environment ?: return
          val index = states.indexOfFirst { it.environment === environment }
          if (index < 0) return
          val state = states[index]
          state.leases--
          if (state.leases > 0) return
          states.removeAt(index)
          state to states.lastOrNull()
        }
    val closed = released.first
    JavaPsiInterop.unregister(closed.environment.javaPsiResolver)
    JavaPsiServiceInterop.unregister(closed.environment.javaServicePsiResolver)
    JavaDiagnosticsInterop.unregister(closed.environment.javaDiagnosticsResolver)
    // The released environment may have owned the interop slot; fall back to a surviving one.
    released.second?.let {
      JavaPsiInterop.register(it.environment.javaPsiResolver)
      JavaPsiServiceInterop.register(it.environment.javaServicePsiResolver)
      JavaDiagnosticsInterop.register(it.environment.javaDiagnosticsResolver)
    }
    closed.environment.close()
  }
}