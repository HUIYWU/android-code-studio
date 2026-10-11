package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.JvmPackagePartProvider
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.config.LanguageVersionSettings

internal class AnalysisPackagePartProviderFactory : KotlinPackagePartProviderFactory {
  private lateinit var languageVersionSettings: LanguageVersionSettings
  private var libraryRoots: List<JavaRoot> = emptyList()

  fun setup(languageVersionSettings: LanguageVersionSettings, libraryRoots: List<JavaRoot>) {
    this.languageVersionSettings = languageVersionSettings
    this.libraryRoots = libraryRoots.distinctBy { it.file.url }
  }

  override fun createPackagePartProvider(scope: GlobalSearchScope): PackagePartProvider {
    val collector = object : MessageCollector {
      private var errors = false

      override fun clear() {
        errors = false
      }

      override fun hasErrors(): Boolean = errors

      override fun report(
          severity: CompilerMessageSeverity,
          message: String,
          location: CompilerMessageSourceLocation?,
      ) {
        if (severity.isError) errors = true
        if (severity.isError || severity.isWarning) {
          KlsLogs.warn("Analysis Kotlin binary metadata: severity={}, path={}, message={}", severity, location?.path, message)
        }
      }
    }
    return JvmPackagePartProvider(languageVersionSettings, scope).apply {
      addRoots(libraryRoots, collector)
    }
  }
}