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
package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.language.services.java.semantic.JavaModuleScopes
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.lsp.testing.LspWorkspaceTestSupport
import com.tom.rv2ide.utils.FileProvider

import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real Tooling-derived module and javac analyzer boundary.
 *
 * This is intentionally below JavaLanguageServer: JUnit/Robolectric is a JVM environment, where
 * JavaLanguageServer deliberately bypasses the debounce timer. It verifies analyzer yield and subsequent
 * direct analysis only; Android debounce retry and publishDiagnostics require instrumentation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaDiagnosticsAnalyzerWorkspaceIntegrationTest {

  @Test
  fun foregroundLease_yieldsThenReleasedLeaseAnalyzesCurrentDocument() {
    val file =
        FileProvider.testProjectRoot()
            .resolve("app/src/main/java/com/tom/rv2ide/testing/fixture/DiagnosticFixture.java")
            .normalize()
    val contents = file.readText()

    LspWorkspaceTestSupport.withWorkspace {
      openDocument(file, contents, version = 23, revision = 41L)
      val module = requireNotNull(workspace.findModuleForFile(file, checkExistance = true))
      val session = JavaModuleScopes.forModule(module)
      val analyzer = JavaDiagnosticsAnalyzer()

      val lease = session.beginForegroundSemantic()
      try {
        val yielded = analyzer.analyze(file)
        assertSame("interactive work must make speculative diagnostics yield", DiagnosticResult.NO_UPDATE, yielded)
        assertTrue("one yielded analysis must request exactly one later retry", analyzer.consumeYieldRetry(yielded))
        assertFalse("the yielded retry token is one-shot", analyzer.consumeYieldRetry(yielded))
      } finally {
        lease.close()
      }

      val analyzed = analyzer.analyze(file)
      assertFalse("released interactive lease must let analyzer enter real analysis", analyzed === DiagnosticResult.NO_UPDATE)
      assertEquals(file, analyzed.file)
      assertEquals(23, analyzed.documentVersion)
      assertEquals(41L, analyzed.documentRevision)
    }
  }
}