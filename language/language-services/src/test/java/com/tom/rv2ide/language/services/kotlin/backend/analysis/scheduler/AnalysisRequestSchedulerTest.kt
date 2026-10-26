package com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler

import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisRequestSchedulerTest {

  @Test
  fun cancelledGenerationRequestDoesNotBlockLaterMutation() {
    val scheduler = AnalysisRequestScheduler("acs-kotlin-analysis-test")
    val path = Path.of("/project/Main.kt")
    val gateEntered = CountDownLatch(1)
    val gateRelease = CountDownLatch(1)
    val laterExecuted = CountDownLatch(1)
    try {
      scheduler.submit(
          kind = AnalysisRequestKind.SEMANTIC,
          path = path,
          backendGeneration = 1L,
          cancelChecker = ICancelChecker.NOOP,
          action = {
            gateEntered.countDown()
            assertTrue(gateRelease.await(2, TimeUnit.SECONDS))
            Unit
          },
      )
      assertTrue(gateEntered.await(2, TimeUnit.SECONDS))

      scheduler.submit(
          kind = AnalysisRequestKind.INDEXING,
          path = path,
          backendGeneration = 1L,
          cancelChecker = ICancelChecker.NOOP,
          mutationPaths = setOf(path),
          action = {},
      )
      scheduler.cancelRequests(AnalysisRequestKind.INDEXING, 1L)

      gateRelease.countDown()
      scheduler.submit(
          kind = AnalysisRequestKind.INDEXING,
          path = path,
          backendGeneration = 2L,
          cancelChecker = ICancelChecker.NOOP,
          mutationPaths = setOf(path),
          action = {
            laterExecuted.countDown()
            Unit
          },
      )

      assertTrue(laterExecuted.await(2, TimeUnit.SECONDS))
    } finally {
      gateRelease.countDown()
      scheduler.close()
    }
  }
  @Test
  fun closeCancelsPendingRequestWithoutExecutingAction() {
    val scheduler = AnalysisRequestScheduler("acs-kotlin-analysis-close-test")
    val gateEntered = CountDownLatch(1)
    val gateRelease = CountDownLatch(1)
    val executed = AtomicBoolean(false)
    try {
      scheduler.submit(
          kind = AnalysisRequestKind.SEMANTIC,
          cancelChecker = ICancelChecker.NOOP,
          action = {
            gateEntered.countDown()
            try {
              gateRelease.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            Unit
          },
      )
      assertTrue(gateEntered.await(2, TimeUnit.SECONDS))

      val future = scheduler.submit(
          kind = AnalysisRequestKind.INDEXING,
          backendGeneration = 1L,
          cancelChecker = ICancelChecker.NOOP,
          action = {
            executed.set(true)
            Unit
          },
      )
      scheduler.close()
      gateRelease.countDown()

      assertTrue(future.isDone)
      assertNull(future.join())
      assertFalse(executed.get())
    } finally {
      gateRelease.countDown()
      scheduler.close()
    }
  }

  @Test
  fun cancellingActiveCancellableRequestReleasesMutationFence() {
    val scheduler = AnalysisRequestScheduler("acs-kotlin-analysis-active-cancel-test")
    val path = Path.of("/project/Main.kt")
    val entered = CountDownLatch(1)
    val laterExecuted = CountDownLatch(1)
    try {
      scheduler.submit(
          kind = AnalysisRequestKind.SEMANTIC,
          path = path,
          backendGeneration = 1L,
          cancelChecker = ICancelChecker.NOOP,
          mutationPaths = setOf(path),
          cancellableAction = { cancelChecker ->
            entered.countDown()
            while (!cancelChecker.isCancelled()) {
              try {
                Thread.sleep(20)
              } catch (_: InterruptedException) {
              }
            }
            Unit
          },
          action = { Unit },
      )
      assertTrue(entered.await(2, TimeUnit.SECONDS))

      scheduler.submit(
          kind = AnalysisRequestKind.INDEXING,
          path = path,
          backendGeneration = 2L,
          cancelChecker = ICancelChecker.NOOP,
          requiredMutationPaths = setOf(path),
          action = {
            laterExecuted.countDown()
            Unit
          },
      )

      scheduler.cancelRequests(AnalysisRequestKind.SEMANTIC, 1L)

      assertTrue(laterExecuted.await(2, TimeUnit.SECONDS))
    } finally {
      scheduler.close()
    }
  }

  @Test
  fun closeIsIdempotent() {
    val scheduler = AnalysisRequestScheduler("acs-kotlin-analysis-repeat-close-test")

    scheduler.close()
    scheduler.close()

    val future = scheduler.submit(
        kind = AnalysisRequestKind.INDEXING,
        cancelChecker = ICancelChecker.NOOP,
        action = { error("closed scheduler executed a request") },
    )

    assertTrue(future.isDone)
    assertNull(future.join())
  }

  @Test
  fun requestKindsExecuteInPriorityOrderWhenSubmittedBeforeWorkerRelease() {
    val scheduler = AnalysisRequestScheduler("acs-kotlin-analysis-priority-test")
    val gateEntered = CountDownLatch(1)
    val gateRelease = CountDownLatch(1)
    val executionOrder = mutableListOf<AnalysisRequestKind>()
    val completed = CountDownLatch(4)
    try {
      scheduler.submit(
          kind = AnalysisRequestKind.INDEXING,
          cancelChecker = ICancelChecker.NOOP,
          action = {
            gateEntered.countDown()
            assertTrue(gateRelease.await(2, TimeUnit.SECONDS))
            synchronized(executionOrder) { executionOrder += AnalysisRequestKind.INDEXING }
            completed.countDown()
            Unit
          },
      )
      assertTrue(gateEntered.await(2, TimeUnit.SECONDS))

      listOf(
          AnalysisRequestKind.DIAGNOSTICS,
          AnalysisRequestKind.COMMAND,
          AnalysisRequestKind.SEMANTIC,
      ).forEach { kind ->
        scheduler.submit(
            kind = kind,
            cancelChecker = ICancelChecker.NOOP,
            action = {
              synchronized(executionOrder) { executionOrder += kind }
              completed.countDown()
              Unit
            },
        )
      }
      gateRelease.countDown()

      assertTrue(completed.await(2, TimeUnit.SECONDS))
      assertEquals(
          listOf(
              AnalysisRequestKind.INDEXING,
              AnalysisRequestKind.SEMANTIC,
              AnalysisRequestKind.COMMAND,
              AnalysisRequestKind.DIAGNOSTICS,
          ),
          executionOrder,
      )
    } finally {
      gateRelease.countDown()
      scheduler.close()
    }
  }

}
