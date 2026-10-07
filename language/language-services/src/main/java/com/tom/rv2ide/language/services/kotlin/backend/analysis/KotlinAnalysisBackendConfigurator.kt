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
package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspConnection
import com.tom.rv2ide.language.services.kotlin.compiler.KotlinClasspathProvider
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs

/**
 * Configurator for the in-process Kotlin Analysis API backend.
 */
object KotlinAnalysisBackendConfigurator : KotlinLspBackendConfigurator {
  override fun beforeServerStart(
      connection: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  ) {
    KlsLogs.info("Analysis API Kotlin backend selected - in-process engine, no external process to configure")
  }

  override fun afterServerInitialized(
      connection: KotlinLspConnection,
      classpathProvider: KotlinClasspathProvider,
  ) {
    KlsLogs.info("Analysis API Kotlin backend initialized - no backend-specific post-init configuration")
  }
}
