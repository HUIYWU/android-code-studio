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
 * Configurator for the fwcd/kotlin-language-server backend.
 *
 * fwcd has its own startup and configuration surface. This configurator keeps
 * backend-specific setup minimal and avoids sending extra
 * `workspace/didChangeConfiguration` payloads unless fwcd explicitly requires them.
 */
object FwcdKotlinLspBackendConfigurator : KotlinLspBackendConfigurator {
  override fun beforeServerStart(
      processManager: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  ) {
    KslLogs.info("FWCD Kotlin backend selected - no backend-specific pre-start configuration")
  }

  override fun afterServerInitialized(
      processManager: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  ) {
    KslLogs.info("FWCD Kotlin backend initialized - no backend-specific post-init configuration")
  }
}
