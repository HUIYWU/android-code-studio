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
package com.tom.rv2ide.language.services.kotlin.backend

import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentSync
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticBackend
import com.tom.rv2ide.language.services.kotlin.semantic.KotlinSemanticRequestValidator

/**
 * Bundles the transport connection and the per-workspace configurator factory for one concrete backend.
 */
internal class KotlinBackendSpec(
    val connection: KotlinBackendConnection,
    val createConfigurator: (KotlinBackendContext) -> KotlinBackendConfigurator,
    val createSemanticBackend: (KotlinBackendContext, KotlinDocumentSync, KotlinSemanticRequestValidator) -> KotlinSemanticBackend,
)
