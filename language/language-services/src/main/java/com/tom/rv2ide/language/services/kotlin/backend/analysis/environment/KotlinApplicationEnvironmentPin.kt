package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment.Companion.getOrCreateApplicationEnvironmentForProduction
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration

/** Keeps the shared Production application alive beyond an individual workspace lifetime. */
@OptIn(K1Deprecation::class)
internal object KotlinApplicationEnvironmentPin {
  private var pinned = false
  private val pin = Disposer.newDisposable("acs-kotlin-application-environment-pin")

  @Synchronized
  fun ensure(configuration: CompilerConfiguration) {
    if (pinned) return
    getOrCreateApplicationEnvironmentForProduction(pin, configuration)
    pinned = true
  }
}
