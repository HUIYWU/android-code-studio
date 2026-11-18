package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class SharedAnalysisWorkspaceOwnerTest {

  @Test
  fun leasesReuseTheEnvironmentAndFreshAcquireStartsAnother() {
    KotlinAnalysisTestSupport.withWorkspace {
      KotlinAnalysisTestSupport.prepareAnalysisPaths()
      val provider = KotlinProjectClasspathProvider(workspace)
      val pluginRoot = KotlinAnalysisTestSupport.analysisApiJar()
      val first = SharedAnalysisWorkspaceOwner.acquire(provider, pluginRoot, fresh = false)
      val firstEnvironment =
          requireNotNull(first.environment) {
            first.failureReason ?: "shared Analysis environment failed to start"
          }
      try {
        val second = SharedAnalysisWorkspaceOwner.acquire(provider, pluginRoot, fresh = false)
        try {
          assertSame(
              "a lease for the same workspace must reuse the environment",
              firstEnvironment,
              second.environment,
          )
        } finally {
          second.close()
        }
        val third = SharedAnalysisWorkspaceOwner.acquire(provider, pluginRoot, fresh = false)
        try {
          assertSame(
              "one released lease must keep the environment alive",
              firstEnvironment,
              third.environment,
          )
        } finally {
          third.close()
        }
        val fresh = SharedAnalysisWorkspaceOwner.acquire(provider, pluginRoot, fresh = true)
        try {
          assertNotSame(
              "a fresh acquire must create an independent environment",
              firstEnvironment,
              requireNotNull(fresh.environment) {
                fresh.failureReason ?: "fresh Analysis environment failed"
              },
          )
        } finally {
          fresh.close()
        }
      } finally {
        first.close()
      }
      val after = SharedAnalysisWorkspaceOwner.acquire(provider, pluginRoot, fresh = false)
      try {
        assertNotSame(
            "all leases released must close the previous environment",
            firstEnvironment,
            requireNotNull(after.environment) {
              after.failureReason ?: "replacement Analysis environment failed"
            },
        )
      } finally {
        after.close()
      }
  }

  @Test
  fun runtimeCloseKeepsTheEnvironmentForAnotherLease() {
    KotlinAnalysisTestSupport.withWorkspace {
      KotlinAnalysisTestSupport.prepareAnalysisPaths()
      val provider = KotlinProjectClasspathProvider(workspace)
      val pluginRoot = KotlinAnalysisTestSupport.analysisApiJar()
      val runtime = KotlinStandaloneAnalysisRuntime(provider, pluginRoot)
      assertTrue("Kotlin Analysis runtime must start", runtime.start())
      val extra = SharedAnalysisWorkspaceOwner.acquire(provider, pluginRoot, fresh = false)
      try {
        val environment =
            requireNotNull(extra.environment) {
              extra.failureReason ?: "shared Analysis environment failed to start"
            }
        runtime.close()
        val afterClose = SharedAnalysisWorkspaceOwner.acquire(provider, pluginRoot, fresh = false)
        try {
          assertSame(
              "closing the Kotlin runtime must keep the environment leased by Java",
              environment,
              afterClose.environment,
          )
          assertTrue(
              "Java interop must stay registered while a lease remains",
              JavaPsiInterop.kotlinTopLevelTypesStamp().resolverGeneration > 0L,
          )
        } finally {
          afterClose.close()
        }
      } finally {
        extra.close()
      }
    }
  }
}
}