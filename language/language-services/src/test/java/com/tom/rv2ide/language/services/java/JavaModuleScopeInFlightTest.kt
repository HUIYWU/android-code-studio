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
package com.tom.rv2ide.language.services.java

import com.tom.rv2ide.builder.model.IJavaCompilerSettings
import com.tom.rv2ide.language.services.java.semantic.JavaModuleScope
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
class JavaModuleScopeInFlightTest {

  @Test
  fun completion_sameKey_joinsOneActiveState() {
    val session = newSession()
    val key = completionKey()

    val (leader, leaderCreated) = session.acquireCompletion(key)
    val (follower, followerCreated) = session.acquireCompletion(key)

    assertTrue(leaderCreated)
    assertFalse(followerCreated)
    assertSame(leader, follower)
  }

  @Test
  fun signature_sameKey_joinsOneActiveState() {
    val session = newSession()
    val key = signatureKey()

    val (leader, leaderCreated) = session.acquireSignatureHelp(key)
    val (follower, followerCreated) = session.acquireSignatureHelp(key)

    assertTrue(leaderCreated)
    assertFalse(followerCreated)
    assertSame(leader, follower)
  }

  @Test
  fun completion_differentRevision_doesNotJoinActiveState() {
    val session = newSession()
    val (first, firstCreated) =
        session.acquireCompletion(completionKey(documentRevision = 1L))
    val (second, secondCreated) =
        session.acquireCompletion(completionKey(documentRevision = 2L))

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun completion_differentPrefix_doesNotJoinActiveState() {
    val session = newSession()
    val firstKey = completionKey(prefix = "value")
    val secondKey = completionKey(prefix = "values")

    val (first, firstCreated) = session.acquireCompletion(firstKey)
    val (second, secondCreated) = session.acquireCompletion(secondKey)

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun signature_differentRevision_doesNotJoinActiveState() {
    val session = newSession()
    val (first, firstCreated) =
        session.acquireSignatureHelp(signatureKey(documentRevision = 1L))
    val (second, secondCreated) =
        session.acquireSignatureHelp(signatureKey(documentRevision = 2L))

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun normalizedEquivalentCompletionPaths_joinOneActiveState() {
    val session = newSession()
    val canonical = completionKey(file = TEST_FILE)
    val equivalent = completionKey(file = TEST_PROJECT_DIR.toPath().resolve("nested/../Sample.java"))

    val (leader, leaderCreated) = session.acquireCompletion(canonical)
    val (follower, followerCreated) = session.acquireCompletion(equivalent)

    assertTrue(leaderCreated)
    assertFalse(followerCreated)
    assertSame(leader, follower)
  }

  @Test
  fun completion_differentEnvironmentGeneration_doesNotJoinActiveState() {
    val session = newSession()
    val (first, firstCreated) =
        session.acquireCompletion(completionKey(epoch = 1L))
    val (second, secondCreated) =
        session.acquireCompletion(completionKey(epoch = 2L))

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun signature_differentEnvironmentGeneration_doesNotJoinActiveState() {
    val session = newSession()
    val (first, firstCreated) =
        session.acquireSignatureHelp(signatureKey(epoch = 1L))
    val (second, secondCreated) =
        session.acquireSignatureHelp(signatureKey(epoch = 2L))

    assertTrue(firstCreated)
    assertTrue(secondCreated)
    assertFalse(first === second)
  }

  @Test
  fun completion_detachingLastSubscriber_cancelsWorker() {
    val session = newSession()
    val (state, _) = session.acquireCompletion(completionKey())
    val subscriber = state.attachSubscriber()

    subscriber.close()

    assertTrue(state.workerCancelChecker.isCancelled())
    assertTrue(state.result.isCompletedExceptionally)
    assertCancellation(state.result.handle { _, error -> error }.join())
  }

  @Test
  fun completion_detachingOneOfTwoSubscribers_keepsWorkerAlive() {
    val session = newSession()
    val (state, _) = session.acquireCompletion(completionKey())
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
    val (state, _) = session.acquireSignatureHelp(signatureKey())
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
    val (older, _) = session.acquireCompletion(olderKey)
    val (current, _) = session.acquireCompletion(currentKey)
    val (otherFile, _) = session.acquireCompletion(otherFileKey)

    session.cancelOlderThan(TEST_FILE, revision = 2L)

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
    val (older, _) = session.acquireSignatureHelp(olderKey)
    val (current, _) = session.acquireSignatureHelp(currentKey)
    val (otherFile, _) = session.acquireSignatureHelp(otherFileKey)

    session.cancelOlderThan(TEST_FILE, revision = 2L)

    assertTrue(older.workerCancelChecker.isCancelled())
    assertFalse(current.workerCancelChecker.isCancelled())
    assertFalse(otherFile.workerCancelChecker.isCancelled())
  }

  @Test
  fun fileClose_cancelsCompletionAndSignatureForThatFileOnly() {
    val session = newSession()
    val (completion, _) = session.acquireCompletion(completionKey())
    val (signature, _) = session.acquireSignatureHelp(signatureKey())
    val (otherFileCompletion, _) =
        session.acquireCompletion(completionKey(file = TEST_OTHER_FILE))

    session.cancelForFile(TEST_FILE)

    assertTrue(completion.workerCancelChecker.isCancelled())
    assertTrue(signature.workerCancelChecker.isCancelled())
    assertFalse(otherFileCompletion.workerCancelChecker.isCancelled())
  }

  @Test
  fun environmentInvalidation_cancelsAllStatesAndAdvancesGeneration() {
    val session = newSession()
    val (completion, _) = session.acquireCompletion(completionKey())
    val (signature, _) = session.acquireSignatureHelp(signatureKey())

    val nextEpoch = session.invalidateEpoch()

    assertEquals(2L, nextEpoch)
    assertEquals(nextEpoch, session.epoch)
    assertTrue(completion.workerCancelChecker.isCancelled())
    assertTrue(signature.workerCancelChecker.isCancelled())
  }

  @Test
  fun completion_terminalState_isReplacedWithoutOldRemovalDeletingNewState() {
    val session = newSession()
    val key = completionKey()
    val (oldState, _) = session.acquireCompletion(key)
    oldState.cancelWorker()

    val (replacement, replacementCreated) = session.acquireCompletion(key)
    session.removeCompletion(oldState)
    val (joinedReplacement, joinedCreated) = session.acquireCompletion(key)

    assertTrue(replacementCreated)
    assertFalse(joinedCreated)
    assertSame(replacement, joinedReplacement)
  }

  @Test
  fun completion_completedState_isReplacedWithoutOldRemovalDeletingNewState() {
    val session = newSession()
    val key = completionKey()
    val (oldState, _) = session.acquireCompletion(key)
    oldState.complete(CompletionResult.EMPTY)

    val (replacement, replacementCreated) = session.acquireCompletion(key)
    session.removeCompletion(oldState)
    val (joinedReplacement, joinedCreated) = session.acquireCompletion(key)

    assertTrue(replacementCreated)
    assertFalse(joinedCreated)
    assertSame(replacement, joinedReplacement)
  }

  @Test
  fun signature_completedState_isReplacedWithoutOldRemovalDeletingNewState() {
    val session = newSession()
    val key = signatureKey()
    val (oldState, _) = session.acquireSignatureHelp(key)
    oldState.complete(SignatureHelp(emptyList(), -1, -1))

    val (replacement, replacementCreated) = session.acquireSignatureHelp(key)
    session.removeSignatureHelp(oldState)
    val (joinedReplacement, joinedCreated) = session.acquireSignatureHelp(key)

    assertTrue(replacementCreated)
    assertFalse(joinedCreated)
    assertSame(replacement, joinedReplacement)
  }

  @Test
  fun signature_terminalState_isReplacedWithoutOldRemovalDeletingNewState() {
    val session = newSession()
    val key = signatureKey()
    val (oldState, _) = session.acquireSignatureHelp(key)
    oldState.cancelWorker()

    val (replacement, replacementCreated) = session.acquireSignatureHelp(key)
    session.removeSignatureHelp(oldState)
    val (joinedReplacement, joinedCreated) = session.acquireSignatureHelp(key)

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

  private fun newSession(): JavaModuleScope =
      JavaModuleScope(testModule(), nextEpoch = { 2L }, initialEpoch = 1L)

  private fun completionKey(
      file: Path = TEST_FILE,
      documentRevision: Long = 1L,
      epoch: Long = 1L,
      prefix: String = "value",
  ): JavaModuleScope.CompletionRequestKey =
      JavaModuleScope.CompletionRequestKey.create(
          file,
          documentVersion = 1,
          documentRevision = documentRevision,
          epoch = epoch,
          cursorIndex = 10L,
          prefix = prefix,
      )

  private fun signatureKey(
      file: Path = TEST_FILE,
      documentRevision: Long = 1L,
      epoch: Long = 1L,
  ): JavaModuleScope.SignatureRequestKey =
      JavaModuleScope.SignatureRequestKey.create(
          file,
          documentVersion = 1,
          documentRevision = documentRevision,
          epoch = epoch,
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
