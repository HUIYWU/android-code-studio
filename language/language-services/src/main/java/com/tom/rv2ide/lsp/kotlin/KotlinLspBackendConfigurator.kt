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
package com.tom.rv2ide.lsp.kotlin

/**
 * Backend-specific hooks that customize otherwise generic Kotlin workspace setup.
 *
 * The initial scope is intentionally small: only the behaviors that remain
 * backend-specific in the current Kotlin LSP integration are extracted here.
 */
interface KotlinLspBackendConfigurator {
  fun beforeServerStart(
      processManager: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  )

  fun afterServerInitialized(
      processManager: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  )
}