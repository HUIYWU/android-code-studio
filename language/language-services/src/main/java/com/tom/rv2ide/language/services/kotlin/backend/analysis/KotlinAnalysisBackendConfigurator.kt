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

import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendContext
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendId
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import java.io.File

/**
 * Configurator for the in-process Kotlin Analysis API backend.
 */
class KotlinAnalysisBackendConfigurator(
    private val context: KotlinBackendContext,
) : KotlinBackendConfigurator {
  override fun resolveWorkspaceRoot(): File =
      context.mainModuleWorkspaceRoot(KotlinBackendId.ANALYSIS)

  override fun initializationOptions(): JsonObject? = null

  override fun beforeBackendStart(connection: KotlinBackendConnection) {
    KlsLogs.info("Analysis API Kotlin backend selected - in-process engine, no external process to configure")
  }

  override fun afterBackendInitialized(connection: KotlinBackendConnection, hasSupportedDocuments: Boolean) {
    KlsLogs.info("Analysis API Kotlin backend initialized - no backend-specific post-init configuration")
  }

  override fun onEnvironmentRefreshed(connection: KotlinBackendConnection) {}

  override fun applyFormattingStyle(connection: KotlinBackendConnection, style: String) {}
}
