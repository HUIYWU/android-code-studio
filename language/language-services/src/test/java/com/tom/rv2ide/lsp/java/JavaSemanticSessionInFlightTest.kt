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

import com.tom.rv2ide.builder.model.IJavaCompilerSettings
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.java.JavaModule
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic lifecycle tests for strict-key semantic in-flight state without starting javac. */
class JavaSemanticSessionInFlightTest {

  @Test
  fun completion_sameKey_joinsOneActiveState() {
    val session = newSession()
    val key = completionKey()

    val (leader, leaderCreated) = session.acquireInFlightCompletion(key)
    val (follower, followerCreated) = session.acquireInFlightCompletion(key)

    assertTrue(leaderCreated)
    assertFalse(followerCreated)
    assertSame(leader, follower)
  }

  @Test
  fun signature_sameKey_joinsOneActiveState() {
    val session = newSession()
    val key = signatureKey()

    val (leader, leaderCreated) = session.acquireInFlightSignatureHelp(key)
    val (follower, followerCreated) = session.acquireInFlightSignatureHelp(key)

    assertTrue(leaderCreated)
    assertFalse(followerCreated)
    assertSame(leader, follower)
  }

  @Test
  fun completion_differentRevision_doesNotJoinActiveState() {
    val session = newSession()
    val (first, firstCreated) =
        session.acquireInFlightCompletion(completionKey(documentRevision = 1L))
    val (second, secondCreated) =
        session.acquireInFlightCompletion(completionKey(documentRevision = 2L))

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun completion_differentPrefix_doesNotJoinActiveState() {
    val session = newSession()
    val firstKey = completionKey(prefix = "value")
    val secondKey = completionKey(prefix = "values")

    val (first, firstCreated) = session.acquireInFlightCompletion(firstKey)
    val (second, secondCreated) = session.acquireInFlightCompletion(secondKey)

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun signature_differentRevision_doesNotJoinActiveState() {
    val session = newSession()
    val (first, firstCreated) =
        session.acquireInFlightSignatureHelp(signatureKey(documentRevision = 1L))
    val (second, secondCreated) =
        session.acquireInFlightSignatureHelp(signatureKey(documentRevision = 2L))

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun normalizedEquivalentCompletionPaths_joinOneActiveState() {
    val session = newSession()
    val canonical = completionKey(file = TEST_FILE)
    val equivalent = completionKey(file = TEST_PROJECT_DIR.toPath().resolve("nested/../Sample.java"))

    val (leader, leaderCreated) = session.acquireInFlightCompletion(canonical)
    val (follower, followerCreated) = session.acquireInFlightCompletion(equivalent)

    assertTrue(leaderCreated)
    assertFalse(followerCreated)
    assertSame(leader, follower)
  }

  @Test
  fun completion_differentEnvironmentGeneration_doesNotJoinActiveState() {
    val session = newSession()
    val (first, firstCreated) =
        session.acquireInFlightCompletion(completionKey(environmentGeneration = 1L))
    val (second, secondCreated) =
        session.acquireInFlightCompletion(completionKey(environmentGeneration = 2L))

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun signature_differentEnvironmentGeneration_doesNotJoinActiveState() {
    val session = newSession()
    val (first, firstCreated) =
        session.acquireInFlightSignatureHelp(signatureKey(environmentGeneration = 1L))
    val (second, secondCreated) =
        session.acquireInFlightSignatureHelp(signatureKey(environmentGeneration = 2L))

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun completion_detachingLastSubscriber_cancelsWorker() {
    val session = newSession()
    val (state, _) = session.acquireInFlightCompletion(completionKey())
    val subscriber = state.attachSubscriber()

    subscriber.close()

    assertTrue(state.workerCancelChecker.isCancelled())
    assertTrue(state.result.isCompletedExceptionally)
    assertCancellation(state.result.handle { _, error -> error }.join())
  }

  @Test
  fun completion_detachingOneOfTwoSubscribers_keepsWorkerAlive() {
    val session = newSession()
    val (state, _) = session.acquireInFlightCompletion(completionKey())
    val first = state.attachSubscriber()
    val second = state.attachSubscriber()

    first.close()

    assertFalse(state.workerCancelChecker.isCancelled())
    assertFalse(state.result.isDone)
    assertEquals(1, state.subscriberCount)

    second.close()
    assertTrue(state.workerCancelChecker.isCancelled())
  }

  @Test
  fun signature_detachingOneOfTwoSubscribers_keepsWorkerAlive() {
    val session = newSession()
    val (state, _) = session.acquireInFlightSignatureHelp(signatureKey())
    val first = state.attachSubscriber()
    val second = state.attachSubscriber()

    first.close()

    assertFalse(state.workerCancelChecker.isCancelled())
    assertFalse(state.result.isDone)
    assertEquals(1, state.subscriberCount)

    second.close()
    assertTrue(state.workerCancelChecker.isCancelled())
    assertTrue(state.result.isCompletedExceptionally)
  }

  @Test
  fun completion_newerRevision_cancelsOnlyOlderStateForTheSameFile() {
    val session = newSession()
    val olderKey = completionKey(documentRevision = 1L)
    val currentKey = completionKey(documentRevision = 2L)
    val otherFileKey = completionKey(file = TEST_OTHER_FILE, documentRevision = 1L)
    val (older, _) = session.acquireInFlightCompletion(olderKey)
    val (current, _) = session.acquireInFlightCompletion(currentKey)
    val (otherFile, _) = session.acquireInFlightCompletion(otherFileKey)

    session.cancelInFlightCompletionsOlderThan(TEST_FILE, revision = 2L)

    assertTrue(older.workerCancelChecker.isCancelled())
    assertFalse(current.workerCancelChecker.isCancelled())
    assertFalse(otherFile.workerCancelChecker.isCancelled())
  }

  @Test
  fun signature_newerRevision_cancelsOnlyOlderStateForTheSameFile() {
    val session = newSession()
    val olderKey = signatureKey(documentRevision = 1L)
    val currentKey = signatureKey(documentRevision = 2L)
    val otherFileKey = signatureKey(file = TEST_OTHER_FILE, documentRevision = 1L)
    val (older, _) = session.acquireInFlightSignatureHelp(olderKey)
    val (current, _) = session.acquireInFlightSignatureHelp(currentKey)
    val (otherFile, _) = session.acquireInFlightSignatureHelp(otherFileKey)

    session.cancelInFlightCompletionsOlderThan(TEST_FILE, revision = 2L)

    assertTrue(older.workerCancelChecker.isCancelled())
    assertFalse(current.workerCancelChecker.isCancelled())
    assertFalse(otherFile.workerCancelChecker.isCancelled())
  }

  @Test
  fun fileClose_cancelsCompletionAndSignatureForThatFileOnly() {
    val session = newSession()
    val (completion, _) = session.acquireInFlightCompletion(completionKey())
    val (signature, _) = session.acquireInFlightSignatureHelp(signatureKey())
    val (otherFileCompletion, _) =
        session.acquireInFlightCompletion(completionKey(file = TEST_OTHER_FILE))

    session.cancelInFlightCompletionsForFile(TEST_FILE)

    assertTrue(completion.workerCancelChecker.isCancelled())
    assertTrue(signature.workerCancelChecker.isCancelled())
    assertFalse(otherFileCompletion.workerCancelChecker.isCancelled())
  }

  @Test
  fun environmentInvalidation_cancelsAllStatesAndAdvancesGeneration() {
    val session = newSession()
    val (completion, _) = session.acquireInFlightCompletion(completionKey())
    val (signature, _) = session.acquireInFlightSignatureHelp(signatureKey())

    val nextGeneration = session.invalidateEnvironment()

    assertEquals(2L, nextGeneration)
    assertEquals(nextGeneration, session.environmentGeneration)
    assertTrue(completion.workerCancelChecker.isCancelled())
    assertTrue(signature.workerCancelChecker.isCancelled())
  }

  @Test
  fun completion_terminalState_isReplacedWithoutOldRemovalDeletingNewState() {
    val session = newSession()
    val key = completionKey()
    val (oldState, _) = session.acquireInFlightCompletion(key)
    oldState.cancelWorker()

    val (replacement, replacementCreated) = session.acquireInFlightCompletion(key)
    session.removeInFlightCompletion(oldState)
    val (joinedReplacement, joinedCreated) = session.acquireInFlightCompletion(key)

    assertTrue(replacementCreated)
    assertFalse(joinedCreated)
    assertSame(replacement, joinedReplacement)
  }

  @Test
  fun completion_completedState_isReplacedWithoutOldRemovalDeletingNewState() {
    val session = newSession()
    val key = completionKey()
    val (oldState, _) = session.acquireInFlightCompletion(key)
    oldState.complete(CompletionResult.EMPTY)

    val (replacement, replacementCreated) = session.acquireInFlightCompletion(key)
    session.removeInFlightCompletion(oldState)
    val (joinedReplacement, joinedCreated) = session.acquireInFlightCompletion(key)

    assertTrue(replacementCreated)
    assertFalse(joinedCreated)
    assertSame(replacement, joinedReplacement)
  }

  @Test
  fun signature_completedState_isReplacedWithoutOldRemovalDeletingNewState() {
    val session = newSession()
    val key = signatureKey()
    val (oldState, _) = session.acquireInFlightSignatureHelp(key)
    oldState.complete(SignatureHelp(emptyList(), -1, -1))

    val (replacement, replacementCreated) = session.acquireInFlightSignatureHelp(key)
    session.removeInFlightSignatureHelp(oldState)
    val (joinedReplacement, joinedCreated) = session.acquireInFlightSignatureHelp(key)

    assertTrue(replacementCreated)
    assertFalse(joinedCreated)
    assertSame(replacement, joinedReplacement)
  }

  @Test
  fun signature_terminalState_isReplacedWithoutOldRemovalDeletingNewState() {
    val session = newSession()
    val key = signatureKey()
    val (oldState, _) = session.acquireInFlightSignatureHelp(key)
    oldState.cancelWorker()

    val (replacement, replacementCreated) = session.acquireInFlightSignatureHelp(key)
    session.removeInFlightSignatureHelp(oldState)
    val (joinedReplacement, joinedCreated) = session.acquireInFlightSignatureHelp(key)

    assertTrue(replacementCreated)
    assertFalse(joinedCreated)
    assertSame(replacement, joinedReplacement)
  }

  private fun assertCancellation(error: Throwable?) {
    var current = error
    while (current != null && current !is CancellationException) {
      current = current.cause
    }
    assertTrue("expected CancellationException but was $error", current is CancellationException)
  }

  private fun newSession(): JavaSemanticSession =
      JavaSemanticSession(testModule(), nextEnvironmentGeneration = { 2L }, initialEnvironmentGeneration = 1L)

  private fun completionKey(
      file: Path = TEST_FILE,
      documentRevision: Long = 1L,
      environmentGeneration: Long = 1L,
      prefix: String = "value",
  ): JavaSemanticSession.CompletionRequestKey =
      JavaSemanticSession.CompletionRequestKey.create(
          file,
          documentVersion = 1,
          documentRevision = documentRevision,
          environmentGeneration = environmentGeneration,
          cursorIndex = 10L,
          prefix = prefix,
      )

  private fun signatureKey(
      file: Path = TEST_FILE,
      documentRevision: Long = 1L,
      environmentGeneration: Long = 1L,
  ): JavaSemanticSession.SignatureRequestKey =
      JavaSemanticSession.SignatureRequestKey.create(
          file,
          documentVersion = 1,
          documentRevision = documentRevision,
          environmentGeneration = environmentGeneration,
          cursorIndex = 10L,
      )

  private fun testModule(): ModuleProject =
      JavaModule(
          name = "semantic-session-test",
          description = "semantic session test module",
          path = ":semantic-session-test",
          projectDir = TEST_PROJECT_DIR,
          buildDir = File(TEST_PROJECT_DIR, "build"),
          buildScript = File(TEST_PROJECT_DIR, "build.gradle"),
          tasks = emptyList(),
          compilerSettings = TestCompilerSettings,
          contentRoots = emptyList(),
          dependencies = emptyList(),
          classesJar = null,
      )

  private object TestCompilerSettings : IJavaCompilerSettings() {
    override val javaSourceVersion: String = "17"
    override val javaBytecodeVersion: String = "17"
  }

  private companion object {
    val TEST_PROJECT_DIR: File = File(System.getProperty("java.io.tmpdir"), "semantic-session-test")
    val TEST_FILE: Path = File(TEST_PROJECT_DIR, "Sample.java").toPath()
    val TEST_OTHER_FILE: Path = File(TEST_PROJECT_DIR, "OtherSample.java").toPath()
  }
}
