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
import com.tom.rv2ide.projects.ModuleProject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local registry of active module semantic scopes.
 *
 * No compiler, document text or AST is duplicated here. Removing a scope also destroys its reusable
 * compiler so module eviction and workspace resets release the heavy javac state.
 */
internal object JavaModuleScopes {
  private val scopes = ConcurrentHashMap<ModuleProject, JavaModuleScope>()
  private val epochSource = AtomicLong(0)

  fun forModule(module: ModuleProject): JavaModuleScope =
      scopes.computeIfAbsent(module) {
        JavaModuleScope(
            module = it,
            nextEpoch = epochSource::incrementAndGet,
            initialEpoch = epochSource.incrementAndGet(),
        )
      }

  /** Cancels stale work across existing scopes without allocating a compiler scope. */
  fun cancelOlderThan(file: Path, revision: Long) {
    scopes.values.forEach { it.cancelOlderThan(file, revision) }
  }

  /** Cancels all completion and signature work for a closed file even without module resolution. */
  fun cancelForFile(file: Path) {
    scopes.values.forEach { it.cancelForFile(file) }
  }

  fun isCurrent(scope: JavaModuleScope, epoch: Long): Boolean =
      scopes[scope.module] === scope && scope.epoch == epoch

  fun destroy(module: ModuleProject) {
    scopes.remove(module)?.invalidateEpoch()
    JavaCompilerProvider.getInstance().destroy(module)
  }

  fun destroyAll() {
    scopes.values.forEach { it.invalidateEpoch() }
    scopes.clear()
    JavaCompilerProvider.getInstance().destroy()
  }
}