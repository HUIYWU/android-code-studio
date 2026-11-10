package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object KotlinParserMessages {
  private const val RESOURCE_PREFIX =
      "/com/tom/rv2ide/language/services/kotlin/diagnostics/parser-messages-"
  private const val RESOURCE_SUFFIX = ".txt"

  private val messagesByLanguage = ConcurrentHashMap<String, Map<String, String>>()

  fun messageFor(
      message: String,
      language: String = KotlinDiagnosticTemplates.currentLanguage(),
  ): String {
    if (message.isBlank()) return message
    return messagesFor(language)[message] ?: message
  }

  internal fun loadedMessages(
      language: String = KotlinDiagnosticTemplates.currentLanguage()
  ): Map<String, String> = messagesFor(language)

  private fun messagesFor(language: String): Map<String, String> {
    val normalized = language.lowercase(Locale.ROOT)
    return messagesByLanguage.getOrPut(normalized) { load(normalized) }
  }

  private fun load(language: String): Map<String, String> {
    val resource = "$RESOURCE_PREFIX$language$RESOURCE_SUFFIX"
    val stream = KotlinParserMessages::class.java.getResourceAsStream(resource)
    if (stream == null) {
      if (language != "en") {
        KlsLogs.debug(
            "Kotlin parser messages are missing: language={}, resource={}",
            language,
            resource,
        )
      }
      return emptyMap()
    }
    return stream.use { input ->
      val loaded = LinkedHashMap<String, String>()
      InputStreamReader(input, StandardCharsets.UTF_8).useLines { lines ->
        lines.forEach { line ->
          if (line.isBlank() || line.startsWith("#")) return@forEach
          val separator = line.indexOf('\t')
          if (separator <= 0) return@forEach
          val key = line.substring(0, separator)
          val value = line.substring(separator + 1)
          if (key.isNotEmpty() && value.isNotEmpty()) {
            loaded[key] = value
          }
        }
      }
      loaded
    }
  }
}
