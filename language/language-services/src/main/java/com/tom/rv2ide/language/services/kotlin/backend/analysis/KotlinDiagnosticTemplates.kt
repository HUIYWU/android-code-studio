package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.resources.localization.LocaleProvider
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.KtPsiDiagnostic
import org.jetbrains.kotlin.diagnostics.SimpleKtDiagnosticRenderer

internal object KotlinDiagnosticTemplates {
  private const val RESOURCE_PREFIX =
      "/com/tom/rv2ide/language/services/kotlin/diagnostics/templates-"
  private const val RESOURCE_SUFFIX = ".properties"

  private val templatesByLanguage = ConcurrentHashMap<String, Map<String, String>>()

  fun messageFor(
      diagnostic: KtPsiDiagnostic,
      language: String = currentLanguage(),
      templates: Map<String, String> = templatesFor(language),
  ): String {
    val ktDiagnostic = diagnostic as? KtDiagnostic ?: return diagnostic.factory.name
    val renderer =
        runCatching { diagnostic.factory.ktRenderer }.getOrElse { return diagnostic.factory.name }
    val template = templates[diagnostic.factory.name] ?: return renderer.render(ktDiagnostic)
    if (renderer is SimpleKtDiagnosticRenderer) return template
    if ('{' !in template && "''" !in template) return template
    return runCatching {
          MessageFormat.format(template, *renderer.renderParameters(ktDiagnostic))
        }
        .getOrElse { renderer.render(ktDiagnostic) }
  }

  internal fun currentLanguage(): String {
    val selected = GeneralPreferences.selectedLocale
    val locale = selected?.let { LocaleProvider.getLocale(it) } ?: Locale.getDefault()
    return locale.language
  }

  internal fun templatesFor(language: String): Map<String, String> {
    val normalized = language.lowercase(Locale.ROOT)
    return templatesByLanguage.getOrPut(normalized) { load(normalized) }
  }

  internal fun loadedTemplates(language: String = currentLanguage()): Map<String, String> =
      templatesFor(language)

  private fun load(language: String): Map<String, String> {
    val resource = "$RESOURCE_PREFIX$language$RESOURCE_SUFFIX"
    val stream = KotlinDiagnosticTemplates::class.java.getResourceAsStream(resource)
    if (stream == null) {
      if (language != "en") {
        KlsLogs.debug(
            "Kotlin diagnostic templates are missing: language={}, resource={}",
            language,
            resource,
        )
      }
      return emptyMap()
    }
    return stream.use { input ->
      val properties = Properties()
      InputStreamReader(input, StandardCharsets.UTF_8).use(properties::load)
      val loaded = LinkedHashMap<String, String>(properties.size)
      for (name in properties.stringPropertyNames()) {
        loaded[name] = properties.getProperty(name)
      }
      loaded
    }
  }
}
