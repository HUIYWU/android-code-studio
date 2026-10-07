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
package com.tom.rv2ide.lsp.java

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.PerformCodeActionParams
import com.tom.rv2ide.lsp.models.ReferenceRole
import com.tom.rv2ide.lsp.models.ShowDocumentParams
import com.tom.rv2ide.lsp.models.ShowDocumentResult
import com.tom.rv2ide.models.Location
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the Android-only diagnostics scheduling boundary.
 *
 * Tooling workspace discovery and the real JavaDiagnosticProvider/javac path are covered by the JVM
 * workspace integration test. This test instead verifies that a real Handler-backed AnalyzeTimer
 * retries one yielded attempt and publishes only the later result on Android's main thread.
 */
@RunWith(AndroidJUnit4::class)
class JavaLanguageServerDiagnosticsInstrumentationTest {

  @Test
  fun yieldedAttempt_retriesAndPublishesOnlyFinalResultOnMainThread() {
    val file = Paths.get("/data/local/tmp/DiagnosticsTimerFixture.java")
    val attempts = AtomicInteger()
    val published = CountDownLatch(1)
    var publishedResult: DiagnosticResult? = null
    var publishedOnMainThread = false
    val server = JavaLanguageServer()

    server.scheduledDiagnosticAttemptOverride = {
      when (attempts.incrementAndGet()) {
        1 -> ScheduledDiagnosticAttempt(DiagnosticResult.NO_UPDATE, retryAfterInteractiveYield = true)
        2 ->
            ScheduledDiagnosticAttempt(
                DiagnosticResult(file, emptyList(), documentVersion = 3, documentRevision = 9L),
                retryAfterInteractiveYield = false,
            )
        else -> error("Unexpected diagnostics attempt after final publication")
      }
    }
    server.connectClient(
        object : ILanguageClient {
          override fun publishDiagnostics(result: DiagnosticResult) {
            publishedResult = result
            publishedOnMainThread = Looper.myLooper() == Looper.getMainLooper()
            published.countDown()
          }

          override fun getDiagnosticAt(file: File, line: Int, column: Int): DiagnosticItem? = null

          override fun performCodeAction(params: PerformCodeActionParams) = Unit

          override fun showDocument(params: ShowDocumentParams): ShowDocumentResult? = null

          override fun showLocations(locations: MutableList<Location>) = Unit

          override fun showReferences(locations: MutableList<Location>, roles: MutableList<ReferenceRole>) = Unit
        },
    )

    try {
      server.onFileOpened(DocumentOpenEvent(file, "class DiagnosticsTimerFixture {}", version = 3, revision = 9L))

      assertTrue("timed out waiting for the retry result to publish", published.await(5, TimeUnit.SECONDS))
      assertEquals("one yielded attempt must schedule exactly one retry", 2, attempts.get())
      assertNotNull("the retry result must reach the client", publishedResult)
      assertFalse("NO_UPDATE must never be published", publishedResult === DiagnosticResult.NO_UPDATE)
      assertEquals(file, publishedResult!!.file)
      assertEquals(3, publishedResult!!.documentVersion)
      assertEquals(9L, publishedResult!!.documentRevision)
      assertTrue("publishDiagnostics must run through Dispatchers.Main", publishedOnMainThread)
    } finally {
      server.shutdown()
    }
  }
}
