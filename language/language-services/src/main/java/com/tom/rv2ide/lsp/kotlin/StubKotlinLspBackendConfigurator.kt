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
 * No-op configurator for backend skeleton wiring.
 */
object StubKotlinLspBackendConfigurator : KotlinLspBackendConfigurator {
  override fun beforeServerStart(
      processManager: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  ) {
    KslLogs.info("Stub Kotlin backend: no pre-start workspace configuration")
  }

  override fun afterServerInitialized(
      processManager: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  ) {
    KslLogs.info("Stub Kotlin backend: no post-initialize workspace configuration")
  }
}
