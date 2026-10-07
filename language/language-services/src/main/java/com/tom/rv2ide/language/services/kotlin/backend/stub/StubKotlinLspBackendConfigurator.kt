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
package com.tom.rv2ide.language.services.kotlin.backend.stub

import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendConfigurator
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendContext
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendId
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspConnection
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import java.io.File

/**
 * No-op configurator for backend skeleton wiring.
 */
class StubKotlinLspBackendConfigurator(
    private val context: KotlinLspBackendContext,
) : KotlinLspBackendConfigurator {
  override fun resolveWorkspaceRoot(): File =
      context.mainModuleWorkspaceRoot(KotlinLspBackendId.STUB)

  override fun initializationOptions(): JsonObject? = null

  override fun beforeServerStart(connection: KotlinLspConnection) {
    KlsLogs.info("Stub Kotlin backend: no pre-start workspace configuration")
  }

  override fun afterServerInitialized(connection: KotlinLspConnection, hasKotlinSources: Boolean) {
    KlsLogs.info("Stub Kotlin backend: no post-initialize workspace configuration")
  }

  override fun onClasspathReloaded(connection: KotlinLspConnection) {}

  override fun applyFormattingStyle(connection: KotlinLspConnection, style: String) {}
}
