/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.lsp.java.models

import com.tom.rv2ide.lsp.java.compiler.CompilationTaskProcessor
import com.tom.rv2ide.lsp.java.compiler.DefaultCompilationTaskProcessor
import java.util.function.Consumer
import jdkx.tools.JavaFileObject
import openjdk.tools.javac.util.Context

/**
 * Data sent to the stable Java compilation path.
 *
 * <p>Method-level incremental analysis is optional and remains a request-scoped plan. Requests
 * without a plan continue to use the stable full compilation path.
 *
 * @param sources The source files to compile.
 * @author Akash Yadav
 */
data class CompilationRequest
@JvmOverloads
constructor(
    @JvmField val sources: Collection<JavaFileObject>,
    @JvmField val compilationTaskProcessor: CompilationTaskProcessor = DefaultCompilationTaskProcessor(),
    @JvmField var configureContext: Consumer<Context>? = null,
    @JvmField val methodReparsePlan: MethodReparsePlan? = null,
    @JvmField val documentState: CompilationDocumentState? = null,
)
